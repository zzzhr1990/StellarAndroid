#pragma once
#include <cstdint>
#include <memory>
#include <string>
class DiscStream {
public:
    virtual ~DiscStream() = default;
    virtual bool open(const std::string& address) = 0;
    virtual bool abort() = 0;
    virtual bool isSeekable() const = 0;
    virtual int64_t position() const = 0;
    virtual int64_t size() const = 0;
    virtual bool seek(int64_t offset, int from) = 0;
    virtual int64_t read(uint8_t* data, int64_t maximum) = 0;
};
std::unique_ptr<DiscStream> makeDvdStream();
std::unique_ptr<DiscStream> makeBlurayStream();
std::string stellarDvdPalette(const std::string& url);
