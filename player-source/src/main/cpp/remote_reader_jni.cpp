#include "remote_reader.h"
#include "smb_reader.h"
#include "nfs_reader.h"
#include <jni.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cstdio>
#include <mutex>
#include <unordered_map>

namespace {
struct File {
    const bool smb;
    const std::string token;
    const std::unique_ptr<stellar::RemoteReader> reader;
    std::array<uint8_t, 64 * 1024> buffer{};
    File(bool isSmb, jlong id) : smb(isSmb), token("source-file:" + std::to_string(id)),
        reader(smb ? stellar::makeSmbReader() : stellar::makeNfsReader()) {}
    ~File() { if (smb) stellar::removeSmbSource(token); else stellar::removeNfsSource(token); }
};
std::mutex filesMutex;
std::unordered_map<jlong, std::shared_ptr<File>> files;
std::atomic<jlong> nextId{1};
std::shared_ptr<File> get(jlong id) {
    std::lock_guard lock(filesMutex);
    auto it = files.find(id);
    return it == files.end() ? nullptr : it->second;
}
std::string text(JNIEnv* env, jbyteArray value) {
    std::string result(env->GetArrayLength(value), '\0');
    env->GetByteArrayRegion(value, 0, result.size(), reinterpret_cast<jbyte*>(result.data()));
    return result;
}
void add(jlong id, std::shared_ptr<File> file) {
    std::lock_guard lock(filesMutex); files.emplace(id, std::move(file));
}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_stellar_player_source_NativeRemoteFiles_smb(
    JNIEnv* env, jobject, jbyteArray server, jbyteArray share, jbyteArray path, jbyteArray user,
    jbyteArray password, jbyteArray domain, jboolean seal) {
    const auto id = nextId++;
    auto file = std::make_shared<File>(true, id);
    stellar::addSmbSource(file->token, text(env, server), text(env, share), text(env, path),
        text(env, user), text(env, password), text(env, domain), seal);
    add(id, std::move(file)); return id;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_stellar_player_source_NativeRemoteFiles_nfs(
    JNIEnv* env, jobject, jbyteArray server, jbyteArray root, jbyteArray path, jint version, jint uid, jint gid, jint port) {
    const auto id = nextId++;
    auto file = std::make_shared<File>(false, id);
    stellar::addNfsSource(file->token, text(env, server), text(env, root), text(env, path), version, uid, gid, port);
    add(id, std::move(file)); return id;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_stellar_player_source_NativeRemoteFiles_open(JNIEnv*, jobject, jlong id) {
    auto file = get(id);
    return file && file->reader->open(file->token) && file->reader->isSeekable() ? file->reader->size() : -1;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stellar_player_source_NativeRemoteFiles_seek(JNIEnv*, jobject, jlong id, jlong offset) {
    auto file = get(id); return file && file->reader->seek(offset, SEEK_SET);
}
extern "C" JNIEXPORT jint JNICALL Java_com_stellar_player_source_NativeRemoteFiles_read(
    JNIEnv* env, jobject, jlong id, jbyteArray bytes, jint offset, jint count) {
    auto file = get(id);
    if (!file || offset < 0 || count < 0 || offset > env->GetArrayLength(bytes) - count) return -1;
    const auto size = file->reader->read(file->buffer.data(), std::min<size_t>(count, file->buffer.size()));
    if (size > 0) env->SetByteArrayRegion(bytes, offset, size, reinterpret_cast<const jbyte*>(file->buffer.data()));
    return static_cast<jint>(size);
}
extern "C" JNIEXPORT void JNICALL Java_com_stellar_player_source_NativeRemoteFiles_cancel(JNIEnv*, jobject, jlong id) {
    // Keep the reader alive through abort, including racing owner-thread close.
    if (auto file = get(id)) file->reader->abort();
}
extern "C" JNIEXPORT void JNICALL Java_com_stellar_player_source_NativeRemoteFiles_close(JNIEnv*, jobject, jlong id) {
    std::shared_ptr<File> file;
    { std::lock_guard lock(filesMutex); auto it = files.find(id);
      if (it != files.end()) { file = std::move(it->second); files.erase(it); } }
    // Protocol destruction runs outside the registry mutex on the IO owner.
}
