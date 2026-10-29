#include <jni.h>
#include "disc_stream.h"
#include "disc_menu.h"
#include "dvd_spu.h"
extern "C" int stellar_dvdnav_open(dvdnav_t**,dvd_reader_t*,const dvdnav_logger_cb*);
extern "C" {
#include <dvdread/dvd_reader.h>
#include <dvdread/ifo_read.h>
#include <dvdread/nav_read.h>
}
#include <algorithm>
#include <atomic>
#include <cctype>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>
#include <vector>
#include <limits>
#include <iomanip>

namespace {
constexpr int64_t Block = 2048;
JavaVM* jvm = nullptr;
struct Env {
    JNIEnv* env = nullptr; bool attached = false;
    Env() { if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
        attached = jvm->AttachCurrentThread(&env, nullptr) == JNI_OK; }
    ~Env() { if (attached) jvm->DetachCurrentThread(); }
};
std::string utf(JNIEnv* e, jstring v) { const char* s = e->GetStringUTFChars(v, nullptr); std::string r(s); e->ReleaseStringUTFChars(v,s); return r; }
std::string upper(std::string s) { for (char& c : s) c = std::toupper(static_cast<unsigned char>(c)); return s; }
struct Cell { uint32_t sector; int64_t blocks, startByte, startTime, duration; int vob, cell; bool interleaved; };
struct Title { int number, vts; int64_t duration = 0, bytes = 0; std::vector<Cell> cells; std::vector<int64_t> chapters;
    std::shared_ptr<std::vector<uint32_t>> vobus; std::string palette; };
struct Source {
    jobject reader = nullptr; jmethodID read = nullptr, cancel = nullptr; bool iso = false;
    std::map<std::string,int64_t> files; std::vector<Title> titles;
    std::atomic<bool> cancelled{false};
    ~Source() { if (reader) { Env e; if (e.env) e.env->DeleteGlobalRef(reader); } }
    void abortRead() { Env e; if(e.env && cancel) { e.env->CallVoidMethod(reader,cancel); if(e.env->ExceptionCheck())e.env->ExceptionClear(); } }
    int readAt(const std::string& name, int64_t offset, uint8_t* out, int n) {
        if (cancelled || offset < 0 || n < 0) return -1;
        Env e; if (!e.env) return -1;
        jstring path = e.env->NewStringUTF(name.c_str());
        auto bytes = static_cast<jbyteArray>(e.env->CallObjectMethod(reader, read, path, offset, n));
        e.env->DeleteLocalRef(path);
        if (e.env->ExceptionCheck()) { e.env->ExceptionClear(); return -1; }
        if (!bytes) return -1;
        const int count = e.env->GetArrayLength(bytes);
        if (count <= n) e.env->GetByteArrayRegion(bytes, 0, count, reinterpret_cast<jbyte*>(out));
        e.env->DeleteLocalRef(bytes);
        return count <= n ? count : -1;
    }
};
std::mutex registryMutex;
std::map<std::string,std::shared_ptr<Source>> sources;
struct File { Source* source; std::string name; int64_t position = 0; };
struct Directory { std::vector<std::string> names; size_t index = 0; };
std::string basename(const char* path) { std::string s(path); return upper(s.substr(s.find_last_of('/')+1)); }
void fsClose(dvd_reader_filesystem_h* fs) { delete fs; }
int fsStat(dvd_reader_filesystem_h* fs, const char* path, dvdstat_t* st) {
    std::string p(path);
    if (p == "/" || p == "/VIDEO_TS" || p == "/video_ts") { st->st_mode = DVD_S_IFDIR; st->size = 0; return 0; }
    auto* s = static_cast<Source*>(fs->internal); const auto i = s->files.find(basename(path));
    if (i == s->files.end()) return -1;
    st->st_mode = DVD_S_IFREG; st->size = i->second; return 0;
}
void* dirOpen(dvd_reader_filesystem_h* fs, const char* path) {
    dvdstat_t st{}; if (fsStat(fs,path,&st) || st.st_mode != DVD_S_IFDIR) return nullptr;
    auto* d = new Directory;
    for (const auto& f : static_cast<Source*>(fs->internal)->files) d->names.push_back(f.first);
    return d;
}
int dirRead(void* p, dvd_dirent_t* e) { auto* d=static_cast<Directory*>(p); if(d->index==d->names.size()) return 1;
    std::strncpy(e->d_name,d->names[d->index++].c_str(),DVDREAD_NAME_MAX); e->d_name[DVDREAD_NAME_MAX]=0; return 0; }
void dirClose(void* p) { delete static_cast<Directory*>(p); }
void* fileOpen(dvd_reader_filesystem_h* fs,const char* path) {
    auto* s=static_cast<Source*>(fs->internal); const auto name=basename(path);
    if (!s->files.count(name)) return nullptr;
    return new File{s,name};
}
ssize_t fileRead(void* p,char* buf,size_t size) { auto* f=static_cast<File*>(p);
    if(size>INT32_MAX) return -1;
    const int n=f->source->readAt(f->name,f->position,reinterpret_cast<uint8_t*>(buf),size);
    if(n>0) f->position+=n; return n; }
off64_t fileSeek(void* p,off64_t offset,int from) { auto* f=static_cast<File*>(p);
    int64_t base=from==SEEK_SET?0:from==SEEK_CUR?f->position:from==SEEK_END?f->source->files.at(f->name):-1;
    if(base<0 || (offset>0 && base>INT64_MAX-offset) || (offset<0 && offset < -base)) return -1;
    f->position=base+offset; return f->position; }
int fileClose(void* p) { delete static_cast<File*>(p); return 0; }
void quietLog(void*,dvd_logger_level_t,const char*,va_list) {}
const dvd_logger_cb logger{quietLog};
struct Disc {
    std::shared_ptr<Source> source; dvd_reader_t* dvd=nullptr; int64_t pos=0;
    explicit Disc(std::shared_ptr<Source> s):source(std::move(s)) {
        if(source->iso) {
            static dvd_reader_stream_cb cb{
                [](void* p,uint64_t offset)->int { if(offset>INT64_MAX) return -1; static_cast<Disc*>(p)->pos=offset; return 0; },
                [](void* p,void* buf,int size)->int { auto* d=static_cast<Disc*>(p); int n=d->source->readAt("",d->pos,static_cast<uint8_t*>(buf),size); if(n>0)d->pos+=n; return n; },nullptr};
            dvd=DVDOpenStream2(this,&logger,&cb);
        } else {
            auto* fs=new dvd_reader_filesystem_h{source.get(),fsClose,fsStat,dirOpen,dirRead,dirClose,fileOpen,fileRead,fileSeek,fileClose};
            dvd=DVDOpenFiles(this,&logger,"/VIDEO_TS",fs);
            if(!dvd) delete fs;
        }
    }
    ~Disc() { if(dvd) DVDClose(dvd); }
};
int bcd(uint8_t n) { return (n>>4)*10+(n&15); }
int64_t ticks(const dvd_time_t& t) {
    int fps=t.frame_u>>6; int frame=bcd(t.frame_u&63);
    return (bcd(t.hour)*3600LL+bcd(t.minute)*60+bcd(t.second))*90000 + (fps==1?frame*3600LL:fps==3?frame*3003LL:0);
}
bool enumerate(Disc& disc) {
    if(!disc.dvd) return false;
    ifo_handle_t* root=ifoOpen(disc.dvd,0); if(!root) return false;
    if(root->tt_srpt) for(int i=0;i<root->tt_srpt->nr_of_srpts;i++) {
        const auto& entry=root->tt_srpt->title[i];
        // Play the first angle of a sequential PGC. Menu/VM execution is outside this reader.
        if(entry.title_set_nr<1 || entry.vts_ttn<1) continue;
        ifo_handle_t* vts=ifoOpen(disc.dvd,entry.title_set_nr); if(!vts) continue;
        Title title{i+1,entry.title_set_nr}; bool valid=false;
        if(vts->vts_ptt_srpt && entry.vts_ttn<=vts->vts_ptt_srpt->nr_of_srpts && vts->vts_pgcit) {
            const auto& parts=vts->vts_ptt_srpt->title[entry.vts_ttn-1];
            if(parts.nr_of_ptts>0) {
                int pgcn=parts.ptt[0].pgcn;
                if(pgcn>0 && pgcn<=vts->vts_pgcit->nr_of_pgci_srp) {
                    pgc_t* pgc=vts->vts_pgcit->pgci_srp[pgcn-1].pgc;
                    valid=pgc && pgc->cell_playback && pgc->cell_position && pgc->program_map && !pgc->pg_playback_mode && !pgc->still_time;
                    if(valid) {
                        std::ostringstream rgb;rgb<<std::hex<<std::setfill('0');
                        auto clamp=[](double x){return std::clamp(int(x+0.5),0,255);};
                        for(int p=0;p<16;p++) {
                            uint32_t yuv=pgc->palette[p];double y=((yuv>>16)&255)-16.0, cr=((yuv>>8)&255)-128.0, cb=(yuv&255)-128.0;
                            int r=clamp(1.164383*y+1.596027*cr),g=clamp(1.164383*y-0.391762*cb-0.812968*cr),b=clamp(1.164383*y+2.017232*cb);
                            if(p)rgb<<',';rgb<<std::setw(6)<<((r<<16)|(g<<8)|b);
                        }
                        title.palette=rgb.str();
                    }
                    for(int p=0;valid && p<parts.nr_of_ptts;p++)
                        if(parts.ptt[p].pgcn!=pgcn || parts.ptt[p].pgn<1 || parts.ptt[p].pgn>pgc->nr_of_programs ||
                            pgc->program_map[parts.ptt[p].pgn-1]<1 || pgc->program_map[parts.ptt[p].pgn-1]>pgc->nr_of_cells ||
                            (p>0 && parts.ptt[p].pgn<parts.ptt[p-1].pgn)) valid=false;
                    int first=valid?pgc->program_map[parts.ptt[0].pgn-1]-1:0;
                    if(first<0 || (valid && first>=pgc->nr_of_cells))valid=false;
                    for(int c=first;valid && c<pgc->nr_of_cells;c++) {
                        const auto& cell=pgc->cell_playback[c];
                        if(cell.block_type==BLOCK_TYPE_ANGLE_BLOCK && cell.block_mode!=BLOCK_MODE_FIRST_CELL) continue;
                        if(cell.block_type>BLOCK_TYPE_ANGLE_BLOCK || cell.still_time || cell.playback_mode || cell.cell_cmd_nr || cell.last_sector<cell.first_sector) {valid=false;break;}
                        for(int p=0;p<parts.nr_of_ptts;p++) if(pgc->program_map[parts.ptt[p].pgn-1]-1==c)
                            title.chapters.push_back(title.duration/90);
                        int64_t duration=ticks(cell.playback_time), blocks=1LL+cell.last_sector-cell.first_sector;
                        if(duration<=0 || cell.last_sector>INT32_MAX) {valid=false;break;}
                        const auto& position=pgc->cell_position[c];
                        title.cells.push_back(Cell{cell.first_sector,blocks,title.bytes,title.duration,duration,position.vob_id_nr,position.cell_nr,cell.interleaved!=0});
                        title.duration+=duration; title.bytes+=blocks*Block;
                    }
                    if(valid && std::any_of(title.cells.begin(),title.cells.end(),[](const Cell& c){return c.interleaved;})) {
                        auto* map=vts->vts_vobu_admap;
                        if(!map || map->last_byte<3 || map->last_byte>4*1024*1024) valid=false;
                        else {
                            title.vobus=std::make_shared<std::vector<uint32_t>>(map->vobu_start_sectors,map->vobu_start_sectors+(map->last_byte-3)/4);
                            if(title.vobus->empty() || !std::is_sorted(title.vobus->begin(),title.vobus->end())) valid=false;
                        }
                    }
                }
            }
        }
        ifoClose(vts);
        if(valid && title.bytes>0 && !title.chapters.empty()) disc.source->titles.push_back(std::move(title));
    }
    ifoClose(root); return !disc.source->titles.empty();
}

uint64_t readPts(const uint8_t* p) { return (uint64_t(p[0]&14)<<29)|(uint64_t(p[1])<<22)|(uint64_t(p[2]&254)<<14)|(uint64_t(p[3])<<7)|(p[4]>>1); }
void shiftPts(uint8_t* p,int64_t offset) {
    uint64_t t=(readPts(p)+offset)&((1ULL<<33)-1);
    p[0]=(p[0]&0xf1)|((t>>29)&14);p[1]=t>>22;p[2]=((t>>14)&254)|1;p[3]=t>>7;p[4]=((t<<1)&254)|1;
}
bool normalize(uint8_t* p,int64_t delta) {
    // A DVD sector is one complete MPEG-2 pack, with bounded PES packets and optional padding.
    if(p[0]||p[1]||p[2]!=1||p[3]!=0xba||(p[4]&0xc0)!=0x40) return false;
    uint64_t scr=(uint64_t((p[4]>>3)&7)<<30)|(uint64_t(p[4]&3)<<28)|(uint64_t(p[5])<<20)|
        (uint64_t(p[6]>>3)<<15)|(uint64_t(p[6]&3)<<13)|(uint64_t(p[7])<<5)|(p[8]>>3);
    scr=(scr+delta)&((1ULL<<33)-1);
    p[4]=(p[4]&0xc4)|((scr>>27)&0x38)|((scr>>28)&3);p[5]=scr>>20;
    p[6]=(p[6]&4)|((scr>>12)&0xf8)|((scr>>13)&3);p[7]=scr>>5;p[8]=(p[8]&7)|((scr<<3)&0xf8);
    for(size_t i=14+(p[13]&7);i+6<=Block;) {
        if(p[i]||p[i+1]||p[i+2]!=1) break;
        const auto id=p[i+3]; if(id==0xb9) break;
        size_t length=(p[i+4]<<8)|p[i+5], end=i+6+length;
        if(end>Block) return false;
        if(id==0xbd || (id>=0xc0 && id<=0xef)) {
            if(length<3 || (p[i+6]&0xc0)!=0x80 || (p[i+6]&0x30)) return false; // encrypted/invalid PES
            int flags=p[i+7]>>6;
            if(flags==2 || flags==3) { if(length<8 || p[i+8]<5) return false; shiftPts(p+i+9,delta); }
            if(flags==3) { if(length<13 || p[i+8]<10) return false; shiftPts(p+i+14,delta); }
        }
        i=end;
    }
    return true;
}
class DvdIO final : public DiscStream {
    std::shared_ptr<Source> source_, abortSource_; std::unique_ptr<Disc> disc_; dvd_file_t* file_=nullptr; const Title* title_=nullptr;
    int64_t pos_=0, cacheStart_=-1; std::vector<uint8_t> cache_; std::map<size_t,int64_t> offsets_;
    uint32_t navSector_=UINT32_MAX; int navVob_=0,navCell_=0;
    std::atomic<bool> aborted_{false};
    bool offset(size_t index,int64_t& out) {
        if(auto it=offsets_.find(index);it!=offsets_.end()){out=it->second;return true;}
        uint8_t nav[2048]; const auto& cell=title_->cells[index];
        if(DVDReadBlocks(file_,cell.sector,1,nav)!=1) return false;
        // PCI starts at byte 45, as specified by DVD-Video's NAV pack layout.
        if(nav[38]!=0 || nav[39]!=0 || nav[40]!=1 || nav[41]!=0xbf || nav[44]!=0) return false;
        pci_t pci{}; navRead_PCI(&pci,nav+45);
        out=cell.startTime+90000-pci.pci_gi.vobu_s_ptm; offsets_[index]=out; return true;
    }
    bool angleRun(const Cell& cell,uint32_t sector,int64_t& blocks,bool& selected) {
        selected=true;
        if(!cell.interleaved) return true;
        const auto& map=*title_->vobus;
        auto next=std::upper_bound(map.begin(),map.end(),sector);
        if(next==map.begin()) return false;
        if(next!=map.end()) blocks=std::min<int64_t>(blocks,*next-sector);
        uint32_t start=*std::prev(next);
        if(navSector_!=start) {
            uint8_t nav[2048];
            if(DVDReadBlocks(file_,start,1,nav)!=1 || nav[1024] || nav[1025] || nav[1026]!=1 || nav[1027]!=0xbf || nav[1030]!=1) return false;
            dsi_t dsi{};navRead_DSI(&dsi,nav+DSI_START_BYTE);
            if(dsi.dsi_gi.nv_pck_lbn!=start) return false;
            navSector_=start;navVob_=dsi.dsi_gi.vobu_vob_idn;navCell_=dsi.dsi_gi.vobu_c_idn;
        }
        selected=navVob_==cell.vob && navCell_==cell.cell;
        return true;
    }
public:
    ~DvdIO() override {if(file_)DVDCloseFile(file_);}
    bool isSeekable() const override{return file_!=nullptr;}
    int64_t position() const override{return pos_;}
    int64_t size() const override{return title_?title_->bytes:0;}
    bool abort() override{aborted_=true;if(auto source=std::atomic_load(&abortSource_))source->abortRead();return true;}
    bool seek(int64_t n,int from) override {
        int64_t base=from==SEEK_SET?0:from==SEEK_CUR?pos_:from==SEEK_END?size():-1;
        if(base<0 || (n>0&&base>INT64_MAX-n)||(n<0&&n < -base)||base+n>size())return false;
        pos_=base+n;return true;
    }
    int64_t read(uint8_t* data,int64_t maximum) override {
        if(!file_||aborted_||source_->cancelled||maximum<0)return -1;
        int64_t count=0;
        while(count<maximum && pos_<size()) {
            if(aborted_||source_->cancelled)return -1;
            if(pos_<cacheStart_ || pos_>=cacheStart_+static_cast<int64_t>(cache_.size())) {
                auto it=std::upper_bound(title_->cells.begin(),title_->cells.end(),pos_,[](int64_t p,const Cell& c){return p<c.startByte;});
                if(it==title_->cells.begin())return -1; --it;
                int64_t sector=(pos_-it->startByte)/Block, blocks=std::min<int64_t>(64,it->blocks-sector), delta;
                if(!offset(it-title_->cells.begin(),delta))return -1;
                bool selected;
                if(!angleRun(*it,it->sector+sector,blocks,selected))return -1;
                cacheStart_=it->startByte+sector*Block;cache_.resize(blocks*Block);
                if(selected) {
                    if(DVDReadBlocks(file_,it->sector+sector,blocks,cache_.data())!=blocks){cache_.clear();return -1;}
                    for(int i=0;i<blocks;i++)if(!normalize(cache_.data()+i*Block,delta)){cache_.clear();return -1;}
                } else {
                    // Preserve byte offsets for demuxer seeks while hiding other interleaved angles.
                    // Each unused sector becomes a complete padding packet, with no media timestamps.
                    for(int i=0;i<blocks;i++) {auto* p=cache_.data()+i*Block;std::memset(p,0xff,Block);
                        p[0]=0;p[1]=0;p[2]=1;p[3]=0xbe;p[4]=0x07;p[5]=0xfa;}
                }
            }
            int64_t n=std::min(maximum-count,cacheStart_+static_cast<int64_t>(cache_.size())-pos_);
            std::memcpy(data+count,cache_.data()+(pos_-cacheStart_),n);pos_+=n;count+=n;
        }
        return count;
    }
    bool open(const std::string& url) override {
        if(file_){DVDCloseFile(file_);file_=nullptr;}disc_.reset();title_=nullptr;cache_.clear();cacheStart_=-1;offsets_.clear();pos_=0;navSector_=UINT32_MAX;aborted_=false;
        auto slash=url.find_last_of('/'); if(slash==std::string::npos)return false;
        std::string token=url.substr(0,slash), number=url.substr(slash+1);
        if(number.empty()||number.size()>3||number.find_first_not_of("0123456789")!=std::string::npos)return false;
        {std::lock_guard lock(registryMutex);auto it=sources.find(token);if(it==sources.end())return false;source_=it->second;}
        std::atomic_store(&abortSource_,source_);
        if(source_->cancelled || aborted_)return false;
        int id=std::stoi(number);for(const auto& t:source_->titles)if(t.number==id)title_=&t;
        if(!title_)return false;
        disc_=std::make_unique<Disc>(source_);if(!disc_->dvd)return false;
        file_=DVDOpenFile(disc_->dvd,title_->vts,DVD_READ_TITLE_VOBS);
        if(file_) {auto blocks=DVDFileSize(file_);for(const auto& cell:title_->cells)
            if(cell.sector+cell.blocks>blocks){DVDCloseFile(file_);file_=nullptr;break;}}
        return file_!=nullptr;
    }
};
}
#include "dvd_menu.inc"
std::string stellarDvdPalette(const std::string& url) {
    auto slash=url.find_last_of('/');if(slash==std::string::npos)return {};
    std::lock_guard lock(registryMutex);auto source=sources.find(url.substr(0,slash));
    if(source==sources.end() || source->second->cancelled)return {};
    for(const auto& title:source->second->titles)if(std::to_string(title.number)==url.substr(slash+1))return title.palette;
    return {};
}
std::unique_ptr<DiscStream> makeDvdStream() { return std::make_unique<DvdIO>(); }
extern "C" JNIEXPORT jstring JNICALL Java_com_stellar_player_disc_DvdNative_register(JNIEnv* e,jobject,jstring token,jobject reader,jobjectArray names,jlongArray sizes,jboolean iso) {
    e->GetJavaVM(&jvm);
    auto s=std::make_shared<Source>();s->iso=iso;s->reader=e->NewGlobalRef(reader);
    jclass cls=e->GetObjectClass(reader);s->read=e->GetMethodID(cls,"readAt","(Ljava/lang/String;JI)[B");s->cancel=e->GetMethodID(cls,"cancelRead","()V");e->DeleteLocalRef(cls);
    int n=e->GetArrayLength(names);if(n!=e->GetArrayLength(sizes)||n>1024)return e->NewStringUTF("{\"error\":\"files\"}");
    std::vector<jlong> lengths(n);e->GetLongArrayRegion(sizes,0,n,lengths.data());
    for(int i=0;i<n;i++){auto name=static_cast<jstring>(e->GetObjectArrayElement(names,i));s->files[upper(utf(e,name))]=lengths[i];e->DeleteLocalRef(name);}
    Disc disc(s);
    if(!enumerate(disc))return e->NewStringUTF("{\"error\":\"structure\"}");
    std::ostringstream json;json<<"{\"titles\":[";
    for(size_t i=0;i<s->titles.size();i++){const auto& t=s->titles[i];if(i)json<<',';
        json<<"{\"number\":"<<t.number<<",\"duration\":"<<t.duration/90<<",\"chapters\":[";
        for(size_t j=0;j<t.chapters.size();j++){if(j)json<<',';json<<t.chapters[j];}json<<"]}";}
    json<<"]}";
    {std::lock_guard lock(registryMutex);sources[utf(e,token)]=s;}
    return e->NewStringUTF(json.str().c_str());
}
extern "C" JNIEXPORT void JNICALL Java_com_stellar_player_disc_DvdNative_remove(JNIEnv* e,jobject,jstring token) {
    std::lock_guard lock(registryMutex);auto it=sources.find(utf(e,token));if(it!=sources.end()){it->second->cancelled=true;sources.erase(it);}
}
