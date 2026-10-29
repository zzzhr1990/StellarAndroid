package cn.coldlake.stellar.opensource.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import cn.coldlake.stellar.opensource.R

internal enum class SettingsPage(@param:StringRes val title: Int) {
    HOME(R.string.home_settings), THEME(R.string.hs_theme),
    LIBRARY(R.string.hs_library),
    INTERACTION(R.string.hs_interaction), PLAYER(R.string.hs_player),
    ADVANCED_FEATURES(R.string.hs_advanced_features), ABOUT(R.string.hs_about),
    DEVICE_INFO(R.string.di_title), OPEN_SOURCE(R.string.about_licenses), CUSTOM_THEME(R.string.ap_color_custom), SCREENSHOT(R.string.screenshot_title),
    PLAYBACK_CACHE(R.string.playback_cache_title), SUBTITLE(R.string.settings_subtitle)
}
internal enum class SettingKind { Toggle, Choice, Slider, Action, Color, SubtitleLanguage, Pip, WebDavTls, WindowHdr, AccurateSeek }
internal data class SettingSpec(
    val id: String, @param:StringRes val title: Int, val kind: SettingKind,
    @param:StringRes val subtitle: Int? = null, val enabledByDefault: Boolean = false,
    @param:DrawableRes val icon: Int? = null,
    val options: List<Int> = emptyList(), val initialValue: Float = 0f,
    val range: ClosedFloatingPointRange<Float> = 0f..1f,
    @param:StringRes val unit: Int = R.string.hs_number, val indent: Boolean = false,
)

