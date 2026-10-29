#include <jni.h>
#include "nas_jni.h"
#include <ctime>
#include <smb2/smb2.h>
#include <smb2/libsmb2.h>
#include <nfsc/libnfs.h>
#include <nfsc/libnfs-raw.h>
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <memory>
#include <mutex>
#include <poll.h>
#include <string>
#include <unordered_map>
#include <vector>
#include <cstring>

namespace {
using Clock = std::chrono::steady_clock;
struct Ticket { std::atomic<bool> cancelled{false}; };
std::mutex ticketsMutex;
std::unordered_map<jlong, std::shared_ptr<Ticket>> tickets;
std::atomic<jlong> nextTicket{1};
std::shared_ptr<Ticket> lookup(jlong id) {
    std::lock_guard lock(ticketsMutex);
    auto it = tickets.find(id); return it == tickets.end() ? nullptr : it->second;
}
using nas_jni::utf8;
using nas_jni::LocalRef;
struct Operation { bool done = false; int status = 0; void* data = nullptr; };
bool appendEntry(JNIEnv* env, jobject entries, const char* name, bool directory, bool regular, uint64_t size) {
    LocalRef klass(env, env->FindClass("com/stellar/player/nativeio/NativeDirectoryProbe$Entry"));
    if (!klass) return false;
    auto ctor = env->GetMethodID(klass.get(), "<init>", "([BZZJ)V");
    if (!ctor) return false;
    LocalRef bytes(env, env->NewByteArray(std::strlen(name)));
    if (!bytes) return false;
    env->SetByteArrayRegion(bytes.get(), 0, std::strlen(name), reinterpret_cast<const jbyte*>(name));
    if (env->ExceptionCheck()) return false;
    LocalRef entry(env, env->NewObject(klass.get(), ctor, bytes.get(), static_cast<jboolean>(directory), static_cast<jboolean>(regular), static_cast<jlong>(size)));
    if (!entry || env->ExceptionCheck()) return false;
    LocalRef listClass(env, env->GetObjectClass(entries));
    if (!listClass) return false;
    auto add = env->GetMethodID(listClass.get(), "add", "(Ljava/lang/Object;)Z");
    if (!add) return false;
    env->CallBooleanMethod(entries, add, entry.get());
    return !env->ExceptionCheck();
}
void smbDone(smb2_context*, int status, void* data, void* opaque) {
    auto& op = *static_cast<Operation*>(opaque); op.status = status; op.data = data; op.done = true;
}
void nfsDone(int status, nfs_context*, void* data, void* opaque) {
    auto& op = *static_cast<Operation*>(opaque); op.status = status; op.data = data; op.done = true;
}
int category(int status, uint32_t nt = 0) {
    if (nt == 0xc000006d || nt == 0xc000006a || nt == 0xc0000064 || nt == 0xc0000072 || nt == 0xc0000234) return 1;
    if (nt == 0xc00000cc || nt == 0xc0000034 || nt == 0xc000003a || status == -ENOENT || status == -ENOTDIR) return 2;
    if (nt == 0xc0000022 || status == -EACCES || status == -EPERM) return 3;
    return status == -ETIMEDOUT ? 4 : 6;
}
int awaitSmb(smb2_context* ctx, int queued, Operation& op, const std::shared_ptr<Ticket>& ticket, Clock::time_point deadline) {
    if (queued < 0) return category(queued, smb2_get_nterror(ctx));
    while (!op.done) {
        if (ticket->cancelled) return 5;
        if (Clock::now() >= deadline) return 4;
        size_t count = 0; int connectTimeout = -1;
        auto fds = smb2_get_fds(ctx, &count, &connectTimeout);
        std::vector<pollfd> all;
        for (size_t i = 0; i < count; ++i) all.push_back({fds[i], static_cast<short>(smb2_which_events(ctx)), 0});
        int wait = connectTimeout < 0 ? 50 : std::min(50, connectTimeout);
        int rc = poll(all.data(), all.size(), wait);
        if (rc < 0 && errno == EINTR) continue;
        if (rc < 0) return 6;
        int result = 0;
        if (!rc && connectTimeout >= 0 && wait == connectTimeout) result = smb2_service_fd(ctx, -1, 0);
        else {
            bool serviced = false;
            for (const auto& fd : all) if (fd.revents) {
                result = smb2_service_fd(ctx, fd.fd, fd.revents); serviced = true; break;
            }
            if (!serviced && !all.empty()) result = smb2_service_fd(ctx, all[0].fd, 0);
        }
        if (result < 0) return category(op.done ? op.status : -EIO, smb2_get_nterror(ctx));
    }
    return ticket->cancelled ? 5 : op.status < 0 ? category(op.status, smb2_get_nterror(ctx)) : 0;
}
int awaitNfs(nfs_context* ctx, int queued, Operation& op, const std::shared_ptr<Ticket>& ticket, Clock::time_point deadline) {
    if (queued < 0) return category(queued);
    while (!op.done) {
        if (ticket->cancelled) return 5;
        if (Clock::now() >= deadline) return 4;
        pollfd fd{nfs_get_fd(ctx), static_cast<short>(nfs_which_events(ctx)), 0};
        int rc = poll(&fd, 1, 50);
        if (rc < 0 && errno == EINTR) continue;
        if (rc < 0) return 6;
        if (nfs_service(ctx, fd.revents) < 0) return category(op.done ? op.status : -EIO);
    }
    return ticket->cancelled ? 5 : op.status < 0 ? category(op.status) : 0;
}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_stellar_player_nativeio_NativeDirectoryProbe_create(JNIEnv*, jobject) {
    const auto id = nextTicket++; std::lock_guard lock(ticketsMutex);
    tickets[id] = std::make_shared<Ticket>(); return id;
}
extern "C" JNIEXPORT void JNICALL Java_com_stellar_player_nativeio_NativeDirectoryProbe_cancel(JNIEnv*, jobject, jlong id) {
    if (auto ticket = lookup(id)) ticket->cancelled = true;
}
extern "C" JNIEXPORT void JNICALL Java_com_stellar_player_nativeio_NativeDirectoryProbe_destroy(JNIEnv*, jobject, jlong id) {
    std::lock_guard lock(ticketsMutex); tickets.erase(id);
}
extern "C" JNIEXPORT jint JNICALL Java_com_stellar_player_nativeio_NativeDirectoryProbe_smb(
    JNIEnv* env, jobject, jlong id, jstring host, jstring share, jstring path, jstring user, jstring password, jstring domain, jobject entries) {
    auto ticket = lookup(id); if (!ticket || ticket->cancelled) return 5;
    std::string h, s, p, u, pw, d;
    if (!utf8(env, host, h) || !utf8(env, share, s) || !utf8(env, path, p) ||
        !utf8(env, user, u) || !utf8(env, password, pw) || !utf8(env, domain, d)) {
        std::fill(pw.begin(), pw.end(), '\0');
        return 6;
    }
    // Operations must outlive context destruction, which may complete callbacks.
    Operation connect, open;
    std::unique_ptr<smb2_context, decltype(&smb2_destroy_context)> ctx(smb2_init_context(), smb2_destroy_context);
    if (!ctx) return 6;
    smb2_set_user(ctx.get(), u.c_str()); smb2_set_password(ctx.get(), pw.c_str()); smb2_set_domain(ctx.get(), d.c_str());
    std::fill(pw.begin(), pw.end(), '\0');
    smb2_set_version(ctx.get(), SMB2_VERSION_ANY); smb2_set_security_mode(ctx.get(), SMB2_NEGOTIATE_SIGNING_ENABLED);
    smb2_set_sign(ctx.get(), !u.empty()); smb2_set_timeout(ctx.get(), 10);
    const auto deadline = Clock::now() + std::chrono::seconds(15);
    int result = awaitSmb(ctx.get(), smb2_connect_share_async(ctx.get(), h.c_str(), s.c_str(), u.c_str(), smbDone, &connect), connect, ticket, deadline);
    if (result) return result;
    result = awaitSmb(ctx.get(), smb2_opendir_async(ctx.get(), p.c_str(), smbDone, &open), open, ticket, deadline);
    if (open.done && open.status >= 0 && open.data) {
        auto dir = static_cast<smb2dir*>(open.data);
        if (!result && entries) {
            size_t count = 0;
            while (auto entry = smb2_readdir(ctx.get(), dir)) {
                if (ticket->cancelled) { result = 5; break; }
                if (Clock::now() >= deadline) { result = 4; break; }
                if (++count > 10000) { result = 7; break; }
                if (!appendEntry(env, entries, entry->name, entry->st.smb2_type == SMB2_TYPE_DIRECTORY,
                    entry->st.smb2_type == SMB2_TYPE_FILE, entry->st.smb2_size)) { result = 6; break; }
            }
        }
        smb2_closedir(ctx.get(), dir);
    }
    return result;
}
extern "C" JNIEXPORT jint JNICALL Java_com_stellar_player_nativeio_NativeDirectoryProbe_nfs(
    JNIEnv* env, jobject, jlong id, jstring host, jstring exportPath, jstring path, jint version, jint port, jint uid, jint gid, jobject entries) {
    auto ticket = lookup(id); if (!ticket || ticket->cancelled) return 5;
    std::string h, p, directory;
    if (!utf8(env, host, h) || !utf8(env, exportPath, p) || !utf8(env, path, directory)) return 6;
    Operation mount, open;
    auto dispose = [](nfs_context* ctx) { rpc_disconnect(nfs_get_rpc_context(ctx), "probe finished"); nfs_destroy_context(ctx); };
    std::unique_ptr<nfs_context, decltype(dispose)> ctx(nfs_init_context(), dispose);
    if (!ctx) return 6;
    nfs_set_autoreconnect(ctx.get(), 0); nfs_set_retrans(ctx.get(), 0); nfs_set_timeout(ctx.get(), 10000);
    nfs_set_auto_traverse_mounts(ctx.get(), 0); nfs_set_nfsport(ctx.get(), port);
    if (uid >= 0) nfs_set_uid(ctx.get(), uid);
    if (gid >= 0) nfs_set_gid(ctx.get(), gid);
    nfs_set_auxiliary_gids(ctx.get(), 0, nullptr);
    if (nfs_set_version(ctx.get(), version) != 0) return 6;
    const auto deadline = Clock::now() + std::chrono::seconds(15);
    int result = awaitNfs(ctx.get(), nfs_mount_async(ctx.get(), h.c_str(), p.c_str(), nfsDone, &mount), mount, ticket, deadline);
    if (result) return result;
    result = awaitNfs(ctx.get(), nfs_opendir_async(ctx.get(), directory.c_str(), nfsDone, &open), open, ticket, deadline);
    if (open.done && open.status >= 0 && open.data) {
        auto dir = static_cast<nfsdir*>(open.data);
        if (!result && entries) {
            size_t count = 0;
            while (auto entry = nfs_readdir(ctx.get(), dir)) {
                if (ticket->cancelled) { result = 5; break; }
                if (Clock::now() >= deadline) { result = 4; break; }
                if (++count > 10000) { result = 7; break; }
                if (!appendEntry(env, entries, entry->name, entry->type == 2, entry->type == 1, entry->size)) { result = 6; break; }
            }
        }
        nfs_closedir(ctx.get(), dir);
    }
    return result;
}
