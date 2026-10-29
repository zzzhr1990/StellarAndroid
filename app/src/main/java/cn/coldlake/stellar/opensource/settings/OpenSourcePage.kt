package cn.coldlake.stellar.opensource.settings

import android.content.res.AssetManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

internal data class AboutLibrary(
    val id: String, val name: String, val version: String, val license: String,
    val url: String, val licenseUrl: String, val notices: List<String>, val other: Boolean,
)

internal fun loadAboutLibraries(assets: AssetManager): List<AboutLibrary> {
    val entries = JSONArray(assets.open("licenses/catalog.json").bufferedReader().use { it.readText() })
    return List(entries.length()) { index ->
        val item = entries.getJSONObject(index)
        val notices = item.getJSONArray("notices")
        AboutLibrary(item.getString("id"), item.getString("name"), item.getString("version"),
            item.getString("license"), item.getString("url"), item.getString("licenseUrl"),
            List(notices.length()) { notices.getString(it) }, item.getBoolean("other"))
    }
}

@Composable
internal fun OpenSourcePage() {
    val assets = LocalContext.current.applicationContext.assets
    var attempt by remember { mutableIntStateOf(0) }
    val catalogue by produceState<Result<List<AboutLibrary>>?>(null, assets, attempt) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { loadAboutLibraries(assets) } }
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val all = catalogue?.getOrNull().orEmpty()
    val visible = remember(all, query) {
        all.filter { query.isBlank() || "${it.name} ${it.id} ${it.license}".contains(query.trim(), ignoreCase = true) }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("licenses-list"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item("intro") {
            Text(stringResource(R.string.about_licenses_intro), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        }
        item("search") {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text(stringResource(R.string.about_search)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("licenses-search"))
        }
        if (catalogue == null) item("loading") {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth().padding(24.dp))
        }
        if (catalogue?.isFailure == true) item("error") {
            Column(Modifier.padding(24.dp)) {
                Text(stringResource(R.string.about_load_failed))
                TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.about_retry)) }
            }
        }
        if (catalogue?.isSuccess == true && visible.isEmpty()) item("empty") {
            Text(stringResource(R.string.about_no_matches), modifier = Modifier.padding(24.dp))
        }
        listOf(false, true).forEach { other ->
            val group = visible.filter { it.other == other }
            if (group.isNotEmpty()) {
                item("group-$other") {
                    Text(stringResource(if (other) R.string.about_other_components else R.string.about_libraries),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp).semantics { heading() })
                }
                itemsIndexed(group, key = { _, library -> library.id }, contentType = { _, _ -> "library" }) { index, library ->
                    SegmentedListItem(onClick = { selectedId = library.id },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp).testTag("license-${library.id}"),
                        shapes = ListItemDefaults.segmentedShapes(index, group.size),
                        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        supportingContent = {
                            Column {
                                if (library.version.isNotBlank()) Text(stringResource(R.string.about_component_version, library.version))
                                Text(library.license.ifEmpty { stringResource(R.string.about_other_license) })
                            }
                        },
                    ) { Text(library.name, style = MaterialTheme.typography.bodyLargeEmphasized) }
                }
            }
        }
    }
    all.firstOrNull { it.id == selectedId }?.let { library ->
        LibraryLicenseDialog(library, assets) { selectedId = null }
    }
}

@Composable
private fun LibraryLicenseDialog(library: AboutLibrary, assets: AssetManager, onClose: () -> Unit) {
    val openLink = rememberAboutLinkOpener()
    val notice by produceState<Result<String>?>(null, library.id, assets) {
        value = withContext(Dispatchers.IO) {
            runCatching { library.notices.joinToString("\n\n") { path ->
                assets.open("licenses/$path").bufferedReader().use { it.readText() }
            } }
        }
    }
    AlertDialog(onDismissRequest = onClose, title = { Text(library.name) },
        modifier = Modifier.testTag("license-dialog"),
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                if (library.version.isNotBlank()) Text(stringResource(R.string.about_component_version, library.version))
                Text(library.license.ifEmpty { stringResource(R.string.about_other_license) })
                if (library.other) Text(stringResource(R.string.about_terms_notice), modifier = Modifier.padding(top = 12.dp))
                TextButton(onClick = { openLink(library.url) }, modifier = Modifier.testTag("license-project-link")) {
                    Text(stringResource(R.string.about_project))
                }
                if (library.licenseUrl.isNotBlank()) TextButton(onClick = { openLink(library.licenseUrl) }) {
                    Text(stringResource(R.string.about_license_link))
                }
                when {
                    notice == null -> Text(stringResource(R.string.about_loading))
                    notice?.isFailure == true -> Text(stringResource(R.string.about_load_failed))
                    else -> SelectionContainer { Text(notice?.getOrNull().orEmpty(), style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose, modifier = Modifier.testTag("license-close")) { Text(stringResource(R.string.about_close)) } })
}
