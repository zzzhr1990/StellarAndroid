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
    PLAYBACK_CACHE(R.string.playback_cache_title), AUDIO(R.string.settings_audio), SUBTITLE(R.string.settings_subtitle)
}
internal enum class SettingKind { Section, Toggle, Choice, Slider, Edit, Sensitive, Action, Color, SubtitleLanguage, Pip, WebDavTls, WindowHdr, AccurateSeek }
internal data class SettingSpec(
    val id: String, @param:StringRes val title: Int, val kind: SettingKind,
    @param:StringRes val subtitle: Int? = null, val enabledByDefault: Boolean = false,
    @param:DrawableRes val icon: Int? = null,
    val options: List<Int> = emptyList(), val initialValue: Float = 0f,
    val range: ClosedFloatingPointRange<Float> = 0f..1f,
    @param:StringRes val unit: Int = R.string.hs_number, val indent: Boolean = false,
)

// Runtime-observed Hills order. THEME uses AppearanceStore; other preview values never enter PlaybackPort.
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
        SettingSpec("startup", R.string.hs_startup, SettingKind.Choice, subtitle = null, enabledByDefault = false, icon = null, options = listOf(R.string.hs_server_page,R.string.hs_home_page), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("hide_library", R.string.hs_hide_library, SettingKind.Toggle, subtitle = R.string.hs_hide_library_hint, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("hide_continue", R.string.hs_hide_continue, SettingKind.Toggle, subtitle = R.string.hs_hide_continue_hint, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("hide_unwatched", R.string.hs_hide_unwatched, SettingKind.Toggle, subtitle = R.string.hs_hide_unwatched_hint, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("hide_daily", R.string.hs_hide_daily, SettingKind.Toggle, subtitle = R.string.hs_hide_daily_hint, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("daily_backdrop", R.string.hs_daily_backdrop, SettingKind.Toggle, subtitle = R.string.hs_daily_backdrop_hint, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("daily_filter", R.string.hs_daily_filter, SettingKind.Edit, subtitle = R.string.hs_daily_filter_hint, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("cover_rating", R.string.hs_cover_rating, SettingKind.Toggle, subtitle = R.string.hs_cover_rating_hint, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("hide_gallery", R.string.hs_hide_gallery, SettingKind.Toggle, subtitle = R.string.hs_hide_gallery_hint, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("search_metadata", R.string.hs_search_metadata, SettingKind.Toggle, subtitle = R.string.hs_search_metadata_hint, enabledByDefault = true, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("search_history", R.string.hs_search_history, SettingKind.Toggle, subtitle = R.string.hs_search_history_hint, enabledByDefault = true, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("aggregate_rows", R.string.hs_aggregate_rows, SettingKind.Slider, subtitle = R.string.hs_aggregate_rows_hint, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 5.0f, range = 0.0f..10.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("cover_scale", R.string.hs_cover_scale, SettingKind.Slider, subtitle = null, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 1.0f, range = 0.4f..2.0f, unit = R.string.hs_multiplier, indent = false),
        SettingSpec("image_quality", R.string.hs_image_quality, SettingKind.Choice, subtitle = null, enabledByDefault = false, icon = null, options = listOf(R.string.hs_medium,R.string.hs_high,R.string.hs_low), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("chapters", R.string.hs_chapters, SettingKind.Choice, subtitle = null, enabledByDefault = false, icon = null, options = listOf(R.string.hs_always,R.string.hs_hide), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
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
        SettingSpec("preferred_version", R.string.hs_preferred_version, SettingKind.Edit, subtitle = R.string.hs_preferred_version_hint, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("orientation", R.string.hs_orientation, SettingKind.Choice, subtitle = null, enabledByDefault = false, icon = null, options = listOf(R.string.hs_landscape,R.string.hs_portrait,R.string.hs_system), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("skip_intro", R.string.hs_skip_intro, SettingKind.Choice, subtitle = null, enabledByDefault = false, icon = null, options = listOf(R.string.hs_show_button,R.string.hs_auto_skip,R.string.hs_off), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("thumbnails", R.string.hs_thumbnails, SettingKind.Toggle, subtitle = R.string.hs_thumbnails_hint, enabledByDefault = true, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("force_sdr", R.string.playback_force_sdr, SettingKind.Toggle),
        SettingSpec("remember_brightness", R.string.hs_remember_brightness, SettingKind.Toggle, subtitle = R.string.hs_remember_brightness_hint, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("remember_fit", R.string.hs_remember_fit, SettingKind.Toggle, subtitle = R.string.hs_remember_fit_hint, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("live_direct", R.string.hs_live_direct, SettingKind.Toggle, subtitle = R.string.hs_live_direct_hint, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("max_refresh", R.string.hs_max_refresh, SettingKind.Toggle, subtitle = R.string.hs_max_refresh_hint, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("disable_session", R.string.hs_disable_session, SettingKind.Toggle, subtitle = null, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false),
        SettingSpec("accurate_seek", R.string.hs_accurate_seek, SettingKind.AccurateSeek,
            subtitle = R.string.hs_accurate_seek_hint, enabledByDefault = true),
        SettingSpec("pip", R.string.hs_pip, SettingKind.Pip, subtitle = R.string.hs_pip_hint, enabledByDefault = false, icon = null, options = emptyList(), initialValue = 0.0f, range = 0.0f..1.0f, unit = R.string.hs_number, indent = false)
    ),
    SettingsPage.AUDIO to listOf(
    ),
    SettingsPage.SUBTITLE to listOf(
        SettingSpec("subtitle_language", R.string.hs_subtitle_language, SettingKind.SubtitleLanguage),
    ),
    SettingsPage.ADVANCED_FEATURES to listOf(
        SettingSpec("force_hdr", R.string.hs_force_hdr, SettingKind.WindowHdr, subtitle = R.string.hs_force_hdr_hint),
        SettingSpec("ignore_ssl", R.string.hs_ignore_ssl, SettingKind.WebDavTls, subtitle = R.string.hs_ignore_ssl_hint),
    ),
)
