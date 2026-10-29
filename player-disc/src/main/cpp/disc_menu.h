#pragma once
#include "disc_stream.h"
#include <atomic>
#include <condition_variable>
#include <mutex>
#include <vector>

// One navigation VM per menu session, shared by successive decoder streams.
// All VM calls are serialized; clock/cancellation wake a bounded read-ahead.
struct DiscMenu {
    std::mutex mutex;
    std::condition_variable changed;
    std::atomic<bool> closed{false};
    int epoch=1, revision=0, width=0, height=0, selected=0, buttons=0;
    int still=-1, error=0, audio=-1, subtitle=-1, title=0;
    bool interactive=false, waiting=false, boundary=false, stopped=false;
    int64_t clock=0, readTime=0, stillSince=-1;
    double aspect=16.0/9;
    std::vector<uint32_t> pixels;
    std::vector<uint8_t> prefetched;
    virtual ~DiscMenu() = default;
    virtual bool start() = 0;
    virtual int next(std::vector<uint8_t>& bytes) = 0;
    virtual bool input(int key, int x, int y) = 0;
    virtual void resume() = 0;
    virtual void cancelRead() = 0;
    virtual void newTimeline() {}
    virtual void tick() {}
    virtual std::string palette() const {return {};}
    void restart() { ++epoch; clock=0; readTime=0; still=-1; stillSince=-1; waiting=false; boundary=false; stopped=false; prefetched.clear(); changed.notify_all(); }
    void clearOverlay() { pixels.clear(); width=height=selected=buttons=0; ++revision; }
};
uint32_t discColor(int y, int cr, int cb, int alpha, bool hd=false);
std::shared_ptr<DiscMenu> makeDvdMenu(const std::string& token);
std::shared_ptr<DiscMenu> makeBlurayMenu(const std::string& token);
std::unique_ptr<DiscStream> makeMenuStream();
std::string discMenuPalette(const std::string& url);
