#include <jni.h>
#include "nas_jni.h"
#include <ctime>
#include <smb2/smb2.h>
#include <smb2/libsmb2.h>
#include <smb2/libsmb2-raw.h>
#include <nfsc/libnfs.h>
#include <nfsc/libnfs-raw.h>
#include <nfsc/libnfs-raw-nfs.h>
#include <nfsc/libnfs-raw-nfs4.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <functional>
#include <fcntl.h>
#include <memory>
#include <mutex>
#include <poll.h>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

// Fixed libnfs 8.0.0 ABI (vendor 5b1bc602). These two handle helpers are private;
// no context layout or high-level directory cache is accessed.
struct nfs_fh { int len; char* val; };
extern "C" const nfs_fh* nfs_get_rootfh(nfs_context*);
extern "C" void nfs_free_nfsfh(nfsfh*);

namespace {
constexpr uint32_t PAGE_BYTES = 16384;
using Clock = std::chrono::steady_clock;
struct Entry { std::string name; bool directory, regular; int64_t size, modified; };
struct Operation {
    bool done = false; int status = 0;
    std::function<void(void*)> consume;
};
struct Session {
    std::atomic<bool> cancelled{false};
    smb2_context* smb = nullptr; nfs_context* nfs = nullptr;
    smb2fh* smbFile = nullptr; nfsfh* nfsFile = nullptr;
    smb2_file_id smbDir{}; std::vector<char> nfsDir;
    uint64_t cookie = 0; char verifier[8]{}; int version = 0; bool eof = false, smbDirOpen = false;
    Operation op;
    std::vector<Entry> entries;
    std::vector<uint8_t> readBuffer;
    // Pending callback storage outlives disconnect, including timeout/cancellation.
    ~Session() {
        op.consume = nullptr;
        if (smb) smb2_destroy_context(smb);
        if (nfs) {
            rpc_disconnect(nfs_get_rpc_context(nfs), "library session closed");
            // A failed/cancelled bounded read still owns its local file handle. Pending
            // callbacks have drained above; no read can now reference this allocation.
            if (nfsFile) nfs_free_nfsfh(nfsFile);
            nfs_destroy_context(nfs);
        }
    }
};
std::mutex sessionsMutex;
std::unordered_map<jlong, std::shared_ptr<Session>> sessions;
std::atomic<jlong> serial{1};
std::shared_ptr<Session> get(jlong id) {
    std::lock_guard lock(sessionsMutex); auto it = sessions.find(id);
    return it == sessions.end() ? nullptr : it->second;
}
using nas_jni::utf8;
using nas_jni::LocalRef;
void done(Operation& op, int status, void* data) {
    op.status = status; op.done = true;
    if (status >= 0 && op.consume) op.consume(data);
}
void smbDone(smb2_context*, int status, void* data, void* opaque) { done(*static_cast<Operation*>(opaque), status, data); }
void nfsDone(int status, nfs_context*, void* data, void* opaque) { done(*static_cast<Operation*>(opaque), status, data); }
void rpcDone(rpc_context*, int status, void* data, void* opaque) {
    done(*static_cast<Operation*>(opaque), status == RPC_STATUS_SUCCESS ? 0 : -EIO, data);
}
int wait(Session& s, bool queued) {
    if (!queued) return -EIO;
    auto deadline = Clock::now() + std::chrono::seconds(15);
    while (!s.op.done) {
        if (s.cancelled) return -ECANCELED;
        if (Clock::now() >= deadline) return -ETIMEDOUT;
        if (s.smb) {
            size_t count = 0; int timeout = -1;
            auto fds = smb2_get_fds(s.smb, &count, &timeout);
            std::vector<pollfd> all;
            for (size_t i = 0; i < count; ++i) all.push_back({fds[i], short(smb2_which_events(s.smb)), 0});
            int ms = timeout < 0 ? 50 : std::min(50, timeout);
            int rc = poll(all.data(), all.size(), ms);
            if (rc < 0 && errno == EINTR) continue;
            if (rc < 0) return -EIO;
            int result = 0;
            if (!rc && timeout >= 0 && ms == timeout) result = smb2_service_fd(s.smb, -1, 0);
            else {
                bool serviced = false;
                for (auto& fd : all) if (fd.revents) { result = smb2_service_fd(s.smb, fd.fd, fd.revents); serviced = true; break; }
                if (!serviced && !all.empty()) result = smb2_service_fd(s.smb, all[0].fd, 0);
            }
            if (result < 0) return -EIO;
        } else {
            pollfd fd{nfs_get_fd(s.nfs), short(nfs_which_events(s.nfs)), 0};
            if (poll(&fd, 1, 50) < 0) { if (errno == EINTR) continue; return -EIO; }
            if (nfs_service(s.nfs, fd.revents) < 0) return -EIO;
        }
    }
    return s.cancelled ? -ECANCELED : s.op.status;
}
int submit(Session& s, smb2_pdu* pdu) {
    if (!pdu) return -EIO; smb2_queue_pdu(s.smb, pdu); return wait(s, true);
}
int compound(Session& s, nfs_argop4* ops, uint32_t count) {
    COMPOUND4args args{}; args.argarray.argarray_len = count; args.argarray.argarray_val = ops;
    return wait(s, rpc_nfs4_compound_task(nfs_get_rpc_context(s.nfs), rpcDone, &args, &s.op) != nullptr);
}
void putfh(Session& s, nfs_argop4& op) {
    op.argop = OP_PUTFH; auto& fh = op.nfs_argop4_u.opputfh.object;
    fh.nfs_fh4_len = s.nfsDir.size(); fh.nfs_fh4_val = s.nfsDir.data();
}
int resolve(Session& s, const std::string& path) {
    auto root = nfs_get_rootfh(s.nfs);
    if (!root || root->len <= 0) return -EIO;
    s.nfsDir.assign(root->val, root->val + root->len);
    std::istringstream stream(path); std::string name;
    while (std::getline(stream, name, '/')) {
        if (name.empty()) continue;
        if (name == "." || name == "..") return -EINVAL;
        s.op = {};
        if (s.version == 3) {
            LOOKUP3args args{}; args.what.dir.data = {uint32_t(s.nfsDir.size()), s.nfsDir.data()}; args.what.name = name.data();
            s.op.consume = [&](void* data) {
                auto* r = static_cast<LOOKUP3res*>(data);
                if (r->status != NFS3_OK) { s.op.status = -EIO; return; }
                auto& fh = r->LOOKUP3res_u.resok.object.data;
                s.nfsDir.assign(fh.data_val, fh.data_val + fh.data_len);
            };
            int result = wait(s, rpc_nfs3_lookup_task(nfs_get_rpc_context(s.nfs), rpcDone, &args, &s.op) != nullptr);
            if (result) return result;
        } else {
            nfs_argop4 ops[3]{}; putfh(s, ops[0]); ops[1].argop = OP_LOOKUP;
            ops[1].nfs_argop4_u.oplookup.objname = {uint32_t(name.size()), name.data()}; ops[2].argop = OP_GETFH;
            s.op.consume = [&](void* data) {
                auto* r = static_cast<COMPOUND4res*>(data);
                if (r->status != NFS4_OK || r->resarray.resarray_len != 3) { s.op.status = -EIO; return; }
                auto& fh = r->resarray.resarray_val[2].nfs_resop4_u.opgetfh.GETFH4res_u.resok4.object;
                s.nfsDir.assign(fh.nfs_fh4_val, fh.nfs_fh4_val + fh.nfs_fh4_len);
            };
            int result = compound(s, ops, 3); if (result) return result;
        }
    }
    return 0;
}
uint64_t number(const char*& p, size_t& length, int bytes, bool& valid) {
    if (length < size_t(bytes)) { valid = false; return 0; }
    uint64_t result = 0; for (int i = 0; i < bytes; ++i) result = (result << 8) | uint8_t(*p++);
    length -= bytes; return result;
}
int next(Session& s) {
    if (s.eof) return 1;
    s.entries.clear(); s.op = {};
    auto oldCookie = s.cookie;
    if (s.smb) {
        smb2_query_directory_request req{}; req.file_information_class = SMB2_FILE_ID_FULL_DIRECTORY_INFORMATION;
        req.output_buffer_length = PAGE_BYTES; req.name = "*"; memcpy(req.file_id, s.smbDir, SMB2_FD_SIZE);
        s.op.consume = [&](void* data) {
            auto* r = static_cast<smb2_query_directory_reply*>(data);
            if (!r || r->output_buffer_length > PAGE_BYTES || !r->output_buffer_length) { s.op.status = -EIO; return; }
            uint32_t offset = 0;
            do {
                smb2_iovec vec{}; vec.buf = r->output_buffer + offset; vec.len = r->output_buffer_length - offset;
                smb2_fileidfulldirectoryinformation info{};
                if (vec.len < 80 || smb2_decode_fileidfulldirectoryinformation(s.smb, &info, &vec) != 0 || !info.name) { s.op.status = -EIO; break; }
                bool link = info.file_attributes & SMB2_FILE_ATTRIBUTE_REPARSE_POINT;
                s.entries.push_back({info.name, !link && bool(info.file_attributes & SMB2_FILE_ATTRIBUTE_DIRECTORY),
                    !link && !(info.file_attributes & SMB2_FILE_ATTRIBUTE_DIRECTORY), int64_t(info.end_of_file),
                    int64_t(info.last_write_time.tv_sec) * 1000 + info.last_write_time.tv_usec / 1000});
                free(const_cast<char*>(info.name));
                if (!info.next_entry_offset) break;
                if (info.next_entry_offset < 80 || info.next_entry_offset >= vec.len) { s.op.status = -EIO; break; }
                offset += info.next_entry_offset;
            } while (true);
        };
        int result = submit(s, smb2_cmd_query_directory_async(s.smb, &req, smbDone, &s.op));
        if (uint32_t(s.op.status) == 0x80000006) { s.eof = true; return 1; } // STATUS_NO_MORE_FILES
        return result == 0 ? 0 : -EIO;
    }
    if (s.version == 3) {
        READDIRPLUS3args args{}; args.dir.data = {uint32_t(s.nfsDir.size()), s.nfsDir.data()}; args.cookie = s.cookie;
        memcpy(args.cookieverf, s.verifier, 8); args.dircount = PAGE_BYTES / 2; args.maxcount = PAGE_BYTES;
        s.op.consume = [&](void* data) {
            auto* r = static_cast<READDIRPLUS3res*>(data);
            if (r->status != NFS3_OK) { s.op.status = -EIO; return; }
            auto& reply = r->READDIRPLUS3res_u.resok;
            memcpy(s.verifier, reply.cookieverf, 8); s.eof = reply.reply.eof;
            for (auto e = reply.reply.entries; e; e = e->nextentry) {
                s.cookie = e->cookie;
                if (!e->name_attributes.attributes_follow) { s.op.status = -EIO; return; }
                auto& a = e->name_attributes.post_op_attr_u.attributes;
                s.entries.push_back({e->name, a.type == NF3DIR, a.type == NF3REG, int64_t(a.size), int64_t(a.mtime.seconds) * 1000 + a.mtime.nseconds / 1000000});
            }
        };
        int result = wait(s, rpc_nfs3_readdirplus_task(nfs_get_rpc_context(s.nfs), rpcDone, &args, &s.op) != nullptr);
        if (result) return -EIO;
    } else {
        nfs_argop4 ops[2]{}; putfh(s, ops[0]); ops[1].argop = OP_READDIR;
        auto& args = ops[1].nfs_argop4_u.opreaddir; args.cookie = s.cookie; memcpy(args.cookieverf, s.verifier, 8);
        args.dircount = PAGE_BYTES / 2; args.maxcount = PAGE_BYTES;
        uint32_t mask[2] = {(1u << FATTR4_TYPE) | (1u << FATTR4_SIZE), 1u << (FATTR4_TIME_MODIFY - 32)};
        args.attr_request = {2, mask};
        s.op.consume = [&](void* data) {
            auto* r = static_cast<COMPOUND4res*>(data);
            if (r->status != NFS4_OK || r->resarray.resarray_len != 2) { s.op.status = -EIO; return; }
            auto& reply = r->resarray.resarray_val[1].nfs_resop4_u.opreaddir.READDIR4res_u.resok4;
            memcpy(s.verifier, reply.cookieverf, 8); s.eof = reply.reply.eof;
            for (auto e = reply.reply.entries; e; e = e->nextentry) {
                s.cookie = e->cookie;
                auto& a = e->attrs; const char* p = a.attr_vals.attrlist4_val; size_t len = a.attr_vals.attrlist4_len;
                bool valid = true; int type = 0; int64_t size = -1, modified = -1;
                for (uint32_t word = 0; word < a.attrmask.bitmap4_len; ++word) for (int bit = 0; bit < 32; ++bit) {
                    if (!(a.attrmask.bitmap4_val[word] & (1u << bit))) continue;
                    switch (word * 32 + bit) {
                        case FATTR4_TYPE: type = number(p, len, 4, valid); break;
                        case FATTR4_SIZE: size = number(p, len, 8, valid); break;
                        case FATTR4_TIME_MODIFY: { auto sec = number(p, len, 8, valid); auto ns = number(p, len, 4, valid); modified = sec * 1000 + ns / 1000000; break; }
                        default: valid = false;
                    }
                }
                if (!valid || !type || len) { s.op.status = -EIO; return; }
                s.entries.push_back({std::string(e->name.utf8string_val, e->name.utf8string_len), type == NF4DIR, type == NF4REG, size, modified});
            }
        };
        if (compound(s, ops, 2)) return -EIO;
    }
    if (!s.eof && (s.entries.empty() || oldCookie == s.cookie)) return -EIO;
    return s.eof ? 1 : 0;
}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_stellar_player_nativeio_NativeLibraryIo_create(JNIEnv*, jobject) {
    auto id = serial++; std::lock_guard lock(sessionsMutex); sessions[id] = std::make_shared<Session>(); return id;
}
extern "C" JNIEXPORT void JNICALL Java_com_stellar_player_nativeio_NativeLibraryIo_cancel(JNIEnv*, jobject, jlong id) { if (auto s = get(id)) s->cancelled = true; }
extern "C" JNIEXPORT void JNICALL Java_com_stellar_player_nativeio_NativeLibraryIo_destroy(JNIEnv*, jobject, jlong id) {
    std::shared_ptr<Session> value;
    { std::lock_guard lock(sessionsMutex); auto it = sessions.find(id); if (it != sessions.end()) { value = it->second; sessions.erase(it); } }
}
extern "C" JNIEXPORT jint JNICALL Java_com_stellar_player_nativeio_NativeLibraryIo_connect(JNIEnv* env, jobject, jlong id,
    jstring host, jstring share, jstring user, jstring password, jstring domain, jint version, jint port, jint mountPort, jint uid, jint gid) {
    auto s = get(id); if (!s || s->cancelled) return -ECANCELED;
    std::string h, sh, u, pw, d;
    if (!utf8(env, host, h) || !utf8(env, share, sh) || !utf8(env, user, u) ||
        !utf8(env, password, pw) || !utf8(env, domain, d)) {
        std::fill(pw.begin(), pw.end(), '\0');
        return -ENOMEM;
    }
    s->version = version;
    if (!version) {
        s->smb = smb2_init_context(); if (!s->smb) return -EIO;
        smb2_set_user(s->smb, u.c_str()); smb2_set_password(s->smb, pw.c_str()); smb2_set_domain(s->smb, d.c_str());
        std::fill(pw.begin(), pw.end(), '\0');
        smb2_set_version(s->smb, SMB2_VERSION_ANY); smb2_set_security_mode(s->smb, SMB2_NEGOTIATE_SIGNING_ENABLED);
        smb2_set_sign(s->smb, !u.empty()); smb2_set_timeout(s->smb, 10);
        return wait(*s, smb2_connect_share_async(s->smb, h.c_str(), sh.c_str(), u.c_str(), smbDone, &s->op) == 0);
    }
    if (version != 3 && version != 4) return -EINVAL;
    s->nfs = nfs_init_context(); if (!s->nfs) return -EIO;
    nfs_set_autoreconnect(s->nfs, 0); nfs_set_retrans(s->nfs, 0); nfs_set_timeout(s->nfs, 10000);
    nfs_set_auto_traverse_mounts(s->nfs, 0); nfs_set_nfsport(s->nfs, port);
    if (mountPort > 0) nfs_set_mountport(s->nfs, mountPort);
    if (uid >= 0) nfs_set_uid(s->nfs, uid); if (gid >= 0) nfs_set_gid(s->nfs, gid);
    nfs_set_auxiliary_gids(s->nfs, 0, nullptr); if (nfs_set_version(s->nfs, version)) return -EINVAL;
    return wait(*s, nfs_mount_async(s->nfs, h.c_str(), sh.c_str(), nfsDone, &s->op) == 0);
}
extern "C" JNIEXPORT jint JNICALL Java_com_stellar_player_nativeio_NativeLibraryIo_open(JNIEnv* env, jobject, jlong id,
    jstring path, jboolean file) {
    auto s = get(id); if (!s || s->cancelled) return -ECANCELED;
    std::string p;
    if (!utf8(env, path, p)) return -ENOMEM;
    s->cookie = 0; s->eof = false; memset(s->verifier, 0, sizeof(s->verifier));
    s->op = {};
    if (s->smb) {
        s->op = {};
        if (file) {
            s->op.consume = [&](void* data) { s->smbFile = static_cast<smb2fh*>(data); };
            return wait(*s, smb2_open_async(s->smb, p.c_str(), O_RDONLY, smbDone, &s->op) == 0);
        }
        smb2_create_request req{}; req.impersonation_level = SMB2_IMPERSONATION_IMPERSONATION;
        req.desired_access = SMB2_FILE_LIST_DIRECTORY | SMB2_FILE_READ_ATTRIBUTES; req.file_attributes = SMB2_FILE_ATTRIBUTE_DIRECTORY;
        req.share_access = SMB2_FILE_SHARE_READ | SMB2_FILE_SHARE_WRITE | SMB2_FILE_SHARE_DELETE;
        req.create_disposition = SMB2_FILE_OPEN; req.create_options = SMB2_FILE_DIRECTORY_FILE; req.name = p.c_str();
        s->op.consume = [&](void* data) {
            memcpy(s->smbDir, static_cast<smb2_create_reply*>(data)->file_id, SMB2_FD_SIZE); s->smbDirOpen = true;
        };
        return submit(*s, smb2_cmd_create_async(s->smb, &req, smbDone, &s->op));
    }
    if (!s->nfs) return -EIO;
    if (file) {
        s->op = {}; s->op.consume = [&](void* data) { s->nfsFile = static_cast<nfsfh*>(data); };
        return wait(*s, nfs_open_async(s->nfs, ("/" + p).c_str(), O_RDONLY, nfsDone, &s->op) == 0);
    }
    return resolve(*s, p);
}
extern "C" JNIEXPORT jint JNICALL Java_com_stellar_player_nativeio_NativeLibraryIo_finish(JNIEnv*, jobject, jlong id) {
    auto s = get(id); if (!s || s->cancelled) return -ECANCELED;
    // read() already closes its file. Raw SMB directory handles need an explicit CLOSE;
    // NFS enumeration uses only a filehandle and cookie, with no open server state.
    if (s->smbDirOpen) {
        s->op = {};
        smb2_close_request req{}; memcpy(req.file_id, s->smbDir, SMB2_FD_SIZE);
        int result = submit(*s, smb2_cmd_close_async(s->smb, &req, smbDone, &s->op));
        if (result) return result;
        s->smbDirOpen = false;
    }
    s->op = {}; s->entries.clear(); s->nfsDir.clear();
    std::vector<uint8_t>().swap(s->readBuffer);
    return 0;
}
extern "C" JNIEXPORT jint JNICALL Java_com_stellar_player_nativeio_NativeLibraryIo_page(JNIEnv* env, jobject, jlong id, jobject list) {
    auto s = get(id); if (!s || s->cancelled) return -ECANCELED;
    int result = next(*s); if (result < 0) return result;
    LocalRef cls(env, env->FindClass("com/stellar/player/nativeio/NativeLibraryIo$Entry"));
    if (!cls) return -EIO;
    auto ctor = env->GetMethodID(cls.get(), "<init>", "([BZZJJ)V");
    if (!ctor) return -EIO;
    LocalRef listCls(env, env->GetObjectClass(list));
    if (!listCls) return -EIO;
    auto add = env->GetMethodID(listCls.get(), "add", "(Ljava/lang/Object;)Z");
    if (!add) return -EIO;
    for (auto& e : s->entries) {
        LocalRef bytes(env, env->NewByteArray(e.name.size()));
        if (!bytes) return -ENOMEM;
        env->SetByteArrayRegion(bytes.get(), 0, e.name.size(), reinterpret_cast<const jbyte*>(e.name.data()));
        if (env->ExceptionCheck()) return -EIO;
        LocalRef entry(env, env->NewObject(cls.get(), ctor, bytes.get(), jboolean(e.directory), jboolean(e.regular), jlong(e.size), jlong(e.modified)));
        if (!entry || env->ExceptionCheck()) return -EIO;
        env->CallBooleanMethod(list, add, entry.get());
        if (env->ExceptionCheck()) return -EIO;
    }
    s->entries.clear(); return result;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_stellar_player_nativeio_NativeLibraryIo_read(JNIEnv* env, jobject, jlong id, jint limit) {
    auto s = get(id); if (!s || limit < 1 || limit > 32 * 1024 * 1024) return nullptr;
    // Keep async read targets alive until cancellation disconnect has drained callbacks.
    auto& bytes = s->readBuffer; bytes.resize(size_t(limit) + 1); size_t offset = 0;
    while (offset < bytes.size()) {
        s->op = {}; auto count = std::min<size_t>(65536, bytes.size() - offset);
        int queued = s->smb ? smb2_pread_async(s->smb, s->smbFile, bytes.data() + offset, count, offset, smbDone, &s->op)
            : nfs_pread_async(s->nfs, s->nfsFile, bytes.data() + offset, count, offset, nfsDone, &s->op);
        int result = wait(*s, queued == 0);
        if (result < 0) { s->op.consume = nullptr; return nullptr; }
        if (!result) break; offset += result;
    }
    // Return the one-byte overflow sentinel too, so Kotlin can distinguish a
    // content budget from transport failure without retrying the read.
    s->op = {};
    int queued = s->smb ? smb2_close_async(s->smb, s->smbFile, smbDone, &s->op)
        : nfs_close_async(s->nfs, s->nfsFile, nfsDone, &s->op);
    // Once CLOSE is queued, libnfs owns the allocation even if its response times out.
    if (!queued) { s->smbFile = nullptr; s->nfsFile = nullptr; }
    int closeResult = wait(*s, queued == 0);
    if (closeResult < 0) return nullptr;
    s->smbFile = nullptr; s->nfsFile = nullptr;
    auto result = env->NewByteArray(offset);
    if (!result) return nullptr;
    env->SetByteArrayRegion(result, 0, offset, reinterpret_cast<const jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) { env->DeleteLocalRef(result); return nullptr; }
    return result;
}
