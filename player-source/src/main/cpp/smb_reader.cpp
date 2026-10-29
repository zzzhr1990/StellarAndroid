#include "smb_reader.h"
#include "remote_reader.h"
#include <smb2/smb2.h>
#include <smb2/libsmb2.h>
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <fcntl.h>
#include <mutex>
#include <poll.h>
#include <sstream>
#include <unordered_map>

namespace stellar {
namespace {
using Clock = std::chrono::steady_clock;
struct Source {
    std::string server, share, path, user, password, domain;
    bool seal = false;
    std::atomic<bool> cancelled{false};
    std::atomic<int> error{0}, dialect{0}, opens{0};
    std::atomic<uint32_t> ntstatus{0};
    std::atomic<int64_t> bytes{0}, reads{0}, seeks{0}, length{-1};
    ~Source() { std::fill(password.begin(), password.end(), '\0'); }
};
std::mutex sourcesMutex;
std::unordered_map<std::string, std::shared_ptr<Source>> sources;
std::shared_ptr<Source> lookup(const std::string& token) {
    std::lock_guard lock(sourcesMutex);
    const auto it = sources.find(token);
    return it == sources.end() ? nullptr : it->second;
}

class SmbIO final : public RemoteReader {
    std::shared_ptr<Source> source_;
    smb2_context* ctx_ = nullptr;
    smb2fh* file_ = nullptr;
    int64_t position_ = 0, size_ = 0;
    std::atomic<bool> aborted_{false};
    std::mutex timeoutMutex_;
    int64_t timeoutMs_ = 10000;
    TimeoutCallback timeoutCallback_;
    struct Operation { bool done = false; int status = 0; void* data = nullptr; };
    static void completed(smb2_context*, int status, void* data, void* opaque) {
        auto& op = *static_cast<Operation*>(opaque);
        op.status = status; op.data = data; op.done = true;
    }
    bool cancelled() const { return aborted_ || (source_ && source_->cancelled); }
    void dispose() {
        // Context destruction finishes pending callbacks on this IO thread, while
        // their Operation and read buffers are still alive. abort() never touches ctx_.
        if (ctx_) smb2_destroy_context(ctx_);
        ctx_ = nullptr; file_ = nullptr;
    }
    void error(int status) {
        if (!source_ || cancelled()) return;
        const auto nt = ctx_ ? static_cast<uint32_t>(smb2_get_nterror(ctx_)) : 0u;
        source_->ntstatus = nt;
        int kind = 6; // network/source failure
        if (nt == 0xc000006d || nt == 0xc000006a || nt == 0xc0000064 || nt == 0xc0000072 || nt == 0xc0000234) kind = 1;
        else if (nt == 0xc00000cc || nt == 0xc0000034 || nt == 0xc000003a || status == -ENOENT) kind = 2;
        else if (nt == 0xc0000022 || status == -EACCES) kind = 3;
        else if (status == -ETIMEDOUT) kind = 4;
        else if (source_->seal && source_->opens == 0) kind = 5;
        source_->error = kind;
    }
    bool await(int queued, Operation& op) {
        if (queued < 0) { error(queued); dispose(); return false; }
        auto since = Clock::now();
        while (!op.done) {
            if (cancelled()) { dispose(); return false; }
            int64_t timeout;
            TimeoutCallback callback;
            { std::lock_guard lock(timeoutMutex_); timeout = timeoutMs_; callback = timeoutCallback_; }
            const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - since).count();
            if (timeout >= 0 && elapsed >= timeout) {
                if (!callback || callback(elapsed)) { error(-ETIMEDOUT); dispose(); return false; }
                since = Clock::now();
            }
            size_t count = 0; int connectTimeout = -1;
            const auto fds = smb2_get_fds(ctx_, &count, &connectTimeout);
            std::vector<pollfd> pollfds;
            for (size_t i = 0; i < count; ++i)
                pollfds.push_back({fds[i], static_cast<short>(smb2_which_events(ctx_)), 0});
            const int waitMs = connectTimeout < 0 ? 50 : std::min(50, connectTimeout);
            const int rc = ::poll(pollfds.data(), pollfds.size(), waitMs);
            if (rc < 0 && errno == EINTR) continue;
            if (rc < 0) { error(-errno); dispose(); return false; }
            if (!rc && connectTimeout >= 0 && waitMs == connectTimeout) {
                if (smb2_service_fd(ctx_, -1, 0) < 0) { error(-EIO); dispose(); return false; }
            } else {
                // Re-fetch descriptors after every service: a successful connection
                // closes the other Happy Eyeballs sockets.
                bool serviced = false;
                for (const auto& fd : pollfds) if (fd.revents) {
                    if (smb2_service_fd(ctx_, fd.fd, fd.revents) < 0) { error(-EIO); dispose(); return false; }
                    serviced = true; break;
                }
                if (!serviced && !pollfds.empty() && smb2_service_fd(ctx_, pollfds[0].fd, 0) < 0) {
                    error(-EIO); dispose(); return false;
                }
            }
        }
        if (op.status < 0) { error(op.status); dispose(); return false; }
        return !cancelled();
    }
public:
    ~SmbIO() override { dispose(); }
    bool isSeekable() const override { return file_ != nullptr; }
    bool abort() override { aborted_ = true; return true; }
    bool setTimeout(int64_t ms, const TimeoutCallback& cb) override {
        std::lock_guard lock(timeoutMutex_); timeoutMs_ = ms; timeoutCallback_ = cb; return true;
    }
    int64_t position() const override { return position_; }
    int64_t size() const override { return size_; }
    bool seek(int64_t offset, int from) override {
        if (!file_ || cancelled()) return false;
        const int64_t base = from == SEEK_SET ? 0 : from == SEEK_CUR ? position_ : from == SEEK_END ? size_ : -1;
        if (base < 0 || (offset > 0 && base > INT64_MAX - offset) || (offset < 0 && offset < -base)) return false;
        position_ = base + offset; ++source_->seeks; return true;
    }
    int64_t read(uint8_t* data, int64_t maximum) override {
        if (!file_ || cancelled() || maximum < 0) return -1;
        if (!maximum || position_ >= size_) return 0;
        const auto count = static_cast<uint32_t>(std::min({maximum, size_ - position_,
            static_cast<int64_t>(smb2_get_max_read_size(ctx_)), int64_t{1024 * 1024}}));
        if (!count) { error(-EIO); return -1; }
        Operation op;
        if (!await(smb2_pread_async(ctx_, file_, data, count, position_, completed, &op), op)) return -1;
        // Short positive reads are valid. Zero before stat's EOF is a truncated file.
        if (!op.status) { error(-EIO); return -1; }
        position_ += op.status; source_->bytes += op.status; ++source_->reads;
        return op.status;
    }
    bool open(const std::string& token) override {
        dispose(); position_ = size_ = 0;
        source_ = lookup(token);
        if (!source_ || cancelled()) return false;
        ctx_ = smb2_init_context();
        if (!ctx_) { error(-ENOMEM); return false; }
        // Public header documents the enum but does not export its definition.
        // This pinned build disables GSSAPI/Kerberos, so default auth is NTLMSSP.
        smb2_set_user(ctx_, source_->user.c_str());
        smb2_set_password(ctx_, source_->password.c_str());
        smb2_set_domain(ctx_, source_->domain.c_str());
        smb2_set_version(ctx_, source_->seal ? SMB2_VERSION_ANY3 : SMB2_VERSION_ANY);
        smb2_set_seal(ctx_, source_->seal ? 1 : 0);
        smb2_set_security_mode(ctx_, SMB2_NEGOTIATE_SIGNING_ENABLED);
        // Guest/anonymous sessions cannot generate a signing key.
        smb2_set_sign(ctx_, !source_->user.empty() && !source_->seal);
        smb2_set_timeout(ctx_, 10);
        Operation connect;
        if (!await(smb2_connect_share_async(ctx_, source_->server.c_str(), source_->share.c_str(),
                   source_->user.c_str(), completed, &connect), connect)) return false;
        source_->dialect = smb2_get_dialect(ctx_);
        if (source_->seal && source_->dialect < SMB2_VERSION_0300) { error(-EPROTONOSUPPORT); dispose(); return false; }
        Operation open;
        if (!await(smb2_open_async(ctx_, source_->path.c_str(), O_RDONLY, completed, &open), open)) return false;
        file_ = static_cast<smb2fh*>(open.data);
        smb2_stat_64 st{}; Operation stat;
        if (!await(smb2_fstat_async(ctx_, file_, &st, completed, &stat), stat)) return false;
        if (st.smb2_type != SMB2_TYPE_FILE || st.smb2_size > INT64_MAX) { error(-EINVAL); dispose(); return false; }
        size_ = static_cast<int64_t>(st.smb2_size); source_->length = size_; ++source_->opens;
        source_->error = 0;
        return true;
    }
};
}

