#include <jni.h>
#include "disc_stream.h"
#include "disc_menu.h"
extern "C" {
#include <libbluray/bluray.h>
#include <libbluray/bluray_internal.h>
#include <libbluray/keys.h>
#include <libbluray/decoders/overlay.h>
#include <file/filesystem.h>
#include <util/log_control.h>
#include <libbluray/disc/disc.h>
#include <libbluray/disc/enc_info.h>
#include <libbluray/bdnav/navigation.h>
}
#include <algorithm>
#include <atomic>
#include <cstring>
#include <map>
#include <set>
#include <memory>
#include <mutex>
#include <sstream>
#include <vector>

namespace {
constexpr int Packet = 192;
JavaVM* jvm = nullptr;
struct Env {
    JNIEnv* e = nullptr; bool attached = false;
    Env() { if (jvm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_6) != JNI_OK)
        attached = jvm->AttachCurrentThread(&e, nullptr) == JNI_OK; }
    ~Env() { if (attached) jvm->DetachCurrentThread(); }
};
std::string utf(JNIEnv* e, jstring v) { const char* p=e->GetStringUTFChars(v,nullptr); std::string s(p); e->ReleaseStringUTFChars(v,p); return s; }
struct Clip { int64_t start, bytes, delta, startTime, inTime, outTime, fileOffset; std::string id; };
struct Title { unsigned playlist; int64_t duration=0, bytes=0; std::vector<Clip> clips; std::vector<int64_t> chapters; };
struct Source {
    jobject reader=nullptr; jmethodID read=nullptr, cancel=nullptr; bool iso=false;
    std::map<std::string,int64_t> files; std::vector<Title> titles; std::atomic<bool> closed{false};
    ~Source() { if(reader) { Env env; if(env.e) env.e->DeleteGlobalRef(reader); } }
    void abortRead() { Env env; if(env.e && cancel) { env.e->CallVoidMethod(reader,cancel); if(env.e->ExceptionCheck())env.e->ExceptionClear(); } }
    int readAt(const std::string& name,int64_t offset,uint8_t* output,int count) {
        if(closed || offset<0 || count<0 || count>2*1024*1024)return -1;
        Env env; auto* e=env.e;if(!e)return -1;
        auto path=e->NewStringUTF(name.c_str());
        auto bytes=static_cast<jbyteArray>(e->CallObjectMethod(reader,read,path,offset,count));e->DeleteLocalRef(path);
        if(e->ExceptionCheck()){e->ExceptionClear();return -1;}if(!bytes)return -1;
        int n=e->GetArrayLength(bytes);if(n<=count)e->GetByteArrayRegion(bytes,0,n,reinterpret_cast<jbyte*>(output));e->DeleteLocalRef(bytes);
        return n<=count?n:-1;
    }
};
std::mutex registryMutex;
std::map<std::string,std::shared_ptr<Source>> sources;
struct File { Source* s; std::string name; int64_t pos=0; };
struct Directory { std::vector<std::string> names; size_t pos=0; };
BD_FILE_H* fileOpen(void* handle,const char* name) {
    auto* s=static_cast<Source*>(handle);if(s->closed || !s->files.count(name))return nullptr;
    return new BD_FILE_H{new File{s,name},
        [](BD_FILE_H* h){delete static_cast<File*>(h->internal);delete h;},
        [](BD_FILE_H* h,int64_t n,int32_t from)->int64_t{auto* f=static_cast<File*>(h->internal);
            auto size=f->s->files.at(f->name);int64_t base=from==SEEK_SET?0:from==SEEK_CUR?f->pos:from==SEEK_END?size:-1;
            if(base<0 || (n>0 && base>INT64_MAX-n)||(n<0 && n < -base)||base+n>size)return -1;return f->pos=base+n;},
        [](BD_FILE_H* h)->int64_t{return static_cast<File*>(h->internal)->pos;},nullptr,
        [](BD_FILE_H* h,uint8_t* data,int64_t n)->int64_t{auto* f=static_cast<File*>(h->internal);
            auto count=std::min<int64_t>({n,2*1024*1024,f->s->files.at(f->name)-f->pos});if(count<0)return -1;
            int r=f->s->readAt(f->name,f->pos,data,count);if(r>0)f->pos+=r;return r;},nullptr};
}
BD_DIR_H* dirOpen(void* handle,const char* name) {
    auto* s=static_cast<Source*>(handle);if(s->closed)return nullptr;
    std::string prefix(name);if(!prefix.empty() && prefix.back()!='/')prefix+='/';
    std::set<std::string> names;
    for(const auto& f:s->files)if(f.first.starts_with(prefix))names.insert(f.first.substr(prefix.size()).substr(0,f.first.substr(prefix.size()).find('/')));
    if(names.empty())return nullptr;
    return new BD_DIR_H{new Directory{{names.begin(),names.end()}},
        [](BD_DIR_H* h){delete static_cast<Directory*>(h->internal);delete h;},
        [](BD_DIR_H* h,BD_DIRENT* out)->int{auto* d=static_cast<Directory*>(h->internal);if(d->pos==d->names.size())return 1;
            std::strncpy(out->d_name,d->names[d->pos++].c_str(),255);out->d_name[255]=0;return 0;}};
}
struct Disc {
    std::shared_ptr<Source> source; BLURAY* bd=nullptr;
    explicit Disc(std::shared_ptr<Source> s):source(std::move(s)),bd(bd_init()) {
        if(!bd)return;
        int ok=source->iso?bd_open_stream(bd,source.get(),[](void* p,void* data,int lba,int count)->int {
            if(lba<0 || count<0 || count>1024)return -1;
            int n=static_cast<Source*>(p)->readAt("",int64_t(lba)*2048,static_cast<uint8_t*>(data),count*2048);
            return n<0?-1:n/2048;
        }):bd_open_files(bd,source.get(),dirOpen,fileOpen);
        if(!ok){bd_close(bd);bd=nullptr;}
    }
    ~Disc(){if(bd)bd_close(bd);}
};
// Preserve 33-bit timestamps and marker bits. All packets remain at their original logical byte offset.
uint64_t pts(const uint8_t* p){return uint64_t((p[0]>>1)&7)<<30|uint64_t(p[1])<<22|uint64_t(p[2]>>1)<<15|uint64_t(p[3])<<7|(p[4]>>1);}
void shiftPts(uint8_t* p,int64_t delta){uint64_t t=(pts(p)+delta)&0x1ffffffffULL;
    p[0]=(p[0]&0xf1)|((t>>29)&14);p[1]=t>>22;p[2]=((t>>14)&0xfe)|1;p[3]=t>>7;p[4]=((t<<1)&0xfe)|1;}
