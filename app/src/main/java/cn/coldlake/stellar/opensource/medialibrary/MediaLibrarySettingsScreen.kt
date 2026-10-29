package cn.coldlake.stellar.opensource.medialibrary

import com.stellar.player.ui.LocalPageActive
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.settings.SettingsNavigationRow
import cn.coldlake.stellar.opensource.settings.SettingsPageToolbar
import cn.coldlake.stellar.opensource.settings.SectionTitle
import cn.coldlake.stellar.opensource.medialibrary.catalog.LocalLibraryRuntime
import cn.coldlake.stellar.opensource.medialibrary.catalog.ScanControls

@Composable
internal fun MediaLibrarySettingsScreen(onBack: () -> Unit, onBuiltInLibrary: () -> Unit,
    onAddFolder: () -> Unit, onMetadata: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember(context) { LocalLibraryRuntime.get(context) }
    Surface(Modifier.fillMaxSize().testTag("media-library-settings-page"),
        color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxSize()) {
            SettingsPageToolbar(R.string.media_library_settings, onBack, "media-library-settings-back")
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                SectionTitle(R.string.home_manage)
                SettingsNavigationRow(R.string.scan_add, R.drawable.material_symbol_folder,
                    "LIBRARY_ADD", groupIndex = 0, groupCount = 3, onClick = onAddFolder)
                SettingsNavigationRow(R.string.library_builtin, R.drawable.material_symbol_video_library,
                    "BUILT_IN_LIBRARY", groupIndex = 1, groupCount = 3, onClick = onBuiltInLibrary)
                SettingsNavigationRow(R.string.metadata_title, R.drawable.material_symbol_movie,
                    "LIBRARY_METADATA", groupIndex = 2, groupCount = 3, onClick = onMetadata)
                ScanControls(runtime, Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
            }
        }
    }
}
