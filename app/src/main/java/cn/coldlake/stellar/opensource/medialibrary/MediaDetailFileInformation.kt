package cn.coldlake.stellar.opensource.medialibrary

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

/** File facts only, without cards or technical stream details. Missing facts stay explicit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun MediaDetailFileInformation(location: String, fileName: String, fileType: String,
    size: Long?, modifier: Modifier = Modifier, added: String = "") {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val date = remember(added, locale) { runCatching {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date.from(Instant.parse(added)))
    }.getOrNull() }
    Column(modifier.fillMaxWidth().padding(horizontal = LocalMediaDetailLayout.current.inset, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.detail_file_location, location), style = MaterialTheme.typography.bodyMedium)
        Text(fileName.ifBlank { stringResource(R.string.detail_file_name_unknown) }, style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val baseline = Modifier.alignByBaseline()
            Text(fileType.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT) ?: stringResource(R.string.detail_file_type_unknown),
                modifier = baseline, style = MaterialTheme.typography.bodyMedium)
            Text(size?.takeIf { it >= 0 }?.let { Formatter.formatFileSize(context, it) } ?: stringResource(R.string.detail_file_size_unknown),
                modifier = baseline, style = MaterialTheme.typography.bodyMedium)
            Text(date?.let { stringResource(R.string.detail_file_added, it) } ?: stringResource(R.string.detail_file_added_unknown),
                modifier = baseline, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