bool normalize(uint8_t* packet,int64_t delta) {
    auto* p=packet+4;if(p[0]!=0x47 || (p[3]&0xc0))return false;
    unsigned afc=(p[3]>>4)&3,offset=4;
    if(afc==0)return false;
    if(afc&2){unsigned len=p[4];if(len>183)return false;offset+=1+len;
        if(len>=7 && (p[5]&0x10)){auto* q=p+6;uint64_t t=(uint64_t(q[0])<<25|uint64_t(q[1])<<17|uint64_t(q[2])<<9|uint64_t(q[3])<<1|(q[4]>>7));
            t=(t+delta)&0x1ffffffffULL;q[0]=t>>25;q[1]=t>>17;q[2]=t>>9;q[3]=t>>1;q[4]=(q[4]&0x7f)|((t&1)<<7);}}
    if(!(afc&1) || !(p[1]&0x40) || offset+9>188)return true;
    auto* pes=p+offset;
    if(pes[0]||pes[1]||pes[2]!=1 || (pes[6]&0xc0)!=0x80)return true;
    unsigned flags=pes[7]>>6;
    if(flags>=2){if(offset+14>188 || pes[8]<5)return false;shiftPts(pes+9,delta);}
    if(flags==3){if(offset+19>188 || pes[8]<10)return false;shiftPts(pes+14,delta);}
    return true;
}
class BlurayIO final:public DiscStream {
    std::shared_ptr<Source> source_;std::shared_ptr<Source> abortSource_;std::unique_ptr<Disc> disc_;const Title* title_=nullptr;
    int64_t pos_=0,cacheStart_=-1;std::vector<uint8_t> cache_;std::atomic<bool> aborted_{false};
    BD_FILE_H* file_=nullptr;std::string fileId_;
    bool fill() {
        int64_t wanted=pos_/Packet*Packet;
        auto clip=std::upper_bound(title_->clips.begin(),title_->clips.end(),wanted,[](int64_t p,const Clip& c){return p<c.start;});
        if(clip==title_->clips.begin())return false;--clip;
        if(fileId_!=clip->id || !file_) {
            if(file_)file_->close(file_);file_=nullptr;fileId_=clip->id;
            file_=bd_open_file_dec(disc_->bd,("BDMV/STREAM/"+clip->id+".m2ts").c_str());if(!file_)return false;
            auto length=file_->seek(file_,0,SEEK_END);if(length<clip->fileOffset+clip->bytes)return false;
        }
        auto offset=clip->fileOffset+wanted-clip->start;
        if(file_->seek(file_,offset,SEEK_SET)!=offset)return false;
        auto count=std::min<int64_t>(Packet*256,clip->start+clip->bytes-wanted);
        if(count<=0)return false;cache_.resize(count);cacheStart_=wanted;
        int64_t done=0;
        while(done<count && !aborted_ && !source_->closed){
            auto n=file_->read(file_,cache_.data()+done,count-done);if(n<=0)return false;done+=n;
        }
        if(done!=count)return false;
        for(size_t i=0;i<cache_.size();i+=Packet)if(!normalize(cache_.data()+i,clip->delta))return false;
        return true;
    }

public:
    ~BlurayIO()override {if(file_)file_->close(file_);}
    bool isSeekable()const override{return title_!=nullptr;}
    int64_t size()const override{return title_?title_->bytes:0;}
    int64_t position()const override{return pos_;}
    bool abort()override{aborted_=true;if(auto source=std::atomic_load(&abortSource_))source->abortRead();return true;}
    bool seek(int64_t n,int from)override {int64_t base=from==SEEK_SET?0:from==SEEK_CUR?pos_:from==SEEK_END?size():-1;
        if(base<0||(n>0&&base>INT64_MAX-n)||(n<0&&n < -base)||base+n>size())return false;pos_=base+n;return true;}
    int64_t read(uint8_t* data,int64_t maximum)override {
        if(!title_||maximum<0||aborted_||source_->closed)return -1;int64_t done=0;
        while(done<maximum && pos_<size()){
            if(aborted_||source_->closed)return -1;
            if(pos_<cacheStart_||pos_>=cacheStart_+static_cast<int64_t>(cache_.size()))if(!fill()){cache_.clear();cacheStart_=-1;return -1;}
            auto n=std::min<int64_t>({maximum-done,cacheStart_+static_cast<int64_t>(cache_.size())-pos_,size()-pos_});
            std::memcpy(data+done,cache_.data()+pos_-cacheStart_,n);done+=n;pos_+=n;
        }return done;
    }
    bool open(const std::string& url) override {
        if(file_)file_->close(file_);file_=nullptr;fileId_.clear();
        disc_.reset();source_.reset();title_=nullptr;pos_=0;cacheStart_=-1;cache_.clear();aborted_=false;
        auto slash=url.find_last_of('/');if(slash==std::string::npos)return false;auto number=url.substr(slash+1);
        if(number.empty()||number.size()>5||number.find_first_not_of("0123456789")!=std::string::npos)return false;
        {std::lock_guard lock(registryMutex);auto i=sources.find(url.substr(0,slash));if(i==sources.end())return false;source_=i->second;}
        std::atomic_store(&abortSource_,source_);
        if(source_->closed || aborted_)return false;auto id=std::stoul(number);
        for(const auto& title:source_->titles)if(title.playlist==id)title_=&title;
        if(!title_)return false;disc_=std::make_unique<Disc>(source_);
        return disc_->bd!=nullptr;
    }
};
}
#include "bluray_menu.inc"
std::unique_ptr<DiscStream> makeBlurayStream() { return std::make_unique<BlurayIO>(); }
extern "C" JNIEXPORT jstring JNICALL Java_com_stellar_player_disc_BlurayNative_register(JNIEnv* e,jobject,jstring token,jobject reader,jobjectArray names,jlongArray sizes,jboolean iso) {
    e->GetJavaVM(&jvm);
    bd_set_debug_mask(0); // No paths or disc metadata in global native logs.
    auto s=std::make_shared<Source>();s->iso=iso;s->reader=e->NewGlobalRef(reader);
    auto cls=e->GetObjectClass(reader);s->read=e->GetMethodID(cls,"readAt","(Ljava/lang/String;JI)[B");s->cancel=e->GetMethodID(cls,"cancelRead","()V");e->DeleteLocalRef(cls);
    int count=e->GetArrayLength(names);if(count!=e->GetArrayLength(sizes)||count>20000)return e->NewStringUTF("{\"error\":\"files\"}");
    std::vector<jlong> lengths(count);e->GetLongArrayRegion(sizes,0,count,lengths.data());
    for(int i=0;i<count;i++){auto name=static_cast<jstring>(e->GetObjectArrayElement(names,i));s->files[utf(e,name)]=lengths[i];e->DeleteLocalRef(name);}
    Disc disc(s);if(!disc.bd)return e->NewStringUTF("{\"error\":\"not_bluray\"}");
    const auto* info=bd_get_disc_info(disc.bd);
    if(!info || !info->bluray_detected)return e->NewStringUTF("{\"error\":\"not_bluray\"}");
    if(info->aacs_detected || info->bdplus_detected)return e->NewStringUTF("{\"error\":\"encrypted\"}");
    // The matching pinned libbluray navigation layer provides the exact CLPI packet bounds.
    // Public bd_seek() rounds to access units and is unsuitable for a byte-seek MediaIO.
    fs_access fs{s.get(), nullptr, iso?nullptr:dirOpen, iso?nullptr:fileOpen};
    if(iso)fs.read_blocks=[](void* p,void* out,int lba,int count)->int {
        if(lba<0 || count<0 || count>1024)return -1;
        int n=static_cast<Source*>(p)->readAt("",int64_t(lba)*2048,static_cast<uint8_t*>(out),count*2048);return n<0?-1:n/2048;};
    BD_ENC_INFO encryption{};
    auto navDisc=std::unique_ptr<BD_DISC,void(*)(BD_DISC*)>(disc_open(nullptr,&fs,&encryption,nullptr,nullptr,nullptr,nullptr),[](BD_DISC* p){disc_close(&p);});
    if(!navDisc)return e->NewStringUTF("{\"error\":\"structure\"}");
    unsigned titles=bd_get_titles(disc.bd,TITLES_RELEVANT,0);if(titles>4096)return e->NewStringUTF("{\"error\":\"titles\"}");
    std::ostringstream json;json<<"{\"titles\":[";
    for(unsigned i=0;i<titles;i++) {
        auto* t=bd_get_title_info(disc.bd,i,0);if(!t)continue;
        char playlist[12];std::snprintf(playlist,sizeof(playlist),"%05u.mpls",t->playlist);
        auto nav=std::unique_ptr<NAV_TITLE,void(*)(NAV_TITLE*)>(nav_title_open(navDisc.get(),playlist,0),[](NAV_TITLE* p){nav_title_close(&p);});
        if(!nav || nav->clip_list.count!=t->clip_count){bd_free_title_info(t);continue;}
        Title title{t->playlist,static_cast<int64_t>(t->duration/90)};bool valid=t->clip_count>0 && t->clip_count<=4096 && t->duration>0;
        for(unsigned c=0;c<t->clip_count && valid;c++){const auto& clip=t->clips[c];
            valid=clip.pkt_count>0 && clip.out_time>clip.in_time && clip.still_mode==0 && clip.video_stream_count>0;
            auto bytes=int64_t(clip.pkt_count)*Packet;
            title.clips.push_back({title.bytes,bytes,static_cast<int64_t>(clip.start_time)-static_cast<int64_t>(clip.in_time),static_cast<int64_t>(clip.start_time/90),static_cast<int64_t>(clip.in_time/90),static_cast<int64_t>(clip.out_time/90),int64_t(nav->clip_list.clip[c].start_pkt)*Packet,clip.clip_id});title.bytes+=bytes;
        }
        for(unsigned c=0;c<t->chapter_count;c++)if(t->chapters[c].start<t->duration)title.chapters.push_back(t->chapters[c].start/90);
        bd_free_title_info(t);if(!valid)continue;
        if(!s->titles.empty())json<<',';
        json<<"{\"number\":"<<title.playlist<<",\"duration\":"<<title.duration<<",\"bytes\":"<<title.bytes<<",\"chapters\":[";
        for(size_t c=0;c<title.chapters.size();c++){if(c)json<<',';json<<title.chapters[c];}json<<"],\"clips\":[";
        for(size_t c=0;c<title.clips.size();c++){if(c)json<<',';const auto& clip=title.clips[c];json<<"{\"id\":\""<<clip.id<<"\",\"start\":"<<clip.startTime<<",\"in\":"<<clip.inTime<<",\"out\":"<<clip.outTime<<",\"offset\":"<<clip.start<<",\"bytes\":"<<clip.bytes<<'}';}
        json<<"]}";s->titles.push_back(std::move(title));
    }
    json<<"]}";if(s->titles.empty())return e->NewStringUTF("{\"error\":\"no_titles\"}");
    {std::lock_guard lock(registryMutex);sources[utf(e,token)]=s;}
    return e->NewStringUTF(json.str().c_str());
}
extern "C" JNIEXPORT void JNICALL Java_com_stellar_player_disc_BlurayNative_remove(JNIEnv* e,jobject,jstring token){
    std::shared_ptr<Source> s;{std::lock_guard lock(registryMutex);auto i=sources.find(utf(e,token));if(i==sources.end())return;s=i->second;sources.erase(i);}
    s->closed=true;s->abortRead();
}
