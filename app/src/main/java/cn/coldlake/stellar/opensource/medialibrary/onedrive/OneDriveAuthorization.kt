package cn.coldlake.stellar.opensource.medialibrary.onedrive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.medialibrary.servers.DirectoryFailure
import cn.coldlake.stellar.opensource.medialibrary.servers.ServerError
import cn.coldlake.stellar.opensource.medialibrary.servers.message
import kotlinx.coroutines.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** User-supplied, read-only OAuth tokens; encrypted locally with Android Keystore. */
internal object OneDriveAuthorization {
    private const val ALIAS = "stellar-opensource-onedrive"
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun preferences(context: Context) = context.getSharedPreferences("onedrive_tokens", Context.MODE_PRIVATE)
    suspend fun remember(context: Context, email: String, token: String) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        check(preferences(context).edit().putString(email, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
    suspend fun token(context: Context, email: String, force: Boolean = false): String = withContext(Dispatchers.IO) {
        val encoded = preferences(context).getString(email, null) ?: throw DirectoryFailure(ServerError.ONEDRIVE_AUTH)
        try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
                .doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
        } catch (_: Exception) { throw DirectoryFailure(ServerError.ONEDRIVE_AUTH) }
    }
    suspend fun invalidate(context: Context, email: String, token: String) = withContext(Dispatchers.IO) {
        // Do not remove a newer token submitted while an old request was in flight.
        if (runCatching { token(context, email) }.getOrNull() == token)
            preferences(context).edit().remove(email).commit()
        Unit
    }
}

class OneDriveAuthorizationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme {
            var token by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<ServerError?>(null) }
            val scope = rememberCoroutineScope()
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.onedrive_token_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.onedrive_token_help))
                    OutlinedTextField(token, { token = it }, label = { Text(stringResource(R.string.drive_token_label)) },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    error?.let { Text(stringResource(it.message()), color = MaterialTheme.colorScheme.error) }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Button(enabled = !busy && token.isNotBlank(), onClick = {
                        busy = true; error = null
                        val supplied = token.trim()
                        scope.launch {
                            try {
                                val account = OneDriveClient({ supplied }).use { it.account() }
                                val email = account.first
                                val expected = intent.getStringExtra("account_id")
                                if (!expected.isNullOrBlank() && !expected.equals(email, ignoreCase = true)) throw DirectoryFailure(ServerError.ONEDRIVE_AUTH)
                                OneDriveAuthorization.remember(applicationContext, email, supplied)
                                token = ""
                                setResult(Activity.RESULT_OK, Intent().putExtra("account_id", email).putExtra("account", account.second)); finish()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = (failure as? DirectoryFailure)?.reason ?: ServerError.NETWORK }
                            finally { busy = false }
                        }
                    }) { Text(stringResource(R.string.drive_connect)) }
                    TextButton(enabled = !busy, onClick = { setResult(Activity.RESULT_CANCELED); finish() }) { Text(stringResource(R.string.media_library_cancel)) }
                }
            }
        } }
    }
}
