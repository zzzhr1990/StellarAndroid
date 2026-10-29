// Shares the protocol-neutral Source and DVDOpenFiles bridge with title playback.
namespace {
class DvdMenu final:public DiscMenu {
    std::shared_ptr<Source> source;
    std::unique_ptr<Disc> disc;
    dvdnav_t* nav=nullptr;
    DvdSpu spu;
    int physicalSpu=-1;
    bool hasBytes=false;
    int64_t delta=0,endTicks=90000;
    void highlight() {
        if(!nav)return;
        auto* pci=dvdnav_get_current_nav_pci(nav);
        int32_t button=0;dvdnav_get_current_highlight(nav,&button);selected=button;
        buttons=pci?pci->hli.hl_gi.btn_ns:0;
        dvdnav_highlight_area_t area{};
        bool valid=pci && button>0 && button<=buttons && dvdnav_get_highlight_area(pci,button,1,&area)==DVDNAV_STATUS_OK;
        if(!spu.indices.empty())spu.draw(*this,valid?&area:nullptr);
    }
    void subpicture(const std::vector<uint8_t>& bytes) {
        // DVD packs are sector-sized; skip pack header and optional stuffing.
        size_t p=14+(bytes[13]&7);
        while(p+6<bytes.size()) {
            if(bytes[p]||bytes[p+1]||bytes[p+2]!=1)break;
            size_t end=p+6+DvdSpu::be(bytes.data()+p+4);if(end>bytes.size())break;
            if(bytes[p+3]==0xbd && p+9<end) {
                size_t start=p+9+bytes[p+8];
                if(start<end && (bytes[start]&0xe0)==0x20) {
                    int stream=bytes[start]&31;
                    if(interactive && (physicalSpu<0 || stream==physicalSpu))
                        if(spu.append(bytes.data()+start+1,end-start-1))highlight();
                }
            }
            p=end;
        }
    }
public:
    explicit DvdMenu(std::shared_ptr<Source> s):source(std::move(s)){}
    ~DvdMenu()override{if(nav)dvdnav_close(nav);}
    bool start()override {
        disc=std::make_unique<Disc>(source);if(!disc->dvd)return false;
        static dvdnav_logger_cb log{[](void*,dvdnav_logger_level_t,const char*,va_list){}};
        auto* reader=disc->dvd;disc->dvd=nullptr;
        if(stellar_dvdnav_open(&nav,reader,&log)!=DVDNAV_STATUS_OK)return false;
        dvdnav_set_readahead_flag(nav,0);dvdnav_set_PGC_positioning_flag(nav,1);
        uint8_t block[2048];int event=0,length=0;
        if(dvdnav_get_next_block(nav,block,&event,&length)!=DVDNAV_STATUS_OK)return false;
        if(dvdnav_menu_call(nav,DVD_MENU_Title)!=DVDNAV_STATUS_OK)dvdnav_menu_call(nav,DVD_MENU_Root);
        return true;
    }
    void cancelRead()override {source->abortRead();}
    void newTimeline()override {endTicks=90000;hasBytes=false;}
    std::string palette()const override {
        std::ostringstream out;out<<std::hex<<std::setfill('0');
        for(int i=0;i<16;i++){if(i)out<<',';auto c=spu.clut[i];out<<std::setw(6)<<(discColor((c>>16)&255,(c>>8)&255,c&255,0)&0xffffff);}return out.str();
    }
    void resume()override {if(waiting)dvdnav_wait_skip(nav);else {dvdnav_still_skip(nav);endTicks=90000;}}
    bool input(int key,int x,int y)override {
        auto* pci=dvdnav_get_current_nav_pci(nav);int result=0;bool jump=false;
        switch(key) {
            case 0:result=dvdnav_upper_button_select(nav,pci);break;
            case 1:result=dvdnav_lower_button_select(nav,pci);break;
            case 2:result=dvdnav_left_button_select(nav,pci);break;
            case 3:result=dvdnav_right_button_select(nav,pci);break;
            case 4:result=dvdnav_button_activate(nav,pci);jump=true;break;
            case 5:result=dvdnav_menu_call(nav,DVD_MENU_Title);if(!result)result=dvdnav_menu_call(nav,DVD_MENU_Root);jump=true;break;
            case 6:result=dvdnav_menu_call(nav,DVD_MENU_Escape);jump=true;break;
            case 7:result=dvdnav_mouse_activate(nav,pci,x,y);jump=true;break;
            case 8:result=dvdnav_menu_call(nav,DVD_MENU_Root);jump=true;break;
        }
        if(result && jump){restart();newTimeline();spu.clear();pixels.clear();buttons=selected=0;++revision;
            int n=next(prefetched);
            for(int i=0;n==0 && waiting && i<16;i++){resume();waiting=false;n=next(prefetched);}
        }
        else if(result)highlight();
        return result==DVDNAV_STATUS_OK;
    }
    int next(std::vector<uint8_t>& bytes)override {
        for(int iterations=0;iterations<4096 && !closed;iterations++) {
            uint8_t block[2048];int event=0,length=0;
            if(dvdnav_get_next_block(nav,block,&event,&length)!=DVDNAV_STATUS_OK){error=1;return -1;}
            switch(event) {
                case DVDNAV_BLOCK_OK:
                    bytes.assign(block,block+length);subpicture(bytes);
                    if(!normalize(bytes.data(),delta)){error=1;return -1;}hasBytes=true;return length;
                case DVDNAV_NAV_PACKET: {
                    auto* pci=dvdnav_get_current_nav_pci(nav);
                    delta=endTicks-pci->pci_gi.vobu_s_ptm;
                    endTicks+=uint32_t(pci->pci_gi.vobu_e_ptm-pci->pci_gi.vobu_s_ptm);
                    readTime=(endTicks-90000)/90;
                    highlight();break;
                }
                case DVDNAV_VTS_CHANGE: {
                    uint32_t w=720,h=576;dvdnav_get_video_resolution(nav,&w,&h);
                    clearOverlay();width=w;height=h;aspect=dvdnav_get_video_aspect(nav)==3?16.0/9:4.0/3;
                    interactive=!dvdnav_is_domain_vts(nav);spu.clear();break;
                }
                case DVDNAV_CELL_CHANGE: {
                    int32_t part=0;dvdnav_current_title_info(nav,&title,&part);interactive=!dvdnav_is_domain_vts(nav);break;
                }
                case DVDNAV_SPU_CLUT_CHANGE:std::memcpy(spu.clut.data(),block,64);highlight();break;
                case DVDNAV_SPU_STREAM_CHANGE: {
                    auto* s=reinterpret_cast<dvdnav_spu_stream_change_event_t*>(block);physicalSpu=s->physical_wide&31;subtitle=dvdnav_get_active_spu_stream(nav);spu.clear();break;
                }
                case DVDNAV_AUDIO_STREAM_CHANGE:audio=reinterpret_cast<dvdnav_audio_stream_change_event_t*>(block)->physical;break;
                case DVDNAV_HIGHLIGHT:highlight();break;
                case DVDNAV_STILL_FRAME:still=reinterpret_cast<dvdnav_still_event_t*>(block)->length;return 0;
                case DVDNAV_WAIT:waiting=true;return 0;
                case DVDNAV_STOP:stopped=true;return 0;
                case DVDNAV_HOP_CHANNEL:
                    if(hasBytes){restart();newTimeline();bytes.clear();return 0;}break;
                default:break;
            }
        }
        error=1;return -1;
    }
};
}
std::shared_ptr<DiscMenu> makeDvdMenu(const std::string& token) {
    std::lock_guard lock(registryMutex);auto i=sources.find(token);
    return i==sources.end()?nullptr:std::make_shared<DvdMenu>(i->second);
}
