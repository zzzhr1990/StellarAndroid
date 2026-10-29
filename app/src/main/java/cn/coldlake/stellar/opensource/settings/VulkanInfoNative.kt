package cn.coldlake.stellar.opensource.settings

/** App-owned read-only probe. No MPV, render device, surface, queue or swapchain is created. */
internal object VulkanInfoNative {
    init { System.loadLibrary("stellar_device_info") }
    fun read(): String = readBytes().toString(Charsets.UTF_8)
    private external fun readBytes(): ByteArray
}

internal fun vulkanApiVersion(packed: Long): String {
    val major = (packed ushr 22) and 0x7f
    val minor = (packed ushr 12) and 0x3ff
    val patch = packed and 0xfff
    return "$major.$minor.$patch"
}
