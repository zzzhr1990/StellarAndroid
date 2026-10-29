#include <jni.h>
#include "remote_reader.h"
#include "smb_reader.h"
#include "nfs_reader.h"
#include <algorithm>
#include <cstdio>
#include <vector>
namespace {
struct File {
    std::string token;
    bool smb, opened = false;
    std::unique_ptr<stellar::RemoteReader> reader;
    ~File() { reader.reset(); if(smb) stellar::removeSmbSource(token); else stellar::removeNfsSource(token); }
};
std::string bytes(JNIEnv* e, jbyteArray a) {
    std::string s(e->GetArrayLength(a), '\0');
    e->GetByteArrayRegion(a,0,s.size(),reinterpret_cast<jbyte*>(s.data())); return s;
}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_stellar_player_disc_NasDiscNative_create(JNIEnv* e,jobject,jobjectArray args,jintArray numbers) {
    std::vector<std::string> a;
    for(int i=0;i<e->GetArrayLength(args);i++) { auto b=static_cast<jbyteArray>(e->GetObjectArrayElement(args,i));a.push_back(bytes(e,b));e->DeleteLocalRef(b); }
    if(a.size()!=8 || e->GetArrayLength(numbers)!=5)return 0;
    jint n[5];e->GetIntArrayRegion(numbers,0,5,n);
    bool smb=n[0]==0;
    if(smb)stellar::addSmbSource(a[0],a[1],a[2],a[3],a[4],a[5],a[6],n[4]!=0);
    else stellar::addNfsSource(a[0],a[1],a[2],a[3],n[0],n[1],n[2],n[3]);
    return reinterpret_cast<jlong>(new File{a[0],smb,false,smb?stellar::makeSmbReader():stellar::makeNfsReader()});
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_stellar_player_disc_NasDiscNative_read(JNIEnv* e,jobject,jlong h,jlong offset,jint size) {
    auto* f=reinterpret_cast<File*>(h);
    if(!f || offset<0 || size<0 || size>2*1024*1024)return nullptr;
    if(!f->opened) { if(!f->reader->open(f->token))return nullptr; f->opened=true; }
    if(!f->reader->seek(offset,SEEK_SET))return nullptr;
    std::vector<uint8_t> data(size);int done=0;
    while(done<size) { auto n=f->reader->read(data.data()+done,size-done);if(n<0)return nullptr;if(n==0)break;done+=n; }
    auto result=e->NewByteArray(done);if(result)e->SetByteArrayRegion(result,0,done,reinterpret_cast<jbyte*>(data.data()));return result;
}
extern "C" JNIEXPORT void JNICALL Java_com_stellar_player_disc_NasDiscNative_cancel(JNIEnv*,jobject,jlong h) {
    auto* f=reinterpret_cast<File*>(h);
    if(f->smb)stellar::cancelSmbSource(f->token);else stellar::cancelNfsSource(f->token);
    f->reader->abort();
}
extern "C" JNIEXPORT void JNICALL Java_com_stellar_player_disc_NasDiscNative_close(JNIEnv*,jobject,jlong h) { delete reinterpret_cast<File*>(h); }
