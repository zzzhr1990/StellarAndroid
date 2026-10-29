#include "remote_stream.h"
#include "remote_reader.h"
#include "callback_reader.h"
#include <stellar_disc.h>
#include <mpv/stream_cb.h>
#include <algorithm>
#include <cstdio>
#include <cstring>

namespace {
using stellar::RemoteReader;
int openDisc(void*, char* uri, mpv_stream_cb_info* info) {
    void* stream = stellar_disc_create(std::strncmp(uri, "stellarbluray:", 13) == 0);
    if (!stellar_disc_open(stream, uri)) { stellar_disc_close(stream); return MPV_ERROR_LOADING_FAILED; }
    info->cookie = stream;
    info->read_fn = [](void* p, char* data, uint64_t n) -> int64_t {
        return stellar_disc_read(p, reinterpret_cast<uint8_t*>(data), std::min(n, uint64_t{INT64_MAX}));
    };
    info->seek_fn = [](void* p, int64_t n) { return stellar_disc_seek(p, n, SEEK_SET); };
    info->size_fn = stellar_disc_size;
    info->cancel_fn = stellar_disc_abort;
    info->close_fn = stellar_disc_close;
    return 0;
}
int openRemote(void*, char* uri, mpv_stream_cb_info* info) {
    auto reader = std::strncmp(uri, "stellarcloud:", 13) == 0 ? stellar::makeCallbackReader()
        : std::strncmp(uri, "smb://", 6) == 0 ? stellar::makeSmbReader() : stellar::makeNfsReader();
    // Only registered session tokens resolve. Credentials never enter an mpv URL.
    // Open is interrupted by source cancellation even before mpv has a stream cookie.
    if (!reader->open(uri)) return MPV_ERROR_LOADING_FAILED;
    info->cookie = reader.release();
    info->read_fn = [](void* cookie, char* buffer, uint64_t length) -> int64_t {
        return static_cast<RemoteReader*>(cookie)->read(reinterpret_cast<uint8_t*>(buffer),
            static_cast<int64_t>(std::min(length, uint64_t{INT64_MAX})));
    };
    info->seek_fn = [](void* cookie, int64_t offset) -> int64_t {
        auto* stream = static_cast<RemoteReader*>(cookie);
        return stream->seek(offset, SEEK_SET) ? stream->position() : MPV_ERROR_GENERIC;
    };
    info->size_fn = [](void* cookie) { return static_cast<RemoteReader*>(cookie)->size(); };
    info->cancel_fn = [](void* cookie) { static_cast<RemoteReader*>(cookie)->abort(); };
    info->close_fn = [](void* cookie) { delete static_cast<RemoteReader*>(cookie); };
    return 0;
}
}

bool registerRemoteStreams(mpv_handle* mpv) {
    return mpv_stream_cb_add_ro(mpv, "stellardvd", nullptr, openDisc) >= 0 &&
        mpv_stream_cb_add_ro(mpv, "stellarbluray", nullptr, openDisc) >= 0 &&
        mpv_stream_cb_add_ro(mpv, "stellarcloud", nullptr, openRemote) >= 0 &&
        mpv_stream_cb_add_ro(mpv, "smb", nullptr, openRemote) >= 0 &&
        mpv_stream_cb_add_ro(mpv, "nfs", nullptr, openRemote) >= 0;
}
