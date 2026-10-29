#pragma once
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace stellar {
using TimeoutCallback = std::function<bool(int64_t)>;
// One IO thread owns open/read/seek/destruction. Only abort and setTimeout may
// run concurrently; they never destroy the protocol context or callback storage.
class RemoteReader {
public:
    virtual ~RemoteReader() = default;
    virtual bool open(const std::string& token) = 0;
    virtual bool isSeekable() const = 0;
    virtual bool abort() = 0;
    virtual bool setTimeout(int64_t ms, const TimeoutCallback& callback) = 0;
    virtual int64_t position() const = 0;
    virtual int64_t size() const = 0;
    virtual bool seek(int64_t offset, int from) = 0;
    virtual int64_t read(uint8_t* data, int64_t maximum) = 0;
};
std::unique_ptr<RemoteReader> makeSmbReader();
std::unique_ptr<RemoteReader> makeNfsReader();
}
