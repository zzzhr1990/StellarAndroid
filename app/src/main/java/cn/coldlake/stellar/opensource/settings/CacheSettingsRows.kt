package cn.coldlake.stellar.opensource.settings

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.cache.*

internal val cacheSettingIds = setOf("data_cache", "data_expiry", "image_expiry", "clear_data", "clear_images")

@Composable internal fun CacheSettingsRow(spec: SettingSpec, caches: AppCaches, onNavigate: (String) -> Unit) {
    val settings by caches.settings.state.collectAsState()
    val state by caches.maintenance.collectAsState()
    val image=spec.id in setOf("image_expiry", "clear_images")
    val usage=if(image) state.images else state.data
    val retention=if(image) settings.imageRetention else settings.dataRetention
    val toggle=spec.id=="data_cache"
    val choice=spec.kind==SettingKind.Choice
    val enabled=!state.busy
    val subtitle=when {
        toggle -> stringResource(R.string.cache_data_hint)
        choice -> stringResource(R.string.cache_retention_hint, stringResource(spec.options[retention.ordinal]))
        usage.work==CacheWork.CALCULATING || usage.work==CacheWork.IDLE && usage.bytes==null -> stringResource(R.string.cache_calculating)
        usage.work==CacheWork.CLEARING -> stringResource(R.string.cache_clearing)
        usage.work==CacheWork.FAILED -> stringResource(R.string.cache_failed)
        else -> {
            val size=Formatter.formatFileSize(LocalContext.current,usage.bytes ?: 0)
            stringResource(if(usage.work==CacheWork.DONE) R.string.cache_cleared_size else R.string.cache_size,size)
        }
    }
    Box {
        Row(Modifier.padding(horizontal=16.dp,vertical=2.dp).fillMaxWidth().heightIn(min=72.dp)
            .clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .testTag("setting-${spec.id}")
            .then(if(toggle) Modifier.toggleable(settings.dataEnabled,enabled=enabled,role=Role.Switch,
                onValueChange={ caches.setDataEnabled(it) }) else Modifier.clickable(enabled=enabled,role=Role.Button) {
                if(choice) onNavigate("cache:${spec.id}") else caches.clear(image)
            }).padding(horizontal=16.dp,vertical=16.dp),verticalAlignment=Alignment.CenterVertically) {
            RowText(spec.title,subtitle,Modifier.weight(1f))
            if(toggle) { Spacer(Modifier.width(12.dp)); Switch(settings.dataEnabled,onCheckedChange=null,enabled=enabled) }
        }

    }
}

@Composable internal fun CacheUsageFooter(caches: AppCaches) {
    val state by caches.maintenance.collectAsState()
    Column(Modifier.padding(horizontal=16.dp)) {
        Text(stringResource(R.string.cache_usage_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick={ caches.refreshUsage() },enabled=!state.busy,modifier=Modifier.testTag("cache-refresh-size")) {
            Text(stringResource(R.string.cache_refresh_size))
        }
    }
}
