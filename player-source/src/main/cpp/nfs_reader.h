#pragma once
#include <string>
namespace stellar {
void addNfsSource(const std::string& token, std::string server, std::string exportPath,
                  std::string path, int version, int uid, int gid, int port);
void cancelNfsSource(const std::string& token);
void removeNfsSource(const std::string& token);
int nfsError(const std::string& token);
std::string nfsDiagnostics(const std::string& token);
}
