#include "callback_reader.h"
#include "cached_file.h"
#include <algorithm>
#include <atomic>
#include <cstdio>
#include <limits>
#include <mutex>
#include <unordered_map>

namespace stellar {
namespace {
// Attach once per native IO thread, not once per read. Java-owned threads are never detached.
struct ThreadEnv {
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    bool attached = false;
    JNIEnv* get(JavaVM* value) {
        if (env) return env;
        vm = value;
        if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
            attached = vm->AttachCurrentThread(&env, nullptr) == JNI_OK;
        return env;
    }
    ~ThreadEnv() { if (attached) vm->DetachCurrentThread(); }
};
JNIEnv* environment(JavaVM* vm) { thread_local ThreadEnv thread; return thread.get(vm); }
bool failed(JNIEnv* env) {
    if (!env->ExceptionCheck()) return false;
    // Exceptions can contain request URLs; expose IO failure, never provider diagnostics.
    env->ExceptionClear(); return true;
}
struct JavaReader {
    JavaVM* vm;
    jobject object;
    jmethodID open, read, seek, cancel, close;
    std::atomic<bool> cancelled{false};
    JavaReader(JNIEnv* env, jobject value, JavaVM* machine) : vm(machine), object(env->NewGlobalRef(value)) {
        auto type = env->GetObjectClass(value);
        open = env->GetMethodID(type, "open", "()J");
        read = env->GetMethodID(type, "read", "(Ljava/nio/ByteBuffer;)I");
        seek = env->GetMethodID(type, "seek", "(J)V");
        cancel = env->GetMethodID(type, "cancel", "()V");
        close = env->GetMethodID(type, "close", "()V");
        env->DeleteLocalRef(type);
    }
    void abort() {
        cancelled = true;
        if (auto env = environment(vm)) { env->CallVoidMethod(object, cancel); failed(env); }
    }
    ~JavaReader() { if (auto env = environment(vm)) env->DeleteGlobalRef(object); }
};
struct Source {
    std::atomic<int64_t> received{0}, diskRead{0}, length{-1};
    JavaVM* vm = nullptr;
    jobject factory = nullptr;
    jmethodID create = nullptr;
    std::string cacheDirectory;
    std::mutex mutex;
    bool cancelled = false;
    std::vector<std::weak_ptr<JavaReader>> readers;
    ~Source() { if (factory) if (auto env = environment(vm)) env->DeleteGlobalRef(factory); }
    std::shared_ptr<JavaReader> reader() {
        auto env = environment(vm);
        if (!env) return {};
        std::lock_guard lock(mutex);
        if (cancelled) return {};
        // createReader is nonblocking. Publish before open so cancellation covers auth/connect.
        auto value = env->CallObjectMethod(factory, create);
        if (failed(env) || !value) return {};
        auto result = std::make_shared<JavaReader>(env, value, vm);
        env->DeleteLocalRef(value);
        if (failed(env)) return {};
        readers.erase(std::remove_if(readers.begin(), readers.end(), [](const auto& r) { return r.expired(); }), readers.end());
        readers.emplace_back(result);
        return result;
    }
    void abort() {
        std::vector<std::shared_ptr<JavaReader>> active;
        { std::lock_guard lock(mutex); cancelled = true;
          for (auto& weak : readers) if (auto r = weak.lock()) active.push_back(std::move(r)); }
        for (auto& r : active) r->abort();
    }
};
std::mutex sourcesMutex;
std::unordered_map<std::string, std::shared_ptr<Source>> sources;
std::shared_ptr<Source> lookup(const std::string& token) {
    std::lock_guard lock(sourcesMutex);
    const auto it = sources.find(token);
    return it == sources.end() ? nullptr : it->second;
}
class CallbackReader final : public RemoteReader {
    std::shared_ptr<Source> source_;
    std::shared_ptr<JavaReader> reader_; // published once, before open; abort may run concurrently
    std::atomic<bool> aborted_{false};
    int64_t position_ = 0, inputPosition_ = 0, size_ = -1;
    std::unique_ptr<CachedFile> cache_;
public:
    bool open(const std::string& token) override {
        source_ = lookup(token);
        if (!source_ || aborted_) return false;
        auto reader = source_->reader();
        if (!reader) return false;
        std::atomic_store(&reader_, reader);
        if (aborted_) { reader->abort(); return false; }
        auto env = environment(reader->vm);
        size_ = env->CallLongMethod(reader->object, reader->open);
        if (failed(env) || size_ < 0 || aborted_ || reader->cancelled) return false;
        source_->length = size_;
        if (!source_->cacheDirectory.empty()) cache_ = std::make_unique<CachedFile>(source_->cacheDirectory, size_);
        return true;
    }
    bool isSeekable() const override { return size_ >= 0; }
    int64_t size() const override { return size_; }
    int64_t position() const override { return position_; }
    bool setTimeout(int64_t, const TimeoutCallback&) override { return false; } // provider HTTP timeouts apply
    bool abort() override {
        aborted_ = true;
        if (auto reader = std::atomic_load(&reader_)) reader->abort();
        return true;
    }
    int64_t read(uint8_t* data, int64_t maximum) override {
        if (aborted_ || !reader_ || reader_->cancelled) return -1;
        if (maximum <= 0) return 0;
        if (position_ == size_) return 0;
        auto env = environment(reader_->vm);
        auto count = static_cast<int>(std::min({maximum, int64_t{1024 * 1024}, size_ - position_}));
        if (cache_) {
            const auto hit = cache_->read(position_, reinterpret_cast<char*>(data), count);
            if (hit > 0) { source_->diskRead += hit; position_ += hit; return hit; }
            count = cache_->missing(position_, count);
        }
        if (inputPosition_ != position_) {
            env->CallVoidMethod(reader_->object, reader_->seek, static_cast<jlong>(position_));
            if (failed(env)) return -1;
            inputPosition_ = position_;
        }
        auto buffer = env->NewDirectByteBuffer(data, count);
        if (!buffer) { failed(env); return -1; }
        const auto result = env->CallIntMethod(reader_->object, reader_->read, buffer);
        env->DeleteLocalRef(buffer);
        if (failed(env) || aborted_ || reader_->cancelled || result < 0 || result > count) return -1;
        source_->received += result;
        if (cache_ && result > 0) cache_->write(position_, reinterpret_cast<char*>(data), result);
        inputPosition_ += result;
        position_ += result;
        return result;
    }
    bool seek(int64_t offset, int from) override {
        if (aborted_ || !reader_ || reader_->cancelled) return false;
        const int64_t base = from == SEEK_SET ? 0 : from == SEEK_CUR ? position_ : from == SEEK_END ? size_ : -1;
        if (base < 0 || offset < -base || offset > std::numeric_limits<int64_t>::max() - base) return false;
        const auto target = base + offset;
        if (target > size_) return false;
        // Keep a cached seek local. Reposition the provider only on the next cache miss.
        position_ = target; return true;
    }
    ~CallbackReader() override {
        if (reader_) if (auto env = environment(reader_->vm)) {
            env->CallVoidMethod(reader_->object, reader_->close); failed(env);
        }
    }
};
}
bool addCallbackSource(JNIEnv* env, const std::string& token, jobject factory, const std::string& cacheDirectory) {
    auto source = std::make_shared<Source>();
    source->cacheDirectory = cacheDirectory;
    env->GetJavaVM(&source->vm);
    auto type = env->GetObjectClass(factory);
    source->create = env->GetMethodID(type, "createReader", "()Lcom/stellar/player/source/StreamReader;");
    env->DeleteLocalRef(type);
    if (failed(env)) return false;
    source->factory = env->NewGlobalRef(factory);
    if (!source->factory) { failed(env); return false; }
    std::lock_guard lock(sourcesMutex);
    return sources.emplace(token, std::move(source)).second;
}
void cancelCallbackSource(const std::string& token) { if (auto source = lookup(token)) source->abort(); }
std::string callbackDiagnostics(const std::string& token) {
    auto s = lookup(token); if (!s) return "{}";
    return "{\"networkReadBytes\":" + std::to_string(s->received.load())
        + ",\"diskReadBytes\":" + std::to_string(s->diskRead.load())
        + ",\"size\":" + std::to_string(s->length.load()) + "}";
}
void removeCallbackSource(const std::string& token) {
    std::shared_ptr<Source> source;
    { std::lock_guard lock(sourcesMutex); const auto it = sources.find(token);
      if (it != sources.end()) { source = std::move(it->second); sources.erase(it); } }
}
std::unique_ptr<RemoteReader> makeCallbackReader() { return std::make_unique<CallbackReader>(); }
}
