#pragma once
#include <cstdint>
#include <string>

namespace stellar {
void addSmbSource(const std::string& token, std::string server, std::string share,
                  std::string path, std::string user, std::string password,
                  std::string domain, bool seal);
void cancelSmbSource(const std::string& token);
void removeSmbSource(const std::string& token);
int smbError(const std::string& token);
std::string smbDiagnostics(const std::string& token);
}
