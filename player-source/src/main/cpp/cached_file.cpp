#include "cached_file.h"
#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

namespace {
constexpr int64_t Magic = 0x5354434143484501; // STCACHE, format 1; app-private arm64 data.
constexpr size_t MaxRanges = 65536; // At most 1 MiB of range metadata, regardless of seeks.
bool transfer(int fd, void* data, size_t size, int64_t offset, bool write) {
    auto* bytes = static_cast<char*>(data);
    while (size) {
        const auto n = write ? pwrite(fd, bytes, size, offset) : pread(fd, bytes, size, offset);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        bytes += n; size -= n; offset += n;
    }
    return true;
}
}

CachedFile::CachedFile(const std::string& directory, int64_t length) : length_(length) {
    if (length <= 0) return;
    // Pin and lock the directory inode, not its replaceable pathname or data file.
    // Clear/recreate can start a new generation while this one is still closing.
    directory_ = open(directory.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (directory_ < 0) return;
    if (flock(directory_, LOCK_EX | LOCK_NB) != 0) { disable(); return; }
    fd_ = openat(directory_, "data", O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd_ < 0) { disable(); return; }
    struct stat data{};
    bool valid = fstat(fd_, &data) == 0 && S_ISREG(data.st_mode);
    const int meta = openat(directory_, "ranges", O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    int64_t header[3]{};
    struct stat info{};
    valid = valid && meta >= 0 && fstat(meta, &info) == 0 && S_ISREG(info.st_mode)
        && transfer(meta, header, sizeof(header), 0, false)
        && header[0] == Magic && header[1] == length && header[2] >= 0
        && header[2] <= static_cast<int64_t>(MaxRanges)
        && info.st_size == static_cast<int64_t>(sizeof(header) + header[2] * sizeof(Range));
    if (valid) {
        ranges_.resize(header[2]);
        valid = transfer(meta, ranges_.data(), ranges_.size() * sizeof(Range), sizeof(header), false);
        int64_t previous = -1;
        for (const auto& range : ranges_) {
            valid = valid && range.begin > previous && range.end > range.begin
                && range.end <= length && range.end <= data.st_size;
            previous = range.end;
        }
    }
    if (meta >= 0) close(meta);
    if (!valid) {
        ranges_.clear();
        // Invalidate the old index before truncating data it might reference.
        if ((unlinkat(directory_, "ranges", 0) != 0 && errno != ENOENT) || ftruncate(fd_, 0) != 0) discard();
    }
}

CachedFile::~CachedFile() { commit(); disable(); }

int CachedFile::read(int64_t offset, char* data, int count) {
    if (fd_ < 0 || count <= 0) return 0;
    const auto it = std::upper_bound(ranges_.begin(), ranges_.end(), offset,
        [](int64_t pos, const Range& range) { return pos < range.begin; });
    if (it == ranges_.begin() || offset >= (it - 1)->end) return 0;
    count = static_cast<int>(std::min<int64_t>(count, (it - 1)->end - offset));
    if (transfer(fd_, data, count, offset, false)) return count;
    discard();
    return 0; // A missing/truncated cache is a network miss, never fabricated bytes.
}

int CachedFile::missing(int64_t offset, int count) const {
    if (fd_ < 0) return count;
    const auto it = std::upper_bound(ranges_.begin(), ranges_.end(), offset,
        [](int64_t pos, const Range& range) { return pos < range.begin; });
    return it == ranges_.end() ? count : static_cast<int>(std::min<int64_t>(count, it->begin - offset));
}

bool CachedFile::write(int64_t offset, const char* data, int count) {
    if (fd_ < 0 || count <= 0 || offset < 0 || offset > length_ - count) return false;
    Range added{offset, offset + count};
    auto first = std::lower_bound(ranges_.begin(), ranges_.end(), added.begin,
        [](const Range& range, int64_t pos) { return range.end < pos; });
    auto last = first;
    while (last != ranges_.end() && last->begin <= added.end) {
        added.begin = std::min(added.begin, last->begin);
        added.end = std::max(added.end, last->end);
        ++last;
    }
    const bool insert = first == last;
    if (insert && ranges_.size() == MaxRanges) return false;
    if (!transfer(fd_, const_cast<char*>(data), count, offset, true)) { discard(); return false; }
    if (insert) {
        const auto position = first - ranges_.begin();
        if (ranges_.size() == ranges_.capacity())
            ranges_.reserve(std::min(MaxRanges, std::max(size_t{16}, ranges_.capacity() * 2)));
        ranges_.insert(ranges_.begin() + position, added);
    } else {
        // Sequential reads extend a range in place; don't shift the cached tail
        // twice on every read while filling a gap before an already cached range.
        *first = added;
        if (last != first + 1) ranges_.erase(first + 1, last);
    }
    dirty_ = true;
    return true;
}

void CachedFile::discard() {
    // Best effort, under our directory lock. Never touch another media entry or
    // a replacement directory. Release allocated bytes, then fall back to HTTP.
    unlinkat(directory_, "ranges", 0);
    unlinkat(directory_, "ranges.tmp", 0);
    unlinkat(directory_, "data", 0);
    disable();
}

void CachedFile::disable() {
    if (fd_ >= 0) close(fd_);
    if (directory_ >= 0) close(directory_);
    directory_ = fd_ = -1; dirty_ = false;
    std::vector<Range>().swap(ranges_);
}

void CachedFile::commit() {
    if (fd_ < 0 || !dirty_) return;
    struct stat data{};
    // A clear may unlink data before removing the directory. Do not resurrect
    // an index for that file, and avoid syncing bytes that have been discarded.
    if (fstat(fd_, &data) != 0 || data.st_nlink == 0) return;
    if (fdatasync(fd_) != 0) { discard(); return; }
    // One compact index write at stream close. A crash before commit loses new
    // cache coverage only; previously committed ranges are never overwritten.
    const int meta = openat(directory_, "ranges.tmp", O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (meta < 0) { discard(); return; }
    int64_t header[]{Magic, length_, static_cast<int64_t>(ranges_.size())};
    const bool saved = transfer(meta, header, sizeof(header), 0, true)
        && transfer(meta, ranges_.data(), ranges_.size() * sizeof(Range), sizeof(header), true)
        && fsync(meta) == 0;
    close(meta);
    if (!saved || renameat(directory_, "ranges.tmp", directory_, "ranges") != 0) discard();
}