std::unique_ptr<RemoteReader> makeSmbReader() { return std::make_unique<SmbIO>(); }
void addSmbSource(const std::string& token, std::string server, std::string share, std::string path,
                  std::string user, std::string password, std::string domain, bool seal) {
    auto source = std::make_shared<Source>();
    source->server = std::move(server); source->share = std::move(share); source->path = std::move(path);
    source->user = std::move(user); source->password = std::move(password); source->domain = std::move(domain); source->seal = seal;
    std::lock_guard lock(sourcesMutex); sources[token] = std::move(source);
}
void cancelSmbSource(const std::string& token) { if (auto s = lookup(token)) s->cancelled = true; }
void removeSmbSource(const std::string& token) { std::lock_guard lock(sourcesMutex); sources.erase(token); }
int smbError(const std::string& token) { auto s = lookup(token); return s ? s->error.load() : 0; }
std::string smbDiagnostics(const std::string& token) {
    auto s = lookup(token); if (!s) return "{}";
    std::ostringstream out;
    out << "{\"dialect\":" << s->dialect << ",\"encryptionRequired\":" << (s->seal ? "true" : "false")
        << ",\"opens\":" << s->opens << ",\"bytes\":" << s->bytes << ",\"reads\":" << s->reads
        << ",\"seeks\":" << s->seeks << ",\"error\":" << s->error << ",\"size\":" << s->length << ",\"ntstatus\":" << s->ntstatus << '}';
    return out.str();
}
}
