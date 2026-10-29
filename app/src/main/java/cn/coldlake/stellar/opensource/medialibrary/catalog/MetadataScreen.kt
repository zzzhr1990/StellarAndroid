package cn.coldlake.stellar.opensource.medialibrary.catalog

import com.stellar.player.ui.LocalPageActive
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.medialibrary.emby.EmbyItem
import cn.coldlake.stellar.opensource.medialibrary.servers.ServerProfile
import cn.coldlake.stellar.opensource.settings.SettingsPageToolbar
import kotlinx.coroutines.*

@Composable
internal fun MetadataScreen(runtime: LocalLibraryRuntime, card: CatalogCard? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val launchTask = rememberLibraryTaskLauncher()
    val metadata = runtime.metadata
    val scope = rememberCoroutineScope()
    val progress by metadata.progress.collectAsStateWithLifecycle()
    val scan by runtime.status.collectAsStateWithLifecycle()
    val locale = metadataLanguage(LocalConfiguration.current.locales[0].toLanguageTag())
    var input by remember(card?.id) { mutableStateOf<MetadataInput?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    var query by rememberSaveable(card?.id) { mutableStateOf(card?.fileName.orEmpty()) }
    var title by rememberSaveable(card?.id) { mutableStateOf(card?.title.orEmpty()) }
    var year by rememberSaveable(card?.id) { mutableStateOf(card?.year?.toString().orEmpty()) }
    var plot by rememberSaveable(card?.id) { mutableStateOf(card?.plot.orEmpty()) }
    var candidates by remember { mutableStateOf<List<MetadataRecord>>(emptyList()) }
    var candidateQuery by remember { mutableStateOf<NameQuery?>(null) }
    var servers by remember { mutableStateOf<List<ServerProfile>>(emptyList()) }
    var selectedServer by remember { mutableStateOf<ServerProfile?>(null) }
    var libraries by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
    var importScopes by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedScope by rememberSaveable { mutableStateOf<String?>(null) }
    var imported by remember { mutableStateOf<List<Pair<Long, MetadataRecord>>>(emptyList()) }
    fun task(block: suspend () -> Unit) {
        if (busy) return
        job = scope.launch {
            busy = true; message = 0
            try { withContext(Dispatchers.IO) { block() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = R.string.metadata_failed }
            finally { busy = false }
        }
    }
    LaunchedEffect(card?.id, locale) {
        runtime.awaitReady()
        if (card == null) metadata.restoreProgress(locale)
        input = card?.let { metadata.input(it.id) }
        servers = metadata.profiles(); importScopes = metadata.importedScopes()
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val captured = input
        if (uri != null && captured != null) task {
            if (!metadata.importFile(captured, uri)) throw MetadataFailure("stale")
            input = metadata.input(captured.id); message = R.string.metadata_saved
        }
    }
    Column(Modifier.fillMaxSize().testTag("metadata-page")) {
        SettingsPageToolbar(R.string.metadata_title, onBack, "metadata-back")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("service") {
                Text(stringResource(R.string.metadata_service), style = MaterialTheme.typography.bodyMedium)
                if (cn.coldlake.stellar.opensource.BuildConfig.TMDB_API_KEY.isBlank() && cn.coldlake.stellar.opensource.BuildConfig.TMDB_READ_ACCESS_TOKEN.isBlank())
                    Text(stringResource(R.string.metadata_api_missing), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.metadata_scope), style = MaterialTheme.typography.bodySmall)
                if (message != 0) Text(stringResource(message), color = if (message == R.string.metadata_failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.scan_cancel)) }
                }
            }
            if (card == null) item("identify") {
                Text(stringResource(R.string.metadata_progress, progress.processed, progress.matched, progress.review, progress.failed))
                if (progress.waiting > 0 || progress.waitingUntil > 0) Text(stringResource(
                    if (progress.running) R.string.metadata_waiting else R.string.metadata_waiting_paused, progress.waiting))
                if (progress.running) TextButton(onClick = { metadata.stop() }) { Text(stringResource(R.string.scan_cancel)) }
                else Button(onClick = { launchTask { LibraryScanService.identify(context, locale) } }, enabled = !scan.running && !busy,
                    modifier = Modifier.testTag("metadata-identify")) { Text(stringResource(R.string.metadata_identify)) }
                TextButton(onClick = { launchTask { task { metadata.retryQueries(); LibraryScanService.identify(context, locale); message = R.string.metadata_retry_ready } } }, enabled = !progress.running && !busy && !scan.running) {
                    Text(stringResource(R.string.metadata_retry))
                }
            }
            if (card != null) {
                item("search") {
                    Text(card.fileName, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.metadata_query)) },
                        modifier = Modifier.fillMaxWidth().testTag("metadata-query"), singleLine = true)
                    Button(onClick = { task {
                        val q = MediaName.parse(query)
                        val result = metadata.provider.search(q, locale, input?.let { runtime.catalog.metadataScope(it.id) }.orEmpty())
                        candidateQuery = q
                        candidates = (listOfNotNull(result.selected) + result.alternatives).distinctBy { it.identity }
                        if (candidates.isEmpty()) message = R.string.metadata_no_match
                    } }, enabled = !busy && query.isNotBlank(), modifier = Modifier.testTag("metadata-search")) { Text(stringResource(R.string.metadata_search)) }
                }
                items(candidates, key = { "candidate:${it.identity}" }) { candidate ->
                    OutlinedButton(onClick = { task {
                        val snapshot = input ?: throw MetadataFailure("source")
                        val resolved = metadata.provider.resolve(requireNotNull(candidateQuery), candidate, locale)
                        if (!metadata.apply(snapshot, resolved)) throw MetadataFailure("stale")
                        input = metadata.input(card.id); title = resolved.title; year = resolved.year?.toString().orEmpty(); plot = resolved.plot.orEmpty()
                        candidates = emptyList(); message = R.string.metadata_saved
                    } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.metadata_candidate, candidate.title, candidate.year?.toString().orEmpty()))
                    }
                }
                item("edit") {
                    OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.metadata_name)) },
                        modifier = Modifier.fillMaxWidth().testTag("metadata-name"))
                    OutlinedTextField(year, { year = it }, label = { Text(stringResource(R.string.metadata_year)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(plot, { plot = it }, label = { Text(stringResource(R.string.metadata_plot)) }, modifier = Modifier.fillMaxWidth(), maxLines = 5)
                    Button(onClick = { task {
                        val snapshot = input ?: throw MetadataFailure("source")
                        if (year.isNotBlank() && year.toIntOrNull() !in 1800..2200) throw MetadataFailure("year")
                        val kind = if (card.kind == "file") "movie" else card.kind
                        val base = metadata.saved(card.id) ?: snapshot.local ?: MetadataRecord("manual:${card.id}", kind, card.title,
                            card.year, card.plot, card.show, card.season, card.episode?.let(::listOf).orEmpty(), locale = locale)
                        val data = base.copy(title = title.trim(), year = year.toIntOrNull(), plot = plot.trim(), fieldSources = null,
                            details = runtime.catalog.details(card.id),
                            seriesDetails = if (card.kind == "episode") runtime.catalog.details(card.id, true) else base.seriesDetails)
                        if (!metadata.apply(snapshot, data)) throw MetadataFailure("stale")
                        input = metadata.input(card.id); message = R.string.metadata_saved
                    } }, enabled = !busy && title.isNotBlank() && input != null, modifier = Modifier.testTag("metadata-save")) {
                        Text(stringResource(R.string.metadata_save))
                    }
                    TextButton(onClick = { filePicker.launch(arrayOf("text/xml", "application/xml", "application/octet-stream", "text/plain")) }, enabled = !busy && input != null) {
                        Text(stringResource(R.string.metadata_import_file))
                    }
                    TextButton(onClick = { task { metadata.reset(card.id); input = metadata.input(card.id); message = R.string.metadata_reset_done } }, enabled = !busy,
                        modifier = Modifier.testTag("metadata-reset")) { Text(stringResource(R.string.metadata_reset)) }
                }
            }
            item("imports-label") { Text(stringResource(R.string.metadata_import_server), style = MaterialTheme.typography.titleMedium) }
            items(servers, key = { "server:${it.id}" }) { server ->
                OutlinedButton(onClick = { task { selectedServer = server; libraries = metadata.libraries(server) } }, enabled = !busy) { Text(server.name) }
            }
            items(libraries, key = { "library:${it.id}" }) { library ->
                TextButton(onClick = { task {
                    val server = selectedServer ?: return@task
                    val result = metadata.importServer(server, library.id)
                    importScopes = metadata.importedScopes()
                    selectedScope = ServerCatalogImporter.scope(server, library.id)
                    imported = metadata.importedPage(requireNotNull(selectedScope))
                    message = if (result == "complete") R.string.metadata_import_done else R.string.metadata_import_partial
                } }, enabled = !busy) { Text(stringResource(R.string.metadata_import_library, library.name)) }
            }
            item("staging") { Text(stringResource(R.string.metadata_staging), style = MaterialTheme.typography.bodySmall) }
            items(importScopes, key = { "scope:$it" }) { importedScope ->
                val index = importScopes.indexOf(importedScope) + 1
                TextButton(onClick = { task { selectedScope = importedScope; imported = metadata.importedPage(importedScope) } }, enabled = !busy) {
                    Text(stringResource(R.string.metadata_import_set, index))
                }
            }
            items(imported, key = { "record:${it.first}" }) { (_, record) ->
                if (card == null) Text(record.title, style = MaterialTheme.typography.bodyMedium)
                else OutlinedButton(onClick = { task {
                    val snapshot = input ?: throw MetadataFailure("source")
                    if (record.kind !in setOf("movie", "episode")) throw MetadataFailure("kind")
                    if (!metadata.apply(snapshot, record)) throw MetadataFailure("stale")
                    input = metadata.input(card.id); message = R.string.metadata_saved
                } }, enabled = !busy && record.kind in setOf("movie", "episode")) { Text(record.title) }
            }
            if (imported.size == 60) item("next") {
                TextButton(onClick = { task { imported = metadata.importedPage(requireNotNull(selectedScope), imported.last().first) } }, enabled = !busy) {
                    Text(stringResource(R.string.scan_next))
                }
            }
        }
    }
}
