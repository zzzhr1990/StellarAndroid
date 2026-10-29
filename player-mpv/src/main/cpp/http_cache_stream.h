#pragma once
#include <mpv/client.h>
#include <string>

bool registerHttpCache(mpv_handle* mpv, const std::string& token, const std::string& address,
    const std::string& directory, const std::string& headers, const std::string& userAgent,
    bool verifyTls, const std::string& caFile);
void cancelHttpCache(const std::string& token);
void removeHttpCache(const std::string& token);
std::string httpCacheDiagnostics(const std::string& token);
