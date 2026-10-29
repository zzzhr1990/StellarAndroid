package cn.coldlake.stellar.opensource.medialibrary.servers

import androidx.activity.compose.BackHandler
import com.stellar.player.ui.LocalPageActive
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.medialibrary.library.RevealDeleteRow

internal fun ServerKind.label() = when (this) {
    ServerKind.WEBDAV -> R.string.server_webdav
    ServerKind.SMB -> R.string.server_smb
    ServerKind.NFS -> R.string.server_nfs
    ServerKind.EMBY -> R.string.server_emby
    ServerKind.JELLYFIN -> R.string.server_jellyfin
    ServerKind.ONEDRIVE -> R.string.server_onedrive
    ServerKind.GOOGLE_DRIVE -> R.string.server_google_drive
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ServerTab(mediaServers: Boolean, state: ServersState, controller: ServersController,
    onOpen: (ServerProfile) -> Unit = {}) {
    val visibleServers = remember(state.servers, mediaServers) { state.servers.filter { it.kind.mediaServer == mediaServers } }
    val snackbar = remember { SnackbarHostState() }
    val deletedMessage = state.deletedName?.let { stringResource(R.string.server_deleted, it) }
    LaunchedEffect(deletedMessage) {
        if (deletedMessage != null) {
            snackbar.showSnackbar(deletedMessage)
            controller.clearDeletionNotice()
        }
    }
    Box(Modifier.fillMaxSize().testTag(if (mediaServers) "media-library-media-servers-content" else "media-library-remote-content")) {
        if (visibleServers.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxSize().testTag("server-list"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(visibleServers, key = { _, server -> server.id }, contentType = { _, _ -> "server" }) { index, server ->
                    val shapes = ListItemDefaults.segmentedShapes(index, visibleServers.size)
                    if (mediaServers) RevealDeleteRow(server.id, !state.deleting, { controller.requestDelete(server.id) }, shape = shapes.shape) {
                        ServerRow(server, shapes, onOpen = { onOpen(server) }, onEdit = { controller.edit(server) }, onDelete = { controller.requestDelete(server.id) })
                    } else ServerRow(server, shapes, onOpen = { onOpen(server) }, onEdit = { controller.edit(server) }, onDelete = { controller.requestDelete(server.id) })
                }
            }
        } else Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!state.loaded) CircularProgressIndicator()
            else {
                Text(stringResource(if (state.listError) R.string.server_storage_error else if (mediaServers) R.string.server_media_servers_empty else R.string.server_remote_empty),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.listError) TextButton(onClick = controller::reload) { Text(stringResource(R.string.server_retry)) }
            }
        }
        FloatingActionButton(onClick = { if (mediaServers) controller.chooseMediaServer() else controller.chooseStorage() }, modifier = Modifier.align(Alignment.BottomEnd)
            .padding(16.dp).testTag(if (mediaServers) "add-media-server" else "add-remote")) {
            Icon(painterResource(R.drawable.material_symbol_add), stringResource(if (mediaServers) R.string.server_add_media_server else R.string.server_add_remote))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
    }
    state.pendingDelete?.let { server ->
        AlertDialog(onDismissRequest = controller::dismissDelete,
            modifier = Modifier.testTag("server-delete-dialog"),
            properties = DialogProperties(dismissOnBackPress = !state.deleting, dismissOnClickOutside = !state.deleting),
            icon = { Icon(painterResource(R.drawable.material_symbol_delete), null) },
            title = { Text(stringResource(R.string.server_delete_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.server_delete_message, server.name))
                if (state.deleting) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.deleteError) Text(stringResource(R.string.server_delete_error), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("server-delete-error"))
            } },
            confirmButton = { TextButton(onClick = controller::confirmDelete, enabled = !state.deleting,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("server-delete-confirm")) { Text(stringResource(R.string.server_delete)) } },
            dismissButton = { TextButton(onClick = controller::dismissDelete, enabled = !state.deleting,
                modifier = Modifier.testTag("server-delete-cancel")) { Text(stringResource(R.string.media_library_cancel)) } })
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ServerRow(server: ServerProfile, shapes: ListItemShapes, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val openLabel = stringResource(R.string.browser_open_server, server.name)
    SegmentedListItem(
        onClick = onOpen,
        shapes = shapes,
        modifier = Modifier.fillMaxWidth().testTag("server-row-${server.id}").semantics {
            role = Role.Button
            onClick(label = openLabel, action = null)
        },
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        leadingContent = { ServerServiceIcon(server.kind) },
        supportingContent = {
            Text(stringResource(server.kind.label()), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = { Box {
            IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp).testTag("server-menu-${server.id}")) {
                Icon(painterResource(R.drawable.material_symbol_more_vert), stringResource(R.string.server_actions, server.name))
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.server_edit)) },
                    leadingIcon = { Icon(painterResource(R.drawable.material_symbol_edit), null) },
                    modifier = Modifier.testTag("server-menu-edit"), onClick = { expanded = false; onEdit() })
                DropdownMenuItem(text = { Text(stringResource(R.string.server_delete)) },
                    leadingIcon = { Icon(painterResource(R.drawable.material_symbol_delete), null) },
                    modifier = Modifier.testTag("server-menu-delete"), onClick = { expanded = false; onDelete() })
            }
        } },
    ) { Text(server.name, style = MaterialTheme.typography.bodyLargeEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
internal fun ServerForm(state: ServersState, controller: ServersController) {
    val profile = state.draft ?: return
    if (profile.kind == ServerKind.ONEDRIVE) {
        cn.coldlake.stellar.opensource.medialibrary.onedrive.OneDriveServerForm(state, controller)
        return
    }
    if (profile.kind == ServerKind.GOOGLE_DRIVE) {
        cn.coldlake.stellar.opensource.medialibrary.drive.DriveServerForm(state, controller)
        return
    }
    val scroll = rememberScrollState()
    BackHandler(LocalPageActive.current && state.busy != null) { controller.cancel() }
    LaunchedEffect(state.error) { if (state.error != null) scroll.animateScrollTo(0) }
    Column(Modifier.fillMaxSize().imePadding().testTag("server-form-${profile.kind.name}")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = controller::back, enabled = state.busy == null, modifier = Modifier.testTag("server-back")) {
                Icon(painterResource(R.drawable.material_symbol_arrow_back), stringResource(R.string.server_back))
            }
            Text(stringResource(if (profile.createdAt == 0L) R.string.server_add_type else R.string.server_edit_type,
                stringResource(profile.kind.label())), fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
        }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { Text(stringResource(it.message()), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("server-error")) }
            ServerField(profile.name, R.string.server_name, "server-name") { controller.edit(profile.copy(name = it)) }
            ServerField(profile.address, if (profile.kind == ServerKind.SMB || profile.kind == ServerKind.NFS) R.string.server_host else R.string.server_url,
                "server-address", KeyboardType.Uri) { controller.edit(profile.copy(address = it)) }
            if (profile.kind == ServerKind.SMB || profile.kind == ServerKind.NFS) {
                ServerField(profile.port, R.string.server_port, "server-port", KeyboardType.Number) { controller.edit(profile.copy(port = it)) }
                if (profile.kind == ServerKind.SMB) ServerField(profile.share, R.string.server_share, "server-share") { controller.edit(profile.copy(share = it)) }
                ServerField(profile.path, if (profile.kind == ServerKind.NFS) R.string.server_export else R.string.server_directory,
                    "server-path") { controller.edit(profile.copy(path = it)) }
            }
            if (profile.kind != ServerKind.NFS) {
                ServerField(profile.username, R.string.server_username, "server-username") { controller.edit(profile.copy(username = it)) }
                key(profile.id) {
                    ServerField(profile.password, R.string.server_password, "server-password", KeyboardType.Password) { controller.edit(profile.copy(password = it)) }
                }
                if (profile.kind == ServerKind.SMB) ServerField(profile.domain, R.string.server_domain, "server-domain") { controller.edit(profile.copy(domain = it)) }
            } else {
                Text(stringResource(R.string.server_nfs_version), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(3, 4).forEach { version -> FilterChip(selected = profile.nfsVersion == version,
                        onClick = { controller.edit(profile.copy(nfsVersion = version)) },
                        label = { Text(stringResource(if (version == 3) R.string.server_nfs_v3 else R.string.server_nfs_v4)) }) }
                }
                ServerField(profile.uid, R.string.server_uid, "server-uid", KeyboardType.Number) { controller.edit(profile.copy(uid = it)) }
                ServerField(profile.gid, R.string.server_gid, "server-gid", KeyboardType.Number) { controller.edit(profile.copy(gid = it)) }
            }
            if (profile.kind.supported) {
                Text(stringResource(when (profile.kind) {
                    ServerKind.WEBDAV -> R.string.server_webdav_hint
                    ServerKind.SMB -> R.string.server_smb_hint
                    ServerKind.EMBY -> R.string.emby_login_hint
                    ServerKind.JELLYFIN -> R.string.jellyfin_login_hint
                    else -> R.string.server_nfs_hint
                }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
        }
        Surface(color = MaterialTheme.colorScheme.surface) {
            Button(onClick = controller::confirm, enabled = profile.kind.supported && state.busy == null && state.loaded,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 48.dp).testTag("server-confirm")) {
                Text(stringResource(if (profile.kind.mediaServer) R.string.emby_login else if (profile.kind.remote) R.string.server_confirm else R.string.server_not_available))
            }
        }
    }
    state.busy?.let { busy ->
        AlertDialog(onDismissRequest = { if (busy == ServerBusy.CHECKING) controller.cancel() },
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = busy == ServerBusy.CHECKING),
            modifier = Modifier.testTag("server-check-dialog"),
            title = { Text(stringResource(if (busy == ServerBusy.CHECKING) R.string.server_checking else R.string.server_saving)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.server_check_hint))
            } },
            confirmButton = {}, dismissButton = {
                if (busy == ServerBusy.CHECKING) TextButton(onClick = controller::cancel, modifier = Modifier.testTag("server-check-cancel")) {
                    Text(stringResource(R.string.media_library_cancel))
                }
            })
    }
}

