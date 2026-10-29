#pragma once
#include "disc_menu.h"
#include <dvdnav/dvdnav.h>
#include <array>
#include <cstring>

// DVD subpicture units carry 2-bit RLE pixels and timed palette/rectangle commands.
// Keep only the current menu unit; the DVD PCI supplies its selected-button palette.
struct DvdSpu {
    std::vector<uint8_t> unit, indices;
    std::array<uint32_t,16> clut{};
    int x=0,y=0,w=0,h=0;
    uint16_t colors=0,alpha=0;
    static int be(const uint8_t* p){return p[0]*256+p[1];}
    void clear(){unit.clear();indices.clear();w=h=0;}
    bool append(const uint8_t* p,size_t n) {
        if(unit.size()+n>65536){clear();return false;}
        unit.insert(unit.end(),p,p+n);
        if(unit.size()<4)return false;
        int size=be(unit.data());if(size<4){clear();return false;}if(unit.size()<size)return false;
        unit.resize(size);bool result=decode();unit.clear();if(!result){indices.clear();w=h=0;}return result;
    }
    bool decode() {
        int control=be(unit.data()+2),even=-1,odd=-1;
        if(control<4||control+4>int(unit.size()))return false;
        for(int chain=0;chain<128;chain++) {
            int next=be(unit.data()+control+2),p=control+4;
            while(p<int(unit.size())) {
                int op=unit[p++];if(op==0xff)break;
                int n=op==3||op==4?2:op==5?6:op==6?4:op<=2?0:-1;
                if(n<0||p+n>int(unit.size()))return false;
                if(op==3)colors=be(unit.data()+p);
                if(op==4)alpha=be(unit.data()+p);
                if(op==5){x=(unit[p]<<4)|(unit[p+1]>>4);int right=((unit[p+1]&15)<<8)|unit[p+2];
                    y=(unit[p+3]<<4)|(unit[p+4]>>4);int bottom=((unit[p+4]&15)<<8)|unit[p+5];w=right-x+1;h=bottom-y+1;}
                if(op==6){even=be(unit.data()+p);odd=be(unit.data()+p+2);}
                p+=n;
            }
            if(next==control)break;if(next<4||next+4>int(unit.size()))return false;control=next;
        }
        if(w<=0||w>720||h<=0||h>576||even<4||odd<4)return false;
        indices.assign(w*h,0);
        for(int field=0;field<2;field++) {
            size_t nibble=(field?odd:even)*2;
            auto read=[&]()->int{if(nibble>=unit.size()*2)return -1;auto v=unit[nibble/2];return (nibble++&1)?v&15:v>>4;};
            for(int row=field;row<h;row+=2) {
                int col=0;
                while(col<w){int code=read();if(code<0)return false;
                    for(int threshold:{4,16,64})if(code<threshold){int v=read();if(v<0)return false;code=code*16+v;}
                    int run=code>>2;if(run==0)run=w-col;run=std::min(run,w-col);
                    std::fill_n(indices.begin()+row*w+col,run,code&3);col+=run;
                }
                nibble=(nibble+1)&~size_t(1);
            }
        }
        return true;
    }
    void draw(DiscMenu& menu, const dvdnav_highlight_area_t* highlight) {
        if(menu.width<=0||menu.height<=0)return;
        menu.pixels.assign(menu.width*menu.height,0);
        for(int row=0;row<h;row++)for(int col=0;col<w;col++) {
            int dx=x+col,dy=y+row;if(dx<0||dy<0||dx>=menu.width||dy>=menu.height)continue;
            int color=indices[row*w+col],palette=colors,opacity=alpha;
            if(highlight && dx>=highlight->sx && dx<=highlight->ex && dy>=highlight->sy && dy<=highlight->ey) {
                palette=highlight->palette>>16;opacity=highlight->palette&0xffff;
            }
            auto yuv=clut[(palette>>(color*4))&15];int a=((opacity>>(color*4))&15)*17;
            menu.pixels[dy*menu.width+dx]=discColor((yuv>>16)&255,(yuv>>8)&255,yuv&255,a);
        }
        ++menu.revision;
    }
};
