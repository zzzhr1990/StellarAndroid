#pragma once
#include <cstdint>
#include <string>
#include <vector>

// Shared single-reader sparse byte cache. The playback thread owns it; no per-read locks,
// JNI crossings, block allocations, or media hashes. Only completed ranges commit.
class CachedFile {
public:
    CachedFile(const std::string& directory, int64_t length);
    ~CachedFile();
    CachedFile(const CachedFile&) = delete;
    CachedFile& operator=(const CachedFile&) = delete;
    bool enabled() const { return fd_ >= 0; }
    int read(int64_t offset, char* data, int count);
    int missing(int64_t offset, int count) const;
    bool write(int64_t offset, const char* data, int count);
private:
    struct Range { int64_t begin, end; };
    int directory_ = -1, fd_ = -1;
    int64_t length_;
    bool dirty_ = false;
    std::vector<Range> ranges_;
    void discard();
    void disable();
    void commit();
};
