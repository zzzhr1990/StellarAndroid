#pragma once
#include "remote_reader.h"
#include <jni.h>

namespace stellar {
bool addCallbackSource(JNIEnv* env, const std::string& token, jobject factory, const std::string& cacheDirectory);
void cancelCallbackSource(const std::string& token);
void removeCallbackSource(const std::string& token);
std::string callbackDiagnostics(const std::string& token);
std::unique_ptr<RemoteReader> makeCallbackReader();
}
