package cn.coldlake.stellar.opensource.medialibrary.servers

import androidx.compose.runtime.Composable
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.settings.SettingsCategoryIcon
import cn.coldlake.stellar.opensource.settings.SettingsIconColor

/** Shared service identity for saved connections and service pickers. */
@Composable
internal fun ServerServiceIcon(kind: ServerKind) {
    val (icon, color) = when (kind) {
        ServerKind.EMBY -> R.drawable.material_symbol_video_library to SettingsIconColor.GREEN
        ServerKind.JELLYFIN -> R.drawable.service_jellyfin to SettingsIconColor.PURPLE
        ServerKind.WEBDAV -> R.drawable.material_symbol_language to SettingsIconColor.BLUE
        ServerKind.SMB -> R.drawable.material_symbol_folder to SettingsIconColor.GREEN
        ServerKind.NFS -> R.drawable.material_symbol_dns to SettingsIconColor.PURPLE
        ServerKind.GOOGLE_DRIVE -> R.drawable.storage_google_drive to SettingsIconColor.ORANGE
        ServerKind.ONEDRIVE -> R.drawable.storage_onedrive to SettingsIconColor.CYAN
    }
    SettingsCategoryIcon(icon, color, preserveIconColors = kind.mediaServer || kind.cloud)
}
