#include "nfs_reader.h"
#include "remote_reader.h"
#include <nfsc/libnfs.h>
#include <nfsc/libnfs-raw.h>
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <fcntl.h>
#include <mutex>
#include <poll.h>
#include <sstream>
#include <sys/stat.h>
#include <unordered_map>

namespace stellar {
namespace {
using Clock = std::chrono::steady_clock;
struct Source {
    std::string server, exportPath, path;
    int version, uid, gid, port;
    std::atomic<bool> cancelled{false};
    std::atomic<int> error{0}, status{0}, opens{0};
    std::atomic<int64_t> bytes{0}, reads{0}, seeks{0}, length{0}, maxOffset{0};
};
std::mutex sourcesMutex;
std::unordered_map<std::string, std::shared_ptr<Source>> sources;
std::shared_ptr<Source> lookup(const std::string& token) {
    std::lock_guard lock(sourcesMutex);
    auto it = sources.find(token);
    return it == sources.end() ? nullptr : it->second;
}
class NfsIO final : public RemoteReader {
    std::shared_ptr<Source> source_;
    nfs_context* ctx_ = nullptr;
    nfsfh* file_ = nullptr;
    int64_t position_ = 0, size_ = 0;
    std::atomic<bool> aborted_{false};
    std::mutex timeoutMutex_;
    int64_t timeoutMs_ = 10000;
    TimeoutCallback timeoutCallback_;
    struct Operation {
        bool done = false;
        int status = 0;
        nfsfh** opened = nullptr;
        nfs_stat_64* stat = nullptr;
    };
    static void completed(int status, nfs_context*, void* data, void* opaque) {
        auto& op = *static_cast<Operation*>(opaque);
        op.status = status;
        // Stat's callback data is ephemeral; copy it before returning to libnfs.
        if (status >= 0 && op.stat) *op.stat = *static_cast<nfs_stat_64*>(data);
        if (status >= 0 && op.opened) *op.opened = static_cast<nfsfh*>(data);
        op.done = true;
    }
    bool cancelled() const { return aborted_ || (source_ && source_->cancelled); }
    void dispose(bool interrupted = false) {
        if (!ctx_) return;
        // Drain outstanding read/open callbacks before closing their handle/buffer.
        if (interrupted) rpc_disconnect(nfs_get_rpc_context(ctx_), "cancelled");
        Operation close;
        if (file_) {
            if (nfs_close_async(ctx_, file_, completed, &close) < 0) {
                // Pinned libnfs retains the handle on enqueue failure. Its v3
                // read-only close frees it locally without allocation or RPC.
                nfs_set_version(ctx_, 3);
                nfs_close_async(ctx_, file_, completed, &close);
            }
            file_ = nullptr;
            const auto deadline = Clock::now() + std::chrono::milliseconds(250);
            while (!close.done && !interrupted && !cancelled() && Clock::now() < deadline) {
                pollfd fd{nfs_get_fd(ctx_), static_cast<short>(nfs_which_events(ctx_)), 0};
                if (::poll(&fd, 1, 25) < 0 && errno != EINTR) break;
                if (nfs_service(ctx_, fd.revents) < 0) break;
            }
        }
        // Also completes a pending CLOSE, with Operation still alive.
        nfs_destroy_context(ctx_); ctx_ = nullptr;
    }
    void error(int status) {
        if (!source_ || cancelled()) return;
        source_->status = status;
        source_->error = status == -ENOENT || status == -ENOTDIR ? 2 :
            status == -EACCES || status == -EPERM ? 3 : status == -ETIMEDOUT ? 4 : 6;
    }
    bool await(int queued, Operation& op) {
        if (queued < 0) { error(queued); dispose(true); return false; }
        auto since = Clock::now();
        while (!op.done) {
            if (cancelled()) { dispose(true); return false; }
            int64_t timeout; TimeoutCallback callback;
            { std::lock_guard lock(timeoutMutex_); timeout = timeoutMs_; callback = timeoutCallback_; }
            const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - since).count();
            if (timeout >= 0 && elapsed >= timeout) {
                if (!callback || callback(elapsed)) { error(-ETIMEDOUT); dispose(true); return false; }
                since = Clock::now();
            }
            pollfd fd{nfs_get_fd(ctx_), static_cast<short>(nfs_which_events(ctx_)), 0};
            const int rc = ::poll(&fd, 1, 50);
            if (rc < 0 && errno == EINTR) continue;
            if (rc < 0) { error(-errno); dispose(true); return false; }
            // Service even on timeout, for libnfs's RPC deadlines.
            if (nfs_service(ctx_, fd.revents) < 0) {
                error(op.done && op.status < 0 ? op.status : -EIO); dispose(true); return false;
            }
        }
        if (op.status < 0 || cancelled()) { error(op.status); dispose(true); return false; }
        return true;
    }
public:
    ~NfsIO() override { dispose(); }
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
        const auto count = static_cast<size_t>(std::min({maximum, size_ - position_, int64_t{1024 * 1024}}));
        Operation op;
        if (!await(nfs_pread_async(ctx_, file_, data, count, position_, completed, &op), op)) return -1;
        if (!op.status || static_cast<size_t>(op.status) > count) { error(-EIO); dispose(); return -1; }
        source_->maxOffset = std::max(source_->maxOffset.load(), position_);
        position_ += op.status; source_->bytes += op.status; ++source_->reads;
        return op.status;
    }
    bool open(const std::string& token) override {
        dispose(); position_ = size_ = 0;
        source_ = lookup(token);
        if (!source_ || cancelled()) return false;
        ctx_ = nfs_init_context();
        if (!ctx_) { error(-ENOMEM); return false; }
        nfs_set_autoreconnect(ctx_, 0); nfs_set_retrans(ctx_, 0);
        nfs_set_timeout(ctx_, 10000);
        nfs_set_auto_traverse_mounts(ctx_, 0);
        nfs_set_nfsport(ctx_, source_->port);
        if (source_->uid >= 0) nfs_set_uid(ctx_, source_->uid);
        if (source_->gid >= 0) nfs_set_gid(ctx_, source_->gid);
        nfs_set_auxiliary_gids(ctx_, 0, nullptr);
        if (nfs_set_version(ctx_, source_->version) != 0) { error(-EPROTONOSUPPORT); dispose(); return false; }
        Operation mount;
        if (!await(nfs_mount_async(ctx_, source_->server.c_str(), source_->exportPath.c_str(), completed, &mount), mount)) return false;
        // In pinned 8.0.0, nfs3_initial_open_cb converts an already-negative errno
        // through nfsstat3_to_errno again, producing ERANGE. stat preserves the
        // path/permission error and avoids parsing library diagnostic strings.
        nfs_stat_64 st{}; Operation pathStat; pathStat.stat = &st;
        if (!await(nfs_stat64_async(ctx_, source_->path.c_str(), completed, &pathStat), pathStat)) return false;
        if (!S_ISREG(st.nfs_mode)) { error(-EINVAL); dispose(); return false; }
        Operation open; open.opened = &file_;
        if (!await(nfs_open_async(ctx_, source_->path.c_str(), O_RDONLY, completed, &open), open)) return false;
        Operation stat; stat.stat = &st;
        if (!await(nfs_fstat64_async(ctx_, file_, completed, &stat), stat)) return false;
        if (!S_ISREG(st.nfs_mode) || st.nfs_size > INT64_MAX) { error(-EINVAL); dispose(); return false; }
        size_ = static_cast<int64_t>(st.nfs_size); source_->length = size_; ++source_->opens;
        source_->error = 0; source_->status = 0;
        return true;
    }
};
}
std::unique_ptr<RemoteReader> makeNfsReader() { return std::make_unique<NfsIO>(); }
void addNfsSource(const std::string& token, std::string server, std::string exportPath,
                  std::string path, int version, int uid, int gid, int port) {
    auto s = std::make_shared<Source>();
    s->server = std::move(server); s->exportPath = std::move(exportPath); s->path = std::move(path);
    s->version = version; s->uid = uid; s->gid = gid; s->port = port;
    std::lock_guard lock(sourcesMutex); sources[token] = std::move(s);
}
void cancelNfsSource(const std::string& token) { if (auto s = lookup(token)) s->cancelled = true; }
void removeNfsSource(const std::string& token) { std::lock_guard lock(sourcesMutex); sources.erase(token); }
int nfsError(const std::string& token) { auto s = lookup(token); return s ? s->error.load() : 0; }
std::string nfsDiagnostics(const std::string& token) {
    auto s = lookup(token); if (!s) return "{}";
    std::ostringstream out;
    out << "{\"version\":" << s->version << ",\"opens\":" << s->opens << ",\"bytes\":" << s->bytes
        << ",\"reads\":" << s->reads << ",\"seeks\":" << s->seeks << ",\"error\":" << s->error
        << ",\"status\":" << s->status << ",\"size\":" << s->length << ",\"maxOffset\":" << s->maxOffset << '}';
    return out.str();
}
}
