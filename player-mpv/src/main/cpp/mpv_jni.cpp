#include <jni.h>
#include <stellar_disc.h>
#include <mpv/client.h>
#include "remote_stream.h"
#include "http_cache_stream.h"
#include "smb_reader.h"
#include "nfs_reader.h"
#include "callback_reader.h"
#include "../../../../native-common/equalizer.h"
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <android/log.h>
#include <string>
#include <vector>

namespace {
struct Player { mpv_handle* mpv = nullptr; jobject surface = nullptr; std::string smbToken, nfsToken, httpToken, streamToken; };
Player* player(jlong value) { return reinterpret_cast<Player*>(value); }
std::string text(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* bytes = env->GetStringUTFChars(value, nullptr);
    std::string result(bytes); env->ReleaseStringUTFChars(value, bytes); return result;
}
std::string quote(const char* value) {
    std::string out = "\"";
    for (const unsigned char* p = reinterpret_cast<const unsigned char*>(value ? value : ""); *p; ++p) {
        if (*p == '"' || *p == '\\') { out += '\\'; out += *p; }
        else if (*p < 32) { char escaped[7]; snprintf(escaped, sizeof(escaped), "\\u%04x", *p); out += escaped; }
        else out += *p;
    }
    return out + '"';
}
std::string json(const mpv_node& node) {
    switch (node.format) {
    case MPV_FORMAT_STRING: return quote(node.u.string);
    case MPV_FORMAT_FLAG: return node.u.flag ? "true" : "false";
    case MPV_FORMAT_INT64: return std::to_string(node.u.int64);
    case MPV_FORMAT_DOUBLE: return std::isfinite(node.u.double_) ? std::to_string(node.u.double_) : "null";
    case MPV_FORMAT_NODE_ARRAY:
    case MPV_FORMAT_NODE_MAP: {
        bool map = node.format == MPV_FORMAT_NODE_MAP;
        std::string out = map ? "{" : "[";
        for (int i = 0; i < node.u.list->num; ++i) {
            if (i) out += ',';
            if (map) out += quote(node.u.list->keys[i]) + ':';
            out += json(node.u.list->values[i]);
        }
        return out + (map ? '}' : ']');
    }
    default: return "null";
    }
}
std::string property(Player* p, const char* name) {
    mpv_node value{};
    if (mpv_get_property(p->mpv, name, MPV_FORMAT_NODE, &value) < 0) return "null";
    auto out = json(value); mpv_free_node_contents(&value); return out;
}
bool attach(JNIEnv* env, Player* p, jobject surface) {
    // A synchronous VO change retires rendering before dropping its Java Surface reference.
    if (mpv_set_property_string(p->mpv, "vo", "null") < 0) return false;
    mpv_set_property_string(p->mpv, "force-window", "no");
    int64_t wid = 0;
    mpv_set_property(p->mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (p->surface) env->DeleteGlobalRef(p->surface);
    p->surface = surface ? env->NewGlobalRef(surface) : nullptr;
    if (!p->surface) return true;
    wid = reinterpret_cast<intptr_t>(p->surface);
    if (mpv_set_property(p->mpv, "wid", MPV_FORMAT_INT64, &wid) < 0) return false;
    mpv_set_property_string(p->mpv, "force-window", "yes");
    return mpv_set_property_string(p->mpv, "vo", "gpu-next") >= 0;
}
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    // mpv uses FFmpeg's process-wide Java VM for Android's Surface and MediaCodec APIs.
    void* library = dlopen("libavcodec.so", RTLD_NOW);
    if (!library) return JNI_ERR;
    auto setVm = reinterpret_cast<int (*)(void*, void*)>(dlsym(library, "av_jni_set_java_vm"));
    int result = setVm ? setVm(vm, nullptr) : -1;
    dlclose(library);
    return result >= 0 ? JNI_VERSION_1_6 : JNI_ERR;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_stellar_player_mpv_MpvNative_create(
    JNIEnv* env, jobject, jobject surface, jobjectArray options) {
    auto* p = new Player{mpv_create(), nullptr, {}, {}, {}, {}};
    if (!p->mpv) { delete p; return 0; }
    for (int i = 0; i + 1 < env->GetArrayLength(options); i += 2) {
        auto key = static_cast<jstring>(env->GetObjectArrayElement(options, i));
        auto val = static_cast<jstring>(env->GetObjectArrayElement(options, i + 1));
        int result = mpv_set_option_string(p->mpv, text(env, key).c_str(), text(env, val).c_str());
        env->DeleteLocalRef(key); env->DeleteLocalRef(val);
        if (result < 0) { __android_log_print(ANDROID_LOG_ERROR, "StellarMPV", "Option index %d rejected: %d", i / 2, result); mpv_terminate_destroy(p->mpv); delete p; return 0; }
    }
    if (mpv_initialize(p->mpv) < 0 || !registerRemoteStreams(p->mpv)) { mpv_terminate_destroy(p->mpv); delete p; return 0; }
    // Consume errors internally only; never emit text (URLs/credentials) to logcat.
    mpv_request_log_messages(p->mpv, "error");
    mpv_observe_property(p->mpv, 1, "track-list", MPV_FORMAT_NONE);
    mpv_observe_property(p->mpv, 2, "video-params", MPV_FORMAT_NONE);
    mpv_observe_property(p->mpv, 3, "current-vo", MPV_FORMAT_NONE);
    if (surface && !attach(env, p, surface)) {
        mpv_terminate_destroy(p->mpv); if (p->surface) env->DeleteGlobalRef(p->surface); delete p; return 0;
    }
    return reinterpret_cast<jlong>(p);
}
extern "C" JNIEXPORT void JNICALL Java_com_stellar_player_mpv_MpvNative_destroy(JNIEnv* env, jobject, jlong handle) {
    auto* p = player(handle); if (!p) return;
    stellar::cancelSmbSource(p->smbToken);
    stellar::cancelNfsSource(p->nfsToken);
    stellar::cancelCallbackSource(p->streamToken);
    cancelHttpCache(p->httpToken);
    mpv_terminate_destroy(p->mpv);
    stellar::removeSmbSource(p->smbToken);
    stellar::removeNfsSource(p->nfsToken);
    stellar::removeCallbackSource(p->streamToken);
    removeHttpCache(p->httpToken);
    if (p->surface) env->DeleteGlobalRef(p->surface);
    delete p;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stellar_player_mpv_MpvNative_surface(JNIEnv* env, jobject, jlong handle, jobject surface) {
    return attach(env, player(handle), surface);
}
extern "C" JNIEXPORT jint JNICALL Java_com_stellar_player_mpv_MpvNative_command(JNIEnv* env, jobject, jlong handle, jobjectArray args) {
    std::vector<std::string> strings;
    for (int i = 0; i < env->GetArrayLength(args); ++i) {
        auto arg = static_cast<jstring>(env->GetObjectArrayElement(args, i));
        strings.push_back(text(env, arg)); env->DeleteLocalRef(arg);
    }
    std::vector<const char*> pointers;
    for (auto& value : strings) pointers.push_back(value.c_str());
    pointers.push_back(nullptr);
    return mpv_command(player(handle)->mpv, pointers.data());
}
extern "C" JNIEXPORT jint JNICALL Java_com_stellar_player_mpv_MpvNative_addSubtitle(
    JNIEnv* env, jobject, jlong handle, jlong request, jstring path, jstring label) {
    const auto file = text(env, path), title = text(env, label);
    const char* args[] = {"sub-add", file.c_str(), "auto", title.c_str(), nullptr};
    return mpv_command_async(player(handle)->mpv, request, args);
}
extern "C" JNIEXPORT void JNICALL Java_com_stellar_player_mpv_MpvNative_abortCommand(
    JNIEnv*, jobject, jlong handle, jlong request) {
    mpv_abort_async_command(player(handle)->mpv, request);
}
extern "C" JNIEXPORT jint JNICALL Java_com_stellar_player_mpv_MpvNative_set(JNIEnv* env, jobject, jlong handle, jstring name, jstring value) {
    return mpv_set_property_string(player(handle)->mpv, text(env, name).c_str(), text(env, value).c_str());
}
extern "C" JNIEXPORT jint JNICALL Java_com_stellar_player_mpv_MpvNative_headers(JNIEnv* env, jobject, jlong handle, jobjectArray values) {
    std::vector<std::string> strings;
    for (int i = 0; i < env->GetArrayLength(values); ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        strings.push_back(text(env, value)); env->DeleteLocalRef(value);
    }
    std::vector<mpv_node> nodes(strings.size());
    for (size_t i = 0; i < strings.size(); ++i) { nodes[i].format = MPV_FORMAT_STRING; nodes[i].u.string = strings[i].data(); }
    mpv_node_list list{static_cast<int>(nodes.size()), nodes.data(), nullptr};
    mpv_node node{}; node.format = MPV_FORMAT_NODE_ARRAY; node.u.list = &list;
    return mpv_set_property(player(handle)->mpv, "http-header-fields", MPV_FORMAT_NODE, &node);
}
extern "C" JNIEXPORT jstring JNICALL Java_com_stellar_player_mpv_MpvNative_read(JNIEnv* env, jobject, jlong handle, jstring name) {
    auto result = property(player(handle), text(env, name).c_str()); return env->NewStringUTF(result.c_str());
}
extern "C" JNIEXPORT jstring JNICALL Java_com_stellar_player_mpv_MpvNative_poll(JNIEnv* env, jobject, jlong handle) {
    auto* p = player(handle);
    bool loaded = false, tracks = false, seeking = false, restarted = false, ended = false, audioReconfigured = false;
    int error = 0;
    std::string replies;
    for (int i = 0; i < 64; ++i) {
        auto* event = mpv_wait_event(p->mpv, 0);
        if (event->event_id == MPV_EVENT_NONE) break;
        if (event->event_id == MPV_EVENT_COMMAND_REPLY) {
            if (!replies.empty()) replies += ',';
            replies += "{\"id\":" + std::to_string(event->reply_userdata)
                + ",\"error\":" + std::to_string(event->error) + '}';
        }
        if (event->event_id == MPV_EVENT_FILE_LOADED) loaded = true;
        if (event->event_id == MPV_EVENT_AUDIO_RECONFIG) audioReconfigured = true;
        if (event->event_id == MPV_EVENT_PROPERTY_CHANGE) tracks = true;
        if (event->event_id == MPV_EVENT_SEEK) seeking = true;
        if (event->event_id == MPV_EVENT_PLAYBACK_RESTART) restarted = true;
        if (event->event_id == MPV_EVENT_END_FILE) {
            auto* end = static_cast<mpv_event_end_file*>(event->data);
            ended = end->reason == MPV_END_FILE_REASON_EOF;
            if (end->reason == MPV_END_FILE_REASON_ERROR) error = end->error;
        }
    }
    std::string out = "{\"loaded\":" + std::to_string(loaded) + ",\"tracksChanged\":" + std::to_string(tracks)
        + ",\"seek\":" + std::to_string(seeking) + ",\"restart\":" + std::to_string(restarted)
        + ",\"ended\":" + std::to_string(ended) + ",\"error\":" + std::to_string(error)
        + ",\"audioReconfigured\":" + (audioReconfigured ? "true" : "false")
        + ",\"replies\":[" + replies + ']';
    for (auto key : {"time-pos", "duration", "pause", "paused-for-cache", "seeking", "speed", "sid", "secondary-sid", "aid", "vid", "demuxer-cache-duration", "hwdec-current"})
        out += ',' + quote(key) + ':' + property(p, key);
    out += ",\"audio-rate\":" + property(p, "audio-params/samplerate");
    out += ",\"audio-output-format\":" + property(p, "audio-out-params/format");
    out += '}';
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jstring JNICALL Java_com_stellar_player_mpv_MpvNative_equalizer(
    JNIEnv* env, jobject, jlong handle, jboolean enabled, jfloat preamp, jfloatArray bands, jint rate) {
    if (env->GetArrayLength(bands) != 10 || rate <= 0) return nullptr;
    std::array<float, 10> gains{};
    env->GetFloatArrayRegion(bands, 0, 10, gains.data());
    auto* p = player(handle);
    const auto graph = stellar::equalizerGraph(enabled, preamp, gains, rate);
    // A named filter leaves unrelated audio filters and mpv's tempo control intact.
    const auto filter = "@stellar-equalizer:lavfi=[" + graph + "]";
    const char* args[] = {"af", enabled ? "add" : "remove",
        enabled ? filter.c_str() : "@stellar-equalizer", nullptr};
    if (enabled) {
        if (mpv_command(p->mpv, args) < 0) return nullptr;
    } else if (property(p, "af").find("stellar-equalizer") != std::string::npos) {
        if (mpv_command(p->mpv, args) < 0) return nullptr;
    }
    return env->NewStringUTF(graph.c_str());
}

namespace {
std::string bytes(JNIEnv* env, jbyteArray value) {
    std::string out(static_cast<size_t>(env->GetArrayLength(value)), '\0');
    env->GetByteArrayRegion(value, 0, static_cast<jsize>(out.size()), reinterpret_cast<jbyte*>(out.data()));
    return out;
}
}
extern "C" JNIEXPORT void JNICALL Java_com_stellar_player_mpv_MpvNative_configureSmb(
    JNIEnv* env, jobject, jlong handle, jstring token, jbyteArray server, jbyteArray share,
    jbyteArray path, jbyteArray user, jbyteArray password, jbyteArray domain, jboolean seal) {
    auto* p = player(handle); p->smbToken = text(env, token);
    stellar::addSmbSource(p->smbToken, bytes(env, server), bytes(env, share), bytes(env, path),
        bytes(env, user), bytes(env, password), bytes(env, domain), seal);
}
extern "C" JNIEXPORT void JNICALL Java_com_stellar_player_mpv_MpvNative_configureNfs(
    JNIEnv* env, jobject, jlong handle, jstring token, jbyteArray server, jbyteArray root,
    jbyteArray path, jint version, jint uid, jint gid, jint port) {
    auto* p = player(handle); p->nfsToken = text(env, token);
    stellar::addNfsSource(p->nfsToken, bytes(env, server), bytes(env, root), bytes(env, path), version, uid, gid, port);
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stellar_player_mpv_MpvNative_configureStream(
    JNIEnv* env, jobject, jlong handle, jstring token, jobject source, jstring cacheDirectory) {
    auto* p = player(handle); p->streamToken = text(env, token);
    return stellar::addCallbackSource(env, p->streamToken, source, text(env, cacheDirectory));
}
extern "C" JNIEXPORT void JNICALL Java_com_stellar_player_mpv_MpvNative_cancelRemote(
    JNIEnv* env, jobject, jstring token) {
    const auto value = text(env, token);
    stellar::cancelSmbSource(value); stellar::cancelNfsSource(value); cancelHttpCache(value);
    stellar::cancelCallbackSource(value);
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stellar_player_mpv_MpvNative_configureHttpCache(
    JNIEnv* env, jobject, jlong handle, jstring token, jstring address, jstring directory,
    jstring headers, jstring userAgent, jboolean verifyTls, jstring caFile) {
    auto* p = player(handle);
    p->httpToken = text(env, token);
    return registerHttpCache(p->mpv, p->httpToken, text(env, address), text(env, directory),
        text(env, headers), text(env, userAgent), verifyTls, text(env, caFile));
}
extern "C" JNIEXPORT jint JNICALL Java_com_stellar_player_mpv_MpvNative_remoteError(
    JNIEnv*, jobject, jlong handle) {
    auto* p = player(handle);
    return p->nfsToken.empty() ? stellar::smbError(p->smbToken) : stellar::nfsError(p->nfsToken);
}
extern "C" JNIEXPORT jstring JNICALL Java_com_stellar_player_mpv_MpvNative_remoteDiagnostics(
    JNIEnv* env, jobject, jlong handle) {
    auto* p = player(handle);
    if (!p->streamToken.empty()) {
        const auto out = stellar::callbackDiagnostics(p->streamToken);
        return env->NewStringUTF(out.c_str());
    }
    if (!p->httpToken.empty()) {
        const auto out = httpCacheDiagnostics(p->httpToken);
        return env->NewStringUTF(out.c_str());
    }
    const auto out = p->nfsToken.empty() ? stellar::smbDiagnostics(p->smbToken) : stellar::nfsDiagnostics(p->nfsToken);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jstring JNICALL Java_com_stellar_player_mpv_MpvNative_discPalette(JNIEnv* env,jobject,jstring address) {
    char palette[128]{};stellar_disc_palette(text(env,address).c_str(),palette,sizeof(palette));return env->NewStringUTF(palette);
}
