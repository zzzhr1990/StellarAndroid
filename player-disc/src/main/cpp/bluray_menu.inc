namespace {
class BlurayMenu final:public DiscMenu {
    std::shared_ptr<Source> source;
    std::unique_ptr<Disc> disc;
    BLURAY_TITLE_INFO* playlist=nullptr;
    int clip=0,playlistId=-1;
    int64_t delta=0,origin=0;
    bool hasBytes=false;
    bool subtitleEnabled=false;
    int subtitleStream=-1;
    std::vector<uint8_t> indices;
    std::vector<uint32_t> drawing;
    uint32_t palette[256]{};
    void overlay(const BD_OVERLAY* o) {
        if(!o){clearOverlay();indices.clear();drawing.clear();return;}
        if(o->plane!=BD_OVERLAY_IG)return;
        switch(o->cmd) {
            case BD_OVERLAY_INIT:
                if(o->w>3840||o->h>2160){error=1;return;}
                width=o->w;height=o->h;aspect=double(width)/std::max(height,1);
                indices.assign(width*height,255);drawing.assign(width*height,0);pixels.clear();break;
            case BD_OVERLAY_CLOSE:clearOverlay();indices.clear();drawing.clear();break;
            case BD_OVERLAY_CLEAR:case BD_OVERLAY_HIDE:
                std::fill(indices.begin(),indices.end(),255);std::fill(drawing.begin(),drawing.end(),0);
                if(o->cmd==BD_OVERLAY_HIDE){pixels=drawing;++revision;}break;
            case BD_OVERLAY_DRAW: {
                if(o->palette)for(int i=0;i<256;i++){auto& c=o->palette[i];palette[i]=i==255?0:discColor(c.Y,c.Cr,c.Cb,c.T,height>576);}
                auto* r=o->img;
                if(r)for(int y=0;y<o->h;y++) {
                    int x=0;
                    while(x<o->w && r->len){int run=std::min<int>(r->len,o->w-x);
                        for(int n=0;n<run;n++)if(o->x+x+n<width && o->y+y<height)
                            indices[(o->y+y)*width+o->x+x+n]=r->color&255;
                        x+=run;r++;
                    }
                    if(!r->len)r++;
                }
                for(size_t i=0;i<indices.size();i++)drawing[i]=palette[indices[i]];
                break;
            }
            case BD_OVERLAY_WIPE:
                for(int y=o->y;y<std::min<int>(height,o->y+o->h);y++)for(int x=o->x;x<std::min<int>(width,o->x+o->w);x++)
                    {indices[y*width+x]=255;drawing[y*width+x]=0;}break;
            case BD_OVERLAY_FLUSH:pixels=drawing;++revision;break;
        }
    }
    void event(const BD_EVENT& e) {
        switch(e.event) {
            case BD_EVENT_ERROR:error=e.param==BD_ERROR_BDJ?2:1;break;
            case BD_EVENT_ENCRYPTED:error=1;break;
            case BD_EVENT_TITLE:title=e.param;break;
            case BD_EVENT_PLAYLIST:
                if(playlist)bd_free_title_info(playlist);
                playlist=bd_get_playlist_info(disc->bd,e.param,0);playlistId=e.param;clip=0;
                if(hasBytes){boundary=true;}break;
            case BD_EVENT_PLAYITEM:clip=e.param;break;
            case BD_EVENT_MENU:interactive=e.param!=0;break;
            case BD_EVENT_STILL_TIME:still=e.param?e.param:255;break;
            case BD_EVENT_AUDIO_STREAM:audio=e.param>0&&e.param<255?int(e.param)-1:-1;break;
            case BD_EVENT_PG_TEXTST_STREAM:subtitleStream=e.param>0&&e.param<4095?int(e.param)-1:-1;subtitle=subtitleEnabled?subtitleStream:-1;break;
            case BD_EVENT_PG_TEXTST:subtitleEnabled=e.param!=0;subtitle=subtitleEnabled?subtitleStream:-1;break;
            case BD_EVENT_END_OF_TITLE:break;
            default:break;
        }
        if(playlist && clip>=0 && clip<int(playlist->clip_count)) {
            auto& c=playlist->clips[clip];delta=int64_t(c.start_time)-int64_t(c.in_time)+90000;
        }
    }
public:
    explicit BlurayMenu(std::shared_ptr<Source> s):source(std::move(s)){}
    ~BlurayMenu()override{
        if(playlist)bd_free_title_info(playlist);
        // bd_close invokes the overlay callback; keep its vectors alive until
        // that callback has completed (members otherwise die in reverse order).
        disc.reset();
    }
    bool start()override {
        disc=std::make_unique<Disc>(source);if(!disc->bd)return false;
        const auto* info=bd_get_disc_info(disc->bd);
        if(!info||!info->top_menu||info->top_menu->bdj)return false;
        bd_register_overlay_proc(disc->bd,this,[](void* p,const BD_OVERLAY* o){static_cast<BlurayMenu*>(p)->overlay(o);});
        bd_set_player_setting(disc->bd,BLURAY_PLAYER_SETTING_DECODE_PG,0);
        bd_play(disc->bd);
        return bd_play_title(disc->bd,BLURAY_TITLE_TOP_MENU)!=0;
    }
    void cancelRead()override{source->abortRead();}
    void tick()override {
        selected=bdpriv_reg_read(disc->bd,1,10);
    }
    void resume()override{if(still>=0)bd_read_skip_still(disc->bd);hasBytes=false;}
    bool input(int key,int x,int y)override {
        int code=key==0?BD_VK_UP:key==1?BD_VK_DOWN:key==2?BD_VK_LEFT:key==3?BD_VK_RIGHT:
            key==4?BD_VK_ENTER:key==5?BD_VK_ROOT_MENU:key==7?BD_VK_MOUSE_ACTIVATE:BD_VK_POPUP;
        if(key==7 && !bd_mouse_select(disc->bd,-1,x,y))return false;
        int result=bd_user_input(disc->bd,-1,code);
        if(result<=0)return false;
        // Directional input can also auto-activate. libbluray emits a playlist or
        // seek event for actual navigation; drain it before replacing the decoder.
        BD_EVENT e{};bool jump=key==5;
        for(int i=0;i<128;i++) {
            bd_read_ext(disc->bd,nullptr,0,&e);if(e.event==BD_EVENT_NONE)break;
            if(e.event==BD_EVENT_PLAYLIST||e.event==BD_EVENT_SEEK||e.event==BD_EVENT_DISCONTINUITY)jump=true;
            event(e);
        }
        if(jump){restart();hasBytes=false;}
        return true;
    }
    int next(std::vector<uint8_t>& bytes)override {
        for(int loops=0;loops<4096 && !closed;loops++) {
            auto before=bd_tell_time(disc->bd);
            bytes.resize(192*32);BD_EVENT e{};int n=bd_read_ext(disc->bd,bytes.data(),bytes.size(),&e);
            event(e);
            if(error)return -1;
            if(boundary||still>=0){bytes.clear();return 0;}
            if(n<0){error=1;return -1;}
            if(n>0){bytes.resize(n);
                // Chapters can begin far into a playlist. Each decoder epoch
                // starts at zero so its bounded read-ahead uses the same clock.
                if(!hasBytes)origin=before;
                for(int p=0;p+192<=n;p+=192)if(!normalize(bytes.data()+p,delta-origin)){error=1;return -1;}
                readTime=(int64_t(bd_tell_time(disc->bd))-origin)/90;hasBytes=true;return n;
            }
        }
        error=1;return -1;
    }
};
}
std::shared_ptr<DiscMenu> makeBlurayMenu(const std::string& token) {
    std::lock_guard lock(registryMutex);auto i=sources.find(token);
    return i==sources.end()?nullptr:std::make_shared<BlurayMenu>(i->second);
}