// Catalogue entries are backed by runtime settings stores.
internal val settingsCatalogue = mapOf(
    SettingsPage.THEME to listOf(
        SettingSpec("theme_mode", R.string.hs_theme_mode, SettingKind.Choice, subtitle = null, enabledByDefault = false, icon = R.drawable.material_symbol_dark_mode, options = listOf(R.string.hs_system,R.string.hs_light,R.string.hs_dark), initialValue = 2.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("theme_color", R.string.hs_theme_color, SettingKind.Color, subtitle = null, enabledByDefault = false, icon = R.drawable.material_symbol_palette, options = emptyList(), initialValue = 0.0f, range = 0.0f..3.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("material_you", R.string.hs_material_you, SettingKind.Toggle, subtitle = R.string.hs_material_you_hint, enabledByDefault = false, icon = R.drawable.material_symbol_palette, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("app_font", R.string.hs_app_font, SettingKind.Toggle, subtitle = R.string.hs_app_font_hint, enabledByDefault = false, icon = R.drawable.material_symbol_format_size, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("floating_nav", R.string.hs_floating_nav, SettingKind.Toggle, subtitle = null, enabledByDefault = true, icon = R.drawable.material_symbol_smartphone, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("hide_nav_labels", R.string.hs_hide_nav_labels, SettingKind.Toggle, subtitle = R.string.hs_hide_nav_labels_hint,
            enabledByDefault = false, icon = R.drawable.material_symbol_smartphone),
        SettingSpec("background_style", R.string.hs_background_style, SettingKind.Choice, subtitle = null, enabledByDefault = false, icon = R.drawable.material_symbol_palette, options = listOf(R.string.ap_background_default,R.string.hs_dynamic,R.string.ap_background_image), initialValue = 1.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false)
    ),
    SettingsPage.LIBRARY to listOf(
        SettingSpec("data_cache", R.string.hs_data_cache, SettingKind.Toggle, subtitle = R.string.hs_data_cache_hint, enabledByDefault = true, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("data_expiry", R.string.hs_data_expiry, SettingKind.Choice, subtitle = null, enabledByDefault = false, icon = null, options = listOf(R.string.hs_thirty_days,R.string.hs_seven_days,R.string.hs_one_day), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("image_expiry", R.string.hs_image_expiry, SettingKind.Choice, subtitle = null, enabledByDefault = false, icon = null, options = listOf(R.string.hs_thirty_days,R.string.hs_seven_days,R.string.hs_one_day), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("clear_data", R.string.hs_clear_data, SettingKind.Action, subtitle = R.string.hs_clear_data_hint, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("clear_images", R.string.hs_clear_images, SettingKind.Action, subtitle = R.string.hs_clear_images_hint, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false)
    ),
    SettingsPage.INTERACTION to listOf(
        SettingSpec("double_pause", R.string.hs_double_pause, SettingKind.Toggle, subtitle = R.string.hs_double_pause_hint, enabledByDefault = true, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("show_pause", R.string.hs_show_pause, SettingKind.Toggle, subtitle = R.string.hs_show_pause_hint, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("double_seek", R.string.hs_double_seek, SettingKind.Toggle, subtitle = R.string.hs_double_seek_hint, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("show_seek", R.string.hs_show_seek, SettingKind.Toggle, subtitle = R.string.hs_show_seek_hint, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("forward_seconds", R.string.hs_forward_seconds, SettingKind.Slider, subtitle = null, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 10.0f, range = 1.0f..60.0f, unit = R.string.hs_seconds, indent = false),
        SettingSpec("rewind_seconds", R.string.hs_rewind_seconds, SettingKind.Slider, subtitle = null, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 10.0f, range = 1.0f..60.0f, unit = R.string.hs_seconds, indent = false),
        SettingSpec("long_press", R.string.hs_long_press, SettingKind.Toggle, subtitle = R.string.hs_long_press_hint, enabledByDefault = true, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("speed_gesture", R.string.hs_speed_gesture, SettingKind.Toggle, subtitle = R.string.hs_speed_gesture_hint, enabledByDefault = true, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = true),
        SettingSpec("hold_speed", R.string.hs_hold_speed, SettingKind.Slider, subtitle = R.string.hs_hold_speed_hint, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 2.0f, range = 0.5f..8.0f, unit = R.string.hs_multiplier, indent = true),
        SettingSpec("seek_gesture", R.string.hs_seek_gesture, SettingKind.Toggle, subtitle = R.string.hs_seek_gesture_hint, enabledByDefault = true, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("brightness_gesture", R.string.hs_brightness_gesture, SettingKind.Toggle, subtitle = R.string.hs_brightness_gesture_hint, enabledByDefault = true, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("volume_gesture", R.string.hs_volume_gesture, SettingKind.Toggle, subtitle = R.string.hs_volume_gesture_hint, enabledByDefault = true, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("status_time", R.string.hs_status_time, SettingKind.Toggle, subtitle = null, enabledByDefault = true, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("status_speed", R.string.hs_status_speed, SettingKind.Toggle, subtitle = null, enabledByDefault = true, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("status_battery", R.string.hs_status_battery, SettingKind.Toggle, subtitle = null, enabledByDefault = true, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("ignore_safe", R.string.hs_ignore_safe, SettingKind.Toggle, subtitle = R.string.hs_ignore_safe_hint, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
    ),
    SettingsPage.PLAYER to listOf(
        SettingSpec("force_sdr", R.string.playback_force_sdr, SettingKind.Toggle),
        SettingSpec("accurate_seek", R.string.hs_accurate_seek, SettingKind.AccurateSeek,
            subtitle = R.string.hs_accurate_seek_hint, enabledByDefault = true),
        SettingSpec("pip", R.string.hs_pip, SettingKind.Pip, subtitle = R.string.hs_pip_hint, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false)
    ),
    SettingsPage.SUBTITLE to listOf(
        SettingSpec("subtitle_language", R.string.hs_subtitle_language, SettingKind.SubtitleLanguage),
    ),
    SettingsPage.ADVANCED_FEATURES to listOf(
        SettingSpec("force_hdr", R.string.hs_force_hdr, SettingKind.WindowHdr, subtitle = R.string.hs_force_hdr_hint),
        SettingSpec("ignore_ssl", R.string.hs_ignore_ssl, SettingKind.WebDavTls, subtitle = R.string.hs_ignore_ssl_hint),
    ),
)