@Composable
private fun ServerField(value: String, label: Int, tag: String, type: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    val password = type == KeyboardType.Password || type == KeyboardType.NumberPassword
    var passwordVisible by remember(type) { mutableStateOf(false) }
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth().testTag(tag), singleLine = true,
        label = { Text(stringResource(label)) },
        keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false),
        trailingIcon = if (password) {
            {
                IconButton(onClick = { passwordVisible = !passwordVisible },
                    modifier = Modifier.testTag("$tag-visibility")) {
                    Icon(painterResource(if (passwordVisible) R.drawable.material_symbol_visibility_off else R.drawable.material_symbol_visibility),
                        stringResource(if (passwordVisible) R.string.server_hide_password else R.string.server_show_password))
                }
            }
        } else null,
        visualTransformation = if (password && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None)
}

internal fun ServerError.message() = when (this) {
    ServerError.ONEDRIVE_AUTH -> R.string.onedrive_auth_error
    ServerError.ONEDRIVE_CONFIG -> R.string.onedrive_config_error
    ServerError.ONEDRIVE_ACCOUNT -> R.string.onedrive_account_error
    ServerError.ONEDRIVE_SERVICE -> R.string.onedrive_service_error
    ServerError.ONEDRIVE_SIGNIN -> R.string.onedrive_signin_error
    ServerError.DRIVE_AUTH -> R.string.drive_auth_error
    ServerError.DRIVE_CONFIG -> R.string.drive_config_error
    ServerError.INPUT -> R.string.server_input_error
    ServerError.AUTHENTICATION -> R.string.server_auth_error
    ServerError.PATH -> R.string.server_path_error
    ServerError.PERMISSION -> R.string.server_permission_error
    ServerError.TIMEOUT -> R.string.server_timeout_error
    ServerError.NETWORK -> R.string.server_network_error
    ServerError.TLS -> R.string.server_tls_error
    ServerError.PROTOCOL -> R.string.server_protocol_error
    ServerError.STORAGE -> R.string.server_storage_error
    ServerError.LIMIT -> R.string.browser_limit
}
