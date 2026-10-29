package cn.coldlake.stellar.opensource.network

import android.content.Context
import androidx.core.content.edit

/** Real network policy. Never migrate the old, presentation-only ignore_ssl preview value. */
internal class MediaSecuritySettings(context: Context) {
    // Retain the established real preference so upgrading preserves the user's choice.
    internal val preferences = context.applicationContext.getSharedPreferences("webdav_security_v1", Context.MODE_PRIVATE)

    var ignoreCertificateErrors: Boolean
        get() = preferences.getBoolean("ignore_certificate_errors", false)
        set(value) { preferences.edit { putBoolean("ignore_certificate_errors", value) } }
}
