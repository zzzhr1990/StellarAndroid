package cn.coldlake.stellar.opensource.platform

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.SystemClock
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Worker-confined export Surface. The MPV display Surface is never rebound. */
internal class AvcCaptureEncoder(path: String, private val width: Int, private val height: Int) : Closeable {
    private var codec: MediaCodec? = null
    private var input: Surface? = null
    private var muxer: MediaMuxer? = null
    private var muxing = false
    private var track = -1
    private val info = MediaCodec.BufferInfo()
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var finished = false
    private var lastTimestampUs = -1L
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // Bitmap row zero is top; OpenGL bottom vertices sample the bitmap's last row.
        put(floatArrayOf(-1f,-1f, 0f,1f, 1f,-1f, 1f,1f, -1f,1f, 0f,0f, 1f,1f, 1f,0f)); position(0)
    }
    init {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 15)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            for (candidate in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
                it.isEncoder && it.supportedTypes.any { mime -> mime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
            }) {
                var attempt: MediaCodec? = null
                try {
                    val caps = candidate.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    if (CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats || !caps.isFormatSupported(format)) continue
                    attempt = MediaCodec.createByCodecName(candidate.name)
                    attempt.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    input = attempt.createInputSurface()
                    attempt.start()
                    codec = attempt
                    break
                } catch (_: Exception) { input?.release(); input = null; runCatching { attempt?.release() } }
            }
            checkNotNull(codec) { "No compatible AVC encoder" }
            muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            setupGl()
        } catch (e: Throwable) { close(); throw e }
    }
    private fun setupGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE,8, EGL14.EGL_GREEN_SIZE,8,
            EGL14.EGL_BLUE_SIZE,8, EGL14.EGL_ALPHA_SIZE,8, EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT, 0x3142,1, EGL14.EGL_NONE), 0, configs, 0, 1, IntArray(1), 0))
        val config = checkNotNull(configs[0])
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE), 0)
        window = EGL14.eglCreateWindowSurface(display, config, input, intArrayOf(EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT && window != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display,window,window,context))
        fun shader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
            val status = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) { GLES20.glDeleteShader(id); error("Export shader failed") }
            return id
        }
        val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec4 p; attribute vec2 t; varying vec2 uv; void main(){gl_Position=p;uv=t;}")
        val fragment = try { shader(GLES20.GL_FRAGMENT_SHADER, "precision mediump float; uniform sampler2D image; varying vec2 uv; void main(){gl_FragColor=texture2D(image,uv);}") }
            catch (e: Throwable) { GLES20.glDeleteShader(vertex); throw e }
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program,vertex); GLES20.glAttachShader(program,fragment); GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        val status = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS,status,0); check(status[0] != 0)
        GLES20.glUseProgram(program)
        val textures = IntArray(1); GLES20.glGenTextures(1,textures,0); texture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glViewport(0,0,width,height)
    }
    fun add(bitmap: Bitmap, timestampMs: Long) {
        check(!finished)
        drain(false)
        GLES20.glUseProgram(program); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0)
        val p = GLES20.glGetAttribLocation(program,"p"); val t = GLES20.glGetAttribLocation(program,"t")
        vertices.position(0); GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,16,vertices); GLES20.glEnableVertexAttribArray(p)
        vertices.position(2); GLES20.glVertexAttribPointer(t,2,GLES20.GL_FLOAT,false,16,vertices); GLES20.glEnableVertexAttribArray(t)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
        check(EGLExt.eglPresentationTimeANDROID(display,window,timestampMs * 1_000_000))
        check(EGL14.eglSwapBuffers(display,window))
        drain(false)
    }
    fun finish(durationMs: Long) {
        check(!finished)
        codec!!.signalEndOfInputStream()
        drain(true)
        check(muxing)
        // Explicit final-sample duration also preserves wall-clock length when sampling drops.
        check(durationMs * 1000 > lastTimestampUs)
        val end = MediaCodec.BufferInfo().apply { set(0,0,durationMs * 1000,MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
        muxer!!.writeSampleData(track,ByteBuffer.allocateDirect(0),end)
        muxer!!.stop(); muxing = false; finished = true
    }
    private fun drain(eos: Boolean) {
        val end = SystemClock.elapsedRealtime() + 5000
        while (true) {
            val index = codec!!.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!eos) return
                    check(SystemClock.elapsedRealtime() < end) { "AVC drain timeout" }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxing); track = muxer!!.addTrack(codec!!.outputFormat); muxer!!.start(); muxing = true
                }
                index >= 0 -> {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(muxing)
                            val buffer = checkNotNull(codec!!.getOutputBuffer(index))
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            muxer!!.writeSampleData(track,buffer,info)
                            lastTimestampUs = maxOf(lastTimestampUs,info.presentationTimeUs)
                        }
                    } finally { codec!!.releaseOutputBuffer(index,false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
    override fun close() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT && window != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display,window,window,context)
                if (texture != 0) GLES20.glDeleteTextures(1,intArrayOf(texture),0)
                if (program != 0) GLES20.glDeleteProgram(program)
            }
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,window)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context)
            EGL14.eglReleaseThread(); EGL14.eglTerminate(display); display = EGL14.EGL_NO_DISPLAY
        }
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
        input?.release(); input = null
        if (muxing) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }; muxer = null; muxing = false
    }
}
