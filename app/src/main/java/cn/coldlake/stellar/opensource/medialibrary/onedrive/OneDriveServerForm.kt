package cn.coldlake.stellar.opensource.medialibrary.onedrive

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import com.stellar.player.ui.LocalPageActive
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.medialibrary.servers.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OneDriveServerForm(state: ServersState, controller: ServersController) {
    val profile = state.draft ?: return
    val context = LocalContext.current
    var authorizing by rememberSaveable(profile.id) { mutableStateOf(false) }
    var attempted by rememberSaveable(profile.id) { mutableStateOf(false) }
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSession by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pendingId; pendingId = null; authorizing = false
        val session = pendingSession; pendingSession = null
        if (result.resultCode == Activity.RESULT_OK && id != null) {
            val accountId = result.data?.getStringExtra("account_id")
            val label = result.data?.getStringExtra("account")
            if (!accountId.isNullOrBlank() && !label.isNullOrBlank()) controller.oneDriveAuthorized(id, accountId, label, session)
        }
    }
    BackHandler(LocalPageActive.current && state.busy != null) { controller.cancel() }
    Column(Modifier.fillMaxSize().imePadding().testTag("server-form-ONEDRIVE")) {
        TopAppBar(title = { Text(stringResource(R.string.server_onedrive)) }, navigationIcon = {
            IconButton(onClick = controller::back, enabled = state.busy == null && !authorizing) {
                Icon(painterResource(R.drawable.material_symbol_arrow_back), stringResource(R.string.server_back))
            }
        })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.onedrive_hint))
            OutlinedTextField(profile.name, { controller.edit(profile.copy(name = it)) },
                label = { Text(stringResource(R.string.server_name)) }, singleLine = true,
                enabled = state.busy == null && !authorizing,
                isError = attempted && profile.name.isBlank(), modifier = Modifier.fillMaxWidth().testTag("server-name"))
            if (profile.username.isNotEmpty()) Text(stringResource(R.string.onedrive_account, profile.username))
            state.error?.let { Text(stringResource(it.message()), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("server-error")) }
            if (state.busy != null || authorizing) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Button(onClick = {
            attempted = true
            if (profile.name.isNotBlank() && profile.name.trim().length <= 120) {
                authorizing = true; pendingId = profile.id; pendingSession = controller.formSession
                launcher.launch(Intent(context, OneDriveAuthorizationActivity::class.java)
                    .putExtra("account_id", profile.cloudAccountId.takeIf(String::isNotEmpty)))
            }
        }, enabled = state.loaded && state.busy == null && !authorizing,
            modifier = Modifier.fillMaxWidth().padding(20.dp).testTag("onedrive-connect")) {
            Text(stringResource(R.string.onedrive_connect))
        }
        if (state.busy == ServerBusy.CHECKING) TextButton(onClick = controller::cancel,
            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.media_library_cancel)) }
    }
}
