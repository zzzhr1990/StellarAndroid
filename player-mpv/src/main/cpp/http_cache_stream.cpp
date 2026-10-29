#include "http_cache_stream.h"
#include "cached_file.h"
#include <mpv/stream_cb.h>
extern "C" {
#include <libavformat/avformat.h>
#include <libavutil/opt.h>
}
#include <algorithm>
#include <atomic>
#include <cctype>
#include <cstring>
#include <memory>
#include <mutex>
#include <unordered_map>

namespace {
struct Source {
    std::string address, directory, headers, userAgent, caFile;
    bool verifyTls;
    std::atomic<bool> cancelled{false};
    std::atomic<int64_t> hits{0}, received{0}, written{0};
};
std::mutex sourcesMutex;
std::unordered_map<std::string, std::shared_ptr<Source>> sources;
std::shared_ptr<Source> lookup(const std::string& token) {
    std::lock_guard lock(sourcesMutex);
    const auto it = sources.find(token);
    return it == sources.end() ? nullptr : it->second;
}

std::string httpUrl(const char* uri) {
    // Match mpv's ordinary HTTP escaping (including existing '%' escapes).
    constexpr char hex[] = "0123456789ABCDEF";
    std::string url;
    for (const auto* p = reinterpret_cast<const unsigned char*>(uri); *p; ++p) {
        if ((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') || (*p >= '0' && *p <= '9')
            || std::strchr("-._~:/?#[]@!$&'()*+,;=%", *p)) url += static_cast<char>(*p);
        else { url += '%'; url += hex[*p >> 4]; url += hex[*p & 15]; }
    }
    return url;
}

class Reader {
public:
    explicit Reader(Source& source) : source_(source) {}
    ~Reader() { if (io_) avio_closep(&io_); }
    bool open(const char* uri) {
        AVDictionary* options = nullptr;
        av_dict_set(&options, "user_agent", source_.userAgent.c_str(), 0);
        av_dict_set(&options, "headers", source_.headers.c_str(), 0);
        av_dict_set(&options, "tls_verify", source_.verifyTls ? "1" : "0", 0);
        if (!source_.caFile.empty()) av_dict_set(&options, "ca_file", source_.caFile.c_str(), 0);
        av_dict_set(&options, "timeout", "60000000", 0);
        AVIOInterruptCB interrupt{[](void* p) { return static_cast<Reader*>(p)->cancelled() ? 1 : 0; }, this};
        const auto normalized = httpUrl(uri);
        const int result = avio_open2(&io_, normalized.c_str(), AVIO_FLAG_READ, &interrupt, &options);
        av_dict_free(&options);
        if (result < 0) return false;
        length_ = avio_size(io_);
        // Live/unknown-length and non-seekable sources retain streaming behavior.
        // Do not persist manifests even when their HTTP response has a fixed size.
        uint8_t* mime = nullptr;
        av_opt_get(io_, "mime_type", AV_OPT_SEARCH_CHILDREN, &mime);
        std::string type = mime ? reinterpret_cast<char*>(mime) : "";
        av_free(mime);
        const std::string url = uri;
        const auto path = url.substr(0, url.find_first_of("?#"));
        auto extension = path.substr(path.find_last_of('.') + 1);
        for (auto* value : {&type, &extension})
            std::transform(value->begin(), value->end(), value->begin(), [](unsigned char c) { return std::tolower(c); });
        const bool manifest = type.find("mpegurl") != std::string::npos || type.find("xml") != std::string::npos
            || type.find("dash") != std::string::npos || type.find("scpls") != std::string::npos || type.rfind("text/", 0) == 0
            || extension == "m3u8" || extension == "m3u" || extension == "mpd" || extension == "pls";
        if (url == source_.address && length_ > 0 && (io_->seekable & AVIO_SEEKABLE_NORMAL) && !manifest)
            cache_ = std::make_unique<CachedFile>(source_.directory, length_);
        return true;
    }
    int64_t read(char* data, uint64_t requested) {
        if (cancelled()) return -1;
        if (length_ >= 0 && position_ >= length_) return 0;
        int count = static_cast<int>(std::min<uint64_t>(requested, 1024 * 1024));
        if (length_ >= 0) count = static_cast<int>(std::min<int64_t>(count, length_ - position_));
        if (cache_) {
            const int hit = cache_->read(position_, data, count);
            if (hit > 0) { source_.hits += hit; position_ += hit; return hit; }
            count = cache_->missing(position_, count);
        }
        if (avio_tell(io_) != position_ && avio_seek(io_, position_, SEEK_SET) != position_) return -1;
        const int received = avio_read_partial(io_, reinterpret_cast<unsigned char*>(data), count);
        if (received <= 0) {
            // A prematurely truncated response must not be accepted as real EOF.
            return received == AVERROR_EOF && (length_ < 0 || position_ == length_) ? 0 : -1;
        }
        source_.received += received;
        if (cache_) {
            // Also recognize extensionless HLS/XML/PLS responses before caching bytes.
            if (position_ == 0 && (data[0] == '#' || data[0] == '<' || data[0] == '[')) cache_.reset();
            else {
                if (cache_->write(position_, data, received)) source_.written += received;
            }
        }
        position_ += received;
        return received;
    }
    int64_t seek(int64_t offset) {
        if (cancelled() || !(io_->seekable & AVIO_SEEKABLE_NORMAL)) return MPV_ERROR_UNSUPPORTED;
        if (offset < 0 || (length_ >= 0 && offset > length_)) return MPV_ERROR_GENERIC;
        // Defer network seek until a cache miss. Cached seeks issue no request.
        position_ = offset;
        return offset;
    }
    int64_t size() const { return length_ > 0 ? length_ : MPV_ERROR_UNSUPPORTED; }
    void cancel() { cancelled_ = true; }
private:
    bool cancelled() const { return cancelled_ || source_.cancelled; }
    Source& source_;
    AVIOContext* io_ = nullptr;
    std::atomic<bool> cancelled_{false};
    int64_t length_ = -1, position_ = 0;
    std::unique_ptr<CachedFile> cache_;
};

int openStream(void* opaque, char* uri, mpv_stream_cb_info* info) {
    auto reader = std::make_unique<Reader>(*static_cast<Source*>(opaque));
    if (!reader->open(uri)) return MPV_ERROR_LOADING_FAILED;
    info->cookie = reader.release();
    info->read_fn = [](void* p, char* data, uint64_t size) { return static_cast<Reader*>(p)->read(data, size); };
    info->seek_fn = [](void* p, int64_t offset) { return static_cast<Reader*>(p)->seek(offset); };
    info->size_fn = [](void* p) { return static_cast<Reader*>(p)->size(); };
    info->cancel_fn = [](void* p) { static_cast<Reader*>(p)->cancel(); };
    info->close_fn = [](void* p) { delete static_cast<Reader*>(p); };
    return 0;
}
}

bool registerHttpCache(mpv_handle* mpv, const std::string& token, const std::string& address,
    const std::string& directory, const std::string& headers, const std::string& userAgent,
    bool verifyTls, const std::string& caFile) {
    auto source = std::make_shared<Source>();
    source->address = address; source->directory = directory; source->headers = headers;
    source->userAgent = userAgent; source->verifyTls = verifyTls; source->caFile = caFile;
    { std::lock_guard lock(sourcesMutex); sources[token] = source; }
    return mpv_stream_cb_add_ro(mpv, "http", source.get(), openStream) >= 0
        && mpv_stream_cb_add_ro(mpv, "https", source.get(), openStream) >= 0;
}
void cancelHttpCache(const std::string& token) { if (auto source = lookup(token)) source->cancelled = true; }
void removeHttpCache(const std::string& token) { std::lock_guard lock(sourcesMutex); sources.erase(token); }
std::string httpCacheDiagnostics(const std::string& token) {
    const auto source = lookup(token);
    if (!source) return "{}";
    return "{\"diskReadBytes\":" + std::to_string(source->hits.load())
        + ",\"networkReadBytes\":" + std::to_string(source->received.load())
        + ",\"diskWriteBytes\":" + std::to_string(source->written.load()) + "}";
}
