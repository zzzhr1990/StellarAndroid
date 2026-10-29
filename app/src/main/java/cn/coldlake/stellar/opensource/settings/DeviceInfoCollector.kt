package cn.coldlake.stellar.opensource.settings

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import android.view.Display
import cn.coldlake.stellar.opensource.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Public app APIs only. No shell, hidden system properties, network, decoder creation or media. */
internal class DeviceInfoCollector(context: Context) {
    private val app = context.applicationContext
    private fun raw(value: String?) = value?.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
        ?.let(DeviceInfoValue::Text) ?: DeviceInfoValue.Message(R.string.di_unavailable)
    private fun flag(value: Boolean) = DeviceInfoValue.Message(if (value) R.string.di_yes else R.string.di_no)
    private fun number(value: Number, format: Int = R.string.di_number) = DeviceInfoValue.Number(value.toDouble(), format)
    private fun lines(values: List<DeviceInfoValue>) = if (values.isEmpty()) DeviceInfoValue.Message(R.string.di_none) else DeviceInfoValue.Lines(values)
    private fun field(key: String, title: Int, value: DeviceInfoValue, vararg args: Any) = DeviceInfoField(key, title, value, args.toList())

    suspend fun collect(displayId: Int): DeviceInfoSnapshot = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        val sampledAt = System.currentTimeMillis()
        val sections = mutableListOf<DeviceInfoSection>()
        suspend fun section(key: String, title: Int, read: () -> List<DeviceInfoField>) {
            currentCoroutineContext().ensureActive()
            sections += try { DeviceInfoSection(key, title, read()) }
            catch (_: Exception) { DeviceInfoSection(key, title, emptyList(), failed = true) }
        }
        section("device", R.string.di_device) { device() }
        section("system", R.string.di_system) { system() }
        section("memory", R.string.di_memory_storage) { memory() }
        section("display", R.string.di_display) { display(displayId) }
        section("graphics", R.string.di_graphics) { graphics() }
        section("vulkan", R.string.di_vulkan) { vulkan() }
        section("features", R.string.di_features) { features() }
        section("battery", R.string.di_battery) { battery() }
        section("app", R.string.di_application) { application() }
        currentCoroutineContext().ensureActive()
        var codecFailure = false
        val codecs = mutableListOf<DeviceCodecInfo>()
        val codecInfos = try { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList() }
            catch (_: Exception) { codecFailure = true; emptyList() }
        // Cache only constant names, not codec objects. Profiles are matched to their MIME family.
        val constants = MediaCodecInfo.CodecProfileLevel::class.java.fields.filter {
            it.type == Int::class.javaPrimitiveType && java.lang.reflect.Modifier.isStatic(it.modifiers)
        }.mapNotNull { field -> runCatching { field.name to field.getInt(null) }.getOrNull() }.toMap()
        for (info in codecInfos) {
            currentCoroutineContext().ensureActive()
            if (info.isEncoder) continue
            for (mime in info.supportedTypes.filter { it.startsWith("video/") || it.startsWith("audio/") }) {
                currentCoroutineContext().ensureActive()
                codecs += DeviceCodecInfo(info.name, mime, codec(info, mime, constants))
            }
        }
        DeviceInfoSnapshot(sampledAt, SystemClock.elapsedRealtime() - started, sections,
            codecs.distinctBy { it.key }.sortedWith(compareBy({ it.mime }, { it.name })), codecFailure)
    }

    private fun device() = buildList {
        add(field("model", R.string.di_model, raw(Build.MODEL)))
        add(field("manufacturer", R.string.di_manufacturer, raw(Build.MANUFACTURER)))
        add(field("brand", R.string.di_brand, raw(Build.BRAND)))
        add(field("device", R.string.di_codename, raw(Build.DEVICE)))
        add(field("product", R.string.di_product, raw(Build.PRODUCT)))
        add(field("hardware", R.string.di_hardware, raw(Build.HARDWARE)))
        add(field("board", R.string.di_board, raw(Build.BOARD)))
        add(field("soc", R.string.di_soc, if (Build.VERSION.SDK_INT >= 31) raw(Build.SOC_MODEL) else DeviceInfoValue.Message(R.string.di_requires_api31)))
        add(field("soc_vendor", R.string.di_soc_vendor, if (Build.VERSION.SDK_INT >= 31) raw(Build.SOC_MANUFACTURER) else DeviceInfoValue.Message(R.string.di_requires_api31)))
        add(field("abis", R.string.di_abis, lines(Build.SUPPORTED_ABIS.map(::raw))))
    }

    private fun system() = listOf(
        field("android", R.string.di_android, raw(Build.VERSION.RELEASE)),
        field("api", R.string.di_api, number(Build.VERSION.SDK_INT)),
        field("codename", R.string.di_os_codename, raw(Build.VERSION.CODENAME)),
        field("patch", R.string.di_security_patch, raw(Build.VERSION.SECURITY_PATCH)),
        field("build", R.string.di_build, raw(Build.DISPLAY)),
        field("incremental", R.string.di_incremental, raw(Build.VERSION.INCREMENTAL)),
        field("fingerprint", R.string.di_fingerprint, raw(Build.FINGERPRINT)),
        field("locale", R.string.di_system_locale, raw(Resources.getSystem().configuration.locales.toLanguageTags())),
        field("pages", R.string.di_page_size, number(Os.sysconf(OsConstants._SC_PAGESIZE), R.string.di_bytes_exact)),
    )

    private fun memory() = buildList {
        val am = app.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        add(field("total", R.string.di_ram_total, DeviceInfoValue.Bytes(memory.totalMem)))
        add(field("available", R.string.di_ram_available, DeviceInfoValue.Bytes(memory.availMem)))
        add(field("low_ram", R.string.di_low_ram, flag(am.isLowRamDevice)))
        val swap = runCatching {
            File("/proc/meminfo").useLines { rows -> rows.filter { it.startsWith("SwapTotal:") || it.startsWith("SwapFree:") }
                .associate { row -> row.substringBefore(':') to row.substringAfter(':').trim().substringBefore(' ').toLong() * 1024L } }
        }.getOrDefault(emptyMap())
        add(field("swap_total", R.string.di_swap_total, swap["SwapTotal"]?.let(DeviceInfoValue::Bytes) ?: DeviceInfoValue.Message(R.string.di_unavailable)))
        add(field("swap_free", R.string.di_swap_free, swap["SwapFree"]?.let(DeviceInfoValue::Bytes) ?: DeviceInfoValue.Message(R.string.di_unavailable)))
        val disk = StatFs(app.filesDir.absolutePath)
        add(field("disk_total", R.string.di_storage_total, DeviceInfoValue.Bytes(disk.totalBytes)))
        add(field("disk_available", R.string.di_storage_available, DeviceInfoValue.Bytes(disk.availableBytes)))
    }

    @Suppress("DEPRECATION")
    private fun display(displayId: Int) = buildList {
        val display = checkNotNull(app.getSystemService(DisplayManager::class.java).getDisplay(displayId))
        val config = app.resources.configuration
        fun mode(mode: Display.Mode) = DeviceInfoValue.Text("${mode.physicalWidth} × ${mode.physicalHeight} · ${"%.2f".format(java.util.Locale.ROOT, mode.refreshRate)} Hz")
        add(field("mode", R.string.di_current_mode, mode(display.mode)))
        add(field("modes", R.string.di_supported_modes, lines(display.supportedModes.map(::mode).distinct())))
        val metrics = android.util.DisplayMetrics().also(display::getRealMetrics)
        add(field("logical_size", R.string.di_logical_size, raw("${metrics.widthPixels} × ${metrics.heightPixels}")))
        add(field("density", R.string.di_density, number(metrics.densityDpi, R.string.di_dpi)))
        add(field("font", R.string.di_font_scale, number(config.fontScale, R.string.di_multiplier)))
        val hdr = display.hdrCapabilities
        val types = (hdr?.supportedHdrTypes ?: intArrayOf()).map { type -> raw(when (type) {
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
            Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
            Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
            Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
            else -> type.toString()
        }) }
        add(field("hdr", R.string.di_hdr_types, if (hdr == null) DeviceInfoValue.Message(R.string.di_unavailable) else lines(types)))
        add(field("wide", R.string.di_wide_color, flag(display.isWideColorGamut)))
        fun luminance(value: Float?) = if (value != null && value >= 0f && value.isFinite()) number(value, R.string.di_nits) else DeviceInfoValue.Message(R.string.di_unavailable)
        add(field("max_luminance", R.string.di_max_luminance, luminance(hdr?.desiredMaxLuminance)))
        add(field("average_luminance", R.string.di_average_luminance, luminance(hdr?.desiredMaxAverageLuminance)))
        add(field("min_luminance", R.string.di_min_luminance, luminance(hdr?.desiredMinLuminance)))
        fun refreshSetting(key: String): DeviceInfoValue = runCatching {
            Settings.System.getString(app.contentResolver, key)?.toDoubleOrNull()?.let { number(it, R.string.di_hz) }
        }.getOrNull() ?: DeviceInfoValue.Message(R.string.di_system_default)
        add(field("peak_refresh", R.string.di_peak_refresh, refreshSetting("peak_refresh_rate")))
        add(field("min_refresh", R.string.di_min_refresh, refreshSetting("min_refresh_rate")))
    }

    private fun graphics(): List<DeviceInfoField> {
        val result = mutableListOf<DeviceInfoField>()
        // Isolated 1x1 offscreen EGL context on this worker; never bind a player or Compose Surface.
        val egl = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(egl != EGL14.EGL_NO_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(egl, version, 0, version, 1))
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(egl, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0)
            context = EGL14.eglCreateContext(egl, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT)
            surface = EGL14.eglCreatePbufferSurface(egl, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(egl, surface, surface, context))
            result += field("vendor", R.string.di_gpu_vendor, raw(GLES20.glGetString(GLES20.GL_VENDOR)))
            result += field("renderer", R.string.di_gpu_renderer, raw(GLES20.glGetString(GLES20.GL_RENDERER)))
            result += field("driver", R.string.di_gl_driver, raw(GLES20.glGetString(GLES20.GL_VERSION)))
            result += field("egl", R.string.di_egl_version, raw(EGL14.eglQueryString(egl, EGL14.EGL_VERSION)))
        } finally {
            EGL14.eglMakeCurrent(egl, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(egl, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(egl, context)
            EGL14.eglTerminate(egl)
            EGL14.eglReleaseThread()
        }
        result += field("gles", R.string.di_gles_supported, raw(app.getSystemService(ActivityManager::class.java).deviceConfigurationInfo.glEsVersion))
        return result
    }

    private fun features() = buildList {
        val pm = app.packageManager
        add(field("pip", R.string.di_pip, flag(pm.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))))
        add(field("low_latency", R.string.di_audio_low_latency, flag(pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY))))
        add(field("pro_audio", R.string.di_audio_pro, flag(pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO))))
    }

    private fun vulkan() = buildList {
        val features = app.packageManager.systemAvailableFeatures.associateBy { it.name }
        features[PackageManager.FEATURE_VULKAN_HARDWARE_VERSION]?.let {
            add(field("declared_api", R.string.di_vk_declared_api, raw(vulkanApiVersion(it.version.toLong()))))
        }
        features[PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL]?.let {
            add(field("level", R.string.di_vk_level, number(it.version)))
        }
        add(field("compute", R.string.di_vk_compute, flag(features.containsKey(PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE))))
        // A failed Vulkan probe must not discard the public Android declarations or other sections.
        val result = try { org.json.JSONObject(VulkanInfoNative.read()) }
            catch (_: Exception) { null }
            catch (_: LinkageError) { null }
        if (result == null) {
            add(field("probe_error", R.string.di_vk_probe, DeviceInfoValue.Message(R.string.di_read_failed)))
            return@buildList
        }
        if (result.has("loaderApi")) {
            add(field("loader_api", R.string.di_vk_loader_api, raw(vulkanApiVersion(result.getLong("loaderApi")))))
        }
        val devices = result.getJSONArray("devices")
        add(field("device_count", R.string.di_vk_device_count, number(devices.length())))
        if (devices.length() == 0) add(field("availability", R.string.di_vk_probe, DeviceInfoValue.Message(R.string.di_vk_no_device)))
        for (index in 0 until devices.length()) {
            val device = devices.getJSONObject(index)
            fun entry(key: String, title: Int, value: DeviceInfoValue) = add(field("device-$index/$key", title, value, index + 1))
            entry("name", R.string.di_vk_name, raw(device.getString("name")))
            entry("api", R.string.di_vk_api, raw(vulkanApiVersion(device.getLong("api"))))
            // Vulkan driverVersion has a vendor-specific encoding, not Vulkan's API version encoding.
            entry("driver", R.string.di_vk_driver, raw("0x${device.getLong("driver").toString(16)}"))
            entry("vendor", R.string.di_vk_vendor, raw("0x${device.getLong("vendor").toString(16)}"))
            entry("device_id", R.string.di_vk_device_id, raw("0x${device.getLong("id").toString(16)}"))
            entry("type", R.string.di_vk_type, DeviceInfoValue.Message(when (device.getInt("type")) {
                1 -> R.string.di_vk_integrated; 2 -> R.string.di_vk_discrete
                3 -> R.string.di_vk_virtual; 4 -> R.string.di_vk_cpu; else -> R.string.di_vk_other
            }))
            entry("max_image_2d", R.string.di_vk_max_image_2d, number(device.getLong("maxImage2D")))
            val extensions = device.optJSONArray("extensions")
            entry("extensions", R.string.di_vk_extensions, if (extensions == null) DeviceInfoValue.Message(R.string.di_read_failed)
                else lines((0 until extensions.length()).map { raw(extensions.getString(it)) }))
        }
    }

    private fun battery() = buildList {
        val battery = checkNotNull(app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        add(field("level", R.string.di_battery_level, if (level >= 0 && scale > 0) number(level * 100.0 / scale, R.string.di_percent) else DeviceInfoValue.Message(R.string.di_unavailable)))
        val status = when (battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> R.string.di_charging
            BatteryManager.BATTERY_STATUS_FULL -> R.string.di_full
            BatteryManager.BATTERY_STATUS_DISCHARGING -> R.string.di_discharging
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> R.string.di_not_charging
            else -> R.string.di_unavailable
        }
        add(field("status", R.string.di_battery_status, DeviceInfoValue.Message(status)))
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val power = when (plugged) {
            0 -> R.string.di_power_battery
            BatteryManager.BATTERY_PLUGGED_AC -> R.string.di_power_ac
            BatteryManager.BATTERY_PLUGGED_USB -> R.string.di_power_usb
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> R.string.di_power_wireless
            BatteryManager.BATTERY_PLUGGED_DOCK -> R.string.di_power_dock
            else -> R.string.di_unavailable
        }
        add(field("plugged", R.string.di_plugged, DeviceInfoValue.Message(power)))
        val temperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        add(field("temperature", R.string.di_temperature, if (temperature != Int.MIN_VALUE) number(temperature / 10.0, R.string.di_celsius) else DeviceInfoValue.Message(R.string.di_unavailable)))
        val voltage = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        add(field("voltage", R.string.di_voltage, if (voltage >= 0) number(voltage, R.string.di_millivolts) else DeviceInfoValue.Message(R.string.di_unavailable)))
        val health = when (battery.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> R.string.di_health_good
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> R.string.di_health_overheat
            BatteryManager.BATTERY_HEALTH_DEAD -> R.string.di_health_dead
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> R.string.di_health_voltage
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> R.string.di_health_failure
            BatteryManager.BATTERY_HEALTH_COLD -> R.string.di_health_cold
            else -> R.string.di_unavailable
        }
        add(field("health", R.string.di_battery_health, DeviceInfoValue.Message(health)))
    }

    @Suppress("DEPRECATION")
    private fun application(): List<DeviceInfoField> {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        return listOf(
            field("package", R.string.di_package, raw(app.packageName)),
            field("version", R.string.di_app_version, raw(info.versionName)),
            field("code", R.string.di_version_code, number(if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())),
            field("min_sdk", R.string.di_min_sdk, number(app.applicationInfo.minSdkVersion)),
            field("target_sdk", R.string.di_target_sdk, number(app.applicationInfo.targetSdkVersion)),
            field("process", R.string.di_process_64, flag(android.os.Process.is64Bit())),
            field("updated", R.string.di_last_updated, DeviceInfoValue.Timestamp(info.lastUpdateTime)),
        )
    }

    private fun codec(info: MediaCodecInfo, mime: String, constants: Map<String, Int>) = buildList {
        add(field("mime", R.string.di_mime, raw(mime)))
        if (Build.VERSION.SDK_INT >= 29) {
            add(field("hardware", R.string.di_hardware_accelerated, flag(info.isHardwareAccelerated)))
            add(field("software", R.string.di_software_only, flag(info.isSoftwareOnly)))
            add(field("vendor", R.string.di_vendor_codec, flag(info.isVendor)))
            add(field("alias", R.string.di_alias, flag(info.isAlias)))
        } else add(field("hardware", R.string.di_hardware_accelerated, DeviceInfoValue.Message(R.string.di_requires_api29)))
        try {
            val caps = info.getCapabilitiesForType(mime)
            add(field("instances", R.string.di_max_instances, number(caps.maxSupportedInstances)))
            add(field("secure", R.string.di_secure_supported, flag(caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback))))
            add(field("secure_required", R.string.di_secure_required, flag(caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback))))
            add(field("tunneled", R.string.di_tunneled, flag(caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback))))
            add(field("adaptive", R.string.di_adaptive, flag(caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback))))
            val prefix = when (mime) {
                "video/avc" -> "AVC"; "video/hevc" -> "HEVC"; "video/av01" -> "AV1"
                "video/x-vnd.on2.vp9" -> "VP9"; "video/x-vnd.on2.vp8" -> "VP8"
                "video/dolby-vision" -> "DolbyVision"; "video/3gpp" -> "H263"; "video/mp4v-es" -> "MPEG4"
                "audio/mp4a-latm" -> "AAC"; else -> ""
            }
            fun constant(value: Int, profile: Boolean): String = constants.filter { (name, number) ->
                number == value && prefix.isNotEmpty() && name.startsWith(prefix) &&
                    if (profile) (name.contains("Profile") || prefix == "AAC" && name.startsWith("AACObject")) else name.contains("Level")
            }.keys.joinToString(" / ").ifEmpty { "0x${value.toString(16)}" }
            add(field("profiles", R.string.di_profiles, lines(caps.profileLevels.map { raw("${constant(it.profile, true)} · ${constant(it.level, false)}") })))
            caps.videoCapabilities?.let { video ->
                add(field("width", R.string.di_width_range, raw(video.supportedWidths.toString())))
                add(field("height", R.string.di_height_range, raw(video.supportedHeights.toString())))
                add(field("bitrate", R.string.di_bitrate_range, raw(video.bitrateRange.toString())))
                add(field("framerate", R.string.di_frame_rate_range, raw(video.supportedFrameRates.toString())))
                for ((width, height, fps) in listOf(Triple(1920,1080,30), Triple(1920,1080,60), Triple(3840,2160,30), Triple(3840,2160,60), Triple(7680,4320,30))) {
                    add(field("$width-$height-$fps", R.string.di_size_rate, flag(video.areSizeAndRateSupported(width,height,fps.toDouble())), width, height, fps))
                }
                add(field("performance", R.string.di_performance_points, if (Build.VERSION.SDK_INT >= 29)
                    video.supportedPerformancePoints?.let { lines(it.map { point -> raw(point.toString()) }) }
                        ?: DeviceInfoValue.Message(R.string.di_not_reported)
                    else DeviceInfoValue.Message(R.string.di_requires_api29)))
            }
            caps.audioCapabilities?.let { audio ->
                add(field("channels", R.string.di_audio_channels, number(audio.maxInputChannelCount)))
                add(field("rates", R.string.di_audio_rates, lines(audio.supportedSampleRateRanges.map { raw(it.toString()) })))
                add(field("bitrate", R.string.di_bitrate_range, raw(audio.bitrateRange.toString())))
            }
        } catch (_: Exception) {
            add(field("error", R.string.di_capabilities, DeviceInfoValue.Message(R.string.di_read_failed)))
        }
    }
}
