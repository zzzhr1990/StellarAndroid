#pragma once
#include <jni.h>
#include <cstdint>
#include <string>

namespace nas_jni {
// DeleteLocalRef is permitted while a Java exception is pending.
template<class T> class LocalRef {
public:
    LocalRef(JNIEnv* env, T value) : env_(env), value_(value) {}
    ~LocalRef() { if (value_) env_->DeleteLocalRef(value_); }
    LocalRef(const LocalRef&) = delete;
    LocalRef& operator=(const LocalRef&) = delete;
    T get() const { return value_; }
    explicit operator bool() const { return value_ != nullptr; }
private:
    JNIEnv* env_;
    T value_;
};

// Preserve normal UTF-8 (including supplementary path characters), not JNI MUTF-8.
// False means the caller must return before another JNI/network operation.
inline bool utf8(JNIEnv* env, jstring value, std::string& out) {
    const auto chars = env->GetStringChars(value, nullptr);
    if (!chars) return false; // Keep the VM's original exception pending.
    struct Release {
        JNIEnv* env; jstring value; const jchar* chars;
        ~Release() { env->ReleaseStringChars(value, chars); }
    } release{env, value, chars};
    const auto length = env->GetStringLength(value);
    for (jsize i = 0; i < length; ++i) {
        uint32_t c = chars[i];
        if (c >= 0xd800 && c <= 0xdbff && i + 1 < length && chars[i+1] >= 0xdc00 && chars[i+1] <= 0xdfff)
            c = 0x10000 + ((c - 0xd800) << 10) + (chars[++i] - 0xdc00);
        if (c < 0x80) out += static_cast<char>(c);
        else if (c < 0x800) { out += char(0xc0 | (c >> 6)); out += char(0x80 | (c & 63)); }
        else if (c < 0x10000) { out += char(0xe0 | (c >> 12)); out += char(0x80 | ((c >> 6) & 63)); out += char(0x80 | (c & 63)); }
        else { out += char(0xf0 | (c >> 18)); out += char(0x80 | ((c >> 12) & 63)); out += char(0x80 | ((c >> 6) & 63)); out += char(0x80 | (c & 63)); }
    }
    return true;
}
}
