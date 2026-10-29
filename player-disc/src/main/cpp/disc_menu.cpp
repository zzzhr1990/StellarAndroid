#include "disc_menu.h"
#include <jni.h>
#include <algorithm>
#include <chrono>
#include <cstring>
#include <map>
#include <sstream>

namespace {
std::mutex registryMutex;
std::map<int64_t,std::shared_ptr<DiscMenu>> menus;
int64_t nextId=1;
std::shared_ptr<DiscMenu> find(int64_t id) { std::lock_guard lock(registryMutex); auto i=menus.find(id); return i==menus.end()?nullptr:i->second; }
class MenuStream final:public DiscStream {
    std::shared_ptr<DiscMenu> menu;
    std::atomic<bool> aborted{false};
    int epoch=0; int64_t pos=0;
    std::vector<uint8_t> pending;
    size_t offset=0;
public:
    bool open(const std::string& url) override {
        auto p=url.find("/menu/"); if(p==std::string::npos)return false;
        std::istringstream in(url.substr(p+6)); int64_t id; char slash;
        if(!(in>>id>>slash>>epoch)||slash!='/')return false;
        menu=find(id); return menu && !menu->closed;
    }
    bool abort() override { aborted=true; if(menu){menu->changed.notify_all();menu->cancelRead();}return true; }
    bool isSeekable() const override{return false;}
    int64_t position() const override{return pos;}
    int64_t size() const override{return -1;}
    bool seek(int64_t n,int from) override{return (from==SEEK_SET && n==pos)||(from==SEEK_CUR && n==0);}
    int64_t read(uint8_t* out,int64_t n) override {
        if(!menu||n<=0)return 0;
        std::unique_lock lock(menu->mutex);
        if(aborted||menu->closed)return -1;
        if(menu->epoch!=epoch)return 0;
        if(offset==pending.size()) {
            pending.clear();offset=0;
            while(menu->readTime>menu->clock+2000 && !aborted && !menu->closed && menu->epoch==epoch)
                menu->changed.wait_for(lock,std::chrono::milliseconds(100));
            if(aborted||menu->closed)return -1;
            if(menu->epoch!=epoch)return 0;
            if(menu->still>=0||menu->boundary||menu->stopped||menu->error)return 0;
            int result;
            if(!menu->prefetched.empty()){pending.swap(menu->prefetched);result=pending.size();}
            else result=menu->next(pending);
            // A fresh decoder has no frames to drain. Later WAITs end this stream;
            // poll resumes the VM only once the backend confirms its EOF.
            for(int i=0;result==0 && menu->waiting && pos==0 && i<16;i++) {
                menu->resume();menu->waiting=false;result=menu->next(pending);
            }
            if(result<=0)return result;
        }
        auto count=std::min<int64_t>(n,pending.size()-offset);
        std::memcpy(out,pending.data()+offset,count);offset+=count;pos+=count;return count;
    }
};
}
uint32_t discColor(int y,int cr,int cb,int a,bool hd) {
    double l=1.164383*(y-16),r=l+(hd?1.792741:1.596027)*(cr-128);
    double g=l-(hd?0.213249:0.391762)*(cb-128)-(hd?0.532909:0.812968)*(cr-128);
    double b=l+(hd?2.112402:2.017232)*(cb-128);
    auto c=[](double v){return std::clamp(int(v+0.5),0,255);};
    return uint32_t(a)<<24|c(r)<<16|c(g)<<8|c(b);
}
std::unique_ptr<DiscStream> makeMenuStream(){return std::make_unique<MenuStream>();}
std::string discMenuPalette(const std::string& url) {
    auto p=url.find("/menu/");if(p==std::string::npos)return {};
    int64_t id=0;std::istringstream(url.substr(p+6))>>id;auto m=find(id);if(!m)return {};
    std::lock_guard lock(m->mutex);return m->palette();
}
extern "C" JNIEXPORT jlong JNICALL Java_com_stellar_player_disc_MenuNative_open(JNIEnv* e,jobject,jstring token,jboolean bluray) {
    const char* raw=e->GetStringUTFChars(token,nullptr);std::string name(raw);e->ReleaseStringUTFChars(token,raw);
    auto menu=bluray?makeBlurayMenu(name):makeDvdMenu(name);
    if(!menu||!menu->start())return 0;
    std::lock_guard lock(registryMutex);auto id=nextId++;menus[id]=std::move(menu);return id;
}
extern "C" JNIEXPORT jstring JNICALL Java_com_stellar_player_disc_MenuNative_poll(JNIEnv* e,jobject,jlong id,jlong clock,jboolean ended,jlong now) {
    auto m=find(id);if(!m)return e->NewStringUTF("{}");
    std::lock_guard lock(m->mutex);m->clock=clock;
    m->tick();
    bool resume=ended && (m->waiting||m->boundary);
    if(ended && m->still>=0) {
        if(m->stillSince<0)m->stillSince=now;
        resume=m->still!=255 && now-m->stillSince>=m->still*1000LL;
    }
    if(resume) {
        m->resume();m->waiting=false;m->boundary=false;m->still=-1;m->newTimeline();
        int n=m->next(m->prefetched);
        // A still following WAIT keeps the existing decoder and its last frame.
        if(n>0){auto bytes=std::move(m->prefetched);m->restart();m->prefetched=std::move(bytes);}
    }
    m->changed.notify_all();
    std::ostringstream j;j<<"{\"epoch\":"<<m->epoch<<",\"revision\":"<<m->revision<<",\"width\":"<<m->width<<",\"height\":"<<m->height
        <<",\"aspect\":"<<m->aspect<<",\"selected\":"<<m->selected<<",\"buttons\":"<<m->buttons<<",\"interactive\":"<<(m->interactive?"true":"false")
        <<",\"still\":"<<m->still<<",\"error\":"<<m->error<<",\"audio\":"<<m->audio<<",\"subtitle\":"<<m->subtitle<<",\"title\":"<<m->title<<"}";
    return e->NewStringUTF(j.str().c_str());
}
extern "C" JNIEXPORT jintArray JNICALL Java_com_stellar_player_disc_MenuNative_pixels(JNIEnv* e,jobject,jlong id,jint revision) {
    auto m=find(id);if(!m)return nullptr;std::lock_guard lock(m->mutex);
    if(m->revision!=revision)return nullptr;
    auto a=e->NewIntArray(m->pixels.size());if(a)e->SetIntArrayRegion(a,0,m->pixels.size(),reinterpret_cast<const jint*>(m->pixels.data()));return a;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stellar_player_disc_MenuNative_input(JNIEnv*,jobject,jlong id,jint key,jint x,jint y) {
    auto m=find(id);if(!m)return false;std::lock_guard lock(m->mutex);if(m->closed)return false;return m->input(key,x,y);
}
extern "C" JNIEXPORT void JNICALL Java_com_stellar_player_disc_MenuNative_close(JNIEnv*,jobject,jlong id) {
    std::shared_ptr<DiscMenu> m;{std::lock_guard lock(registryMutex);auto i=menus.find(id);if(i==menus.end())return;m=i->second;menus.erase(i);}
    m->closed=true;m->changed.notify_all();m->cancelRead();
}
