#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <memory>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/opt.h>
}

namespace {
struct Encoder {
    AVFormatContext* format = nullptr;
    AVCodecContext* codec = nullptr;
    AVFrame* frame = nullptr;
    AVPacket* packet = nullptr;
    AVStream* stream = nullptr;
    int64_t lastPts = -1;
    bool finished = false;
    ~Encoder() {
        av_packet_free(&packet);
        av_frame_free(&frame);
        avcodec_free_context(&codec);
        if (format) { if (format->pb) avio_closep(&format->pb); avformat_free_context(format); }
    }
    bool drain() {
        for (;;) {
            const int r = avcodec_receive_packet(codec, packet);
            if (r == AVERROR(EAGAIN) || r == AVERROR_EOF) return true;
            if (r < 0) return false;
            av_packet_rescale_ts(packet, codec->time_base, stream->time_base);
            packet->stream_index = stream->index;
            const int written = av_interleaved_write_frame(format, packet);
            av_packet_unref(packet);
            if (written < 0) return false;
        }
    }
};
}

extern "C" JNIEXPORT jlong JNICALL Java_com_stellar_player_nativeio_GifCaptureEncoder_create(
    JNIEnv* env, jobject, jstring path, jint width, jint height) {
    // Struct ABI is tied to the shipped binary, not whatever FFmpeg happens to be installed.
    if (avcodec_version() >> 16 != LIBAVCODEC_VERSION_MAJOR ||
        avformat_version() >> 16 != LIBAVFORMAT_VERSION_MAJOR || avutil_version() >> 16 != LIBAVUTIL_VERSION_MAJOR ||
        width < 2 || height < 2 || width > 640 || height > 640) {
        __android_log_print(ANDROID_LOG_ERROR, "GifCapture", "Incompatible encoder ABI: %s %u/%u/%u; size %dx%d",
            av_version_info(), avcodec_version() >> 16, avformat_version() >> 16, avutil_version() >> 16, width, height);
        return 0;
    }
    auto e = std::make_unique<Encoder>();
    const AVCodec* gif = avcodec_find_encoder_by_name("gif");
    if (!gif || avformat_alloc_output_context2(&e->format, nullptr, "gif", nullptr) < 0 || !e->format) return 0;
    e->codec = avcodec_alloc_context3(gif);
    if (!e->codec) return 0;
    e->codec->width = width; e->codec->height = height;
    e->codec->pix_fmt = AV_PIX_FMT_PAL8;
    e->codec->time_base = AVRational{1, 100};
    e->codec->framerate = AVRational{10, 1};
    if (avcodec_open2(e->codec, gif, nullptr) < 0) return 0;
    e->stream = avformat_new_stream(e->format, nullptr);
    if (!e->stream || avcodec_parameters_from_context(e->stream->codecpar, e->codec) < 0) return 0;
    e->stream->time_base = e->codec->time_base;
    av_opt_set_int(e->format->priv_data, "loop", 0, 0);
    const char* filename = env->GetStringUTFChars(path, nullptr);
    if (!filename) return 0;
    const int opened = avio_open(&e->format->pb, filename, AVIO_FLAG_WRITE);
    env->ReleaseStringUTFChars(path, filename);
    if (opened < 0 || avformat_write_header(e->format, nullptr) < 0) return 0;
    e->frame = av_frame_alloc(); e->packet = av_packet_alloc();
    if (!e->frame || !e->packet) return 0;
    e->frame->format = AV_PIX_FMT_PAL8; e->frame->width = width; e->frame->height = height;
    if (av_frame_get_buffer(e->frame, 32) < 0) return 0;
    return reinterpret_cast<jlong>(e.release());
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_stellar_player_nativeio_GifCaptureEncoder_add(
    JNIEnv* env, jobject, jlong handle, jobject bitmap, jlong timeMs) {
    auto* e = reinterpret_cast<Encoder*>(handle);
    if (!e || e->finished || timeMs < 0 || timeMs > 11000 || av_frame_make_writable(e->frame) < 0) return false;
    AndroidBitmapInfo info{}; void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != 0 || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        info.width != unsigned(e->frame->width) || info.height != unsigned(e->frame->height) ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) != 0) return false;
    // Bounded RGB332 palette with ordered dithering. No missing palettegen/paletteuse filters.
    auto* palette = reinterpret_cast<uint32_t*>(e->frame->data[1]);
    for (int i = 0; i < 256; ++i)
        palette[i] = 0xff000000u | ((i >> 5) * 255 / 7 << 16) | (((i >> 2) & 7) * 255 / 7 << 8) | ((i & 3) * 255 / 3);
    const int bayer[4][4] = {{0,8,2,10},{12,4,14,6},{3,11,1,9},{15,7,13,5}};
    for (unsigned y = 0; y < info.height; ++y) {
        const auto* src = static_cast<uint8_t*>(pixels) + y * info.stride;
        auto* dst = e->frame->data[0] + y * e->frame->linesize[0];
        for (unsigned x = 0; x < info.width; ++x) {
            const int d = bayer[y % 4][x % 4] - 8;
            const int r = (std::clamp(int(src[x*4]) + d*2, 0, 255) * 7 + 127) / 255;
            const int g = (std::clamp(int(src[x*4+1]) + d*2, 0, 255) * 7 + 127) / 255;
            const int b = (std::clamp(int(src[x*4+2]) + d*5, 0, 255) * 3 + 127) / 255;
            dst[x] = (r << 5) | (g << 2) | b;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    e->frame->pts = std::max<int64_t>(e->lastPts + 1, (timeMs + 5) / 10);
    e->lastPts = e->frame->pts;
    return avcodec_send_frame(e->codec, e->frame) >= 0 && e->drain();
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stellar_player_nativeio_GifCaptureEncoder_finish(
    JNIEnv*, jobject, jlong handle, jlong durationMs) {
    auto* e = reinterpret_cast<Encoder*>(handle);
    if (!e || e->finished || e->lastPts < 0) return false;
    av_opt_set_int(e->format->priv_data, "final_delay", std::max<int64_t>(1, (durationMs + 5) / 10 - e->lastPts), 0);
    if (avcodec_send_frame(e->codec, nullptr) < 0 || !e->drain()) return false;
    e->finished = true;
    return av_write_trailer(e->format) >= 0 && avio_closep(&e->format->pb) >= 0;
}
extern "C" JNIEXPORT void JNICALL Java_com_stellar_player_nativeio_GifCaptureEncoder_release(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Encoder*>(handle);
}
