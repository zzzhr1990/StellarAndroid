#include "disc_stream.h"
#include "disc_menu.h"
#include "stellar_disc.h"
#include <cstring>
struct StreamHandle { bool bluray; std::unique_ptr<DiscStream> stream; };
extern "C" {
void* stellar_disc_create(int bluray) { return new StreamHandle{bluray!=0,nullptr}; }
int stellar_disc_open(void* p, const char* url) {
    auto* h=static_cast<StreamHandle*>(p);
    h->stream=std::strstr(url,"/menu/")?makeMenuStream():(h->bluray?makeBlurayStream():makeDvdStream());
    return h->stream->open(url);
}
int64_t stellar_disc_read(void* p, uint8_t* data, int64_t n) { return static_cast<StreamHandle*>(p)->stream->read(data, n); }
int64_t stellar_disc_seek(void* p, int64_t n, int from) {
    auto* s = static_cast<StreamHandle*>(p)->stream.get(); return s->seek(n, from) ? s->position() : -1;
}
int64_t stellar_disc_size(void* p) { auto* s=static_cast<StreamHandle*>(p)->stream.get(); return s?s->size():0; }
int64_t stellar_disc_position(void* p) { auto* s=static_cast<StreamHandle*>(p)->stream.get(); return s?s->position():0; }
void stellar_disc_abort(void* p) { auto* s=static_cast<StreamHandle*>(p)->stream.get(); if(s)s->abort(); }
void stellar_disc_close(void* p) { delete static_cast<StreamHandle*>(p); }
int stellar_disc_palette(const char* url, char* output, int capacity) {
    const auto palette = std::strstr(url,"/menu/")?discMenuPalette(url):stellarDvdPalette(url);
    if (capacity <= static_cast<int>(palette.size())) return 0;
    std::memcpy(output, palette.c_str(), palette.size() + 1); return palette.size();
}
}
