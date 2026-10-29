package cn.coldlake.stellar.opensource.settings

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Values stay language-independent so a retained snapshot follows the current UI language. */
internal sealed interface DeviceInfoValue {
    data class Text(val text: String) : DeviceInfoValue
    data class Message(@param:StringRes val resource: Int) : DeviceInfoValue
    data class Bytes(val bytes: Long) : DeviceInfoValue
    data class Timestamp(val millis: Long) : DeviceInfoValue
    data class Number(val number: Double, @param:StringRes val format: Int) : DeviceInfoValue
    data class Lines(val values: List<DeviceInfoValue>) : DeviceInfoValue
}

internal data class DeviceInfoField(
    val key: String,
    @param:StringRes val title: Int,
    val value: DeviceInfoValue,
    val titleArguments: List<Any> = emptyList(),
)

internal data class DeviceInfoSection(
    val key: String,
    @param:StringRes val title: Int,
    val fields: List<DeviceInfoField>,
    val failed: Boolean = false,
)

internal data class DeviceCodecInfo(val name: String, val mime: String, val fields: List<DeviceInfoField>) {
    val key: String get() = "$name/$mime"
}

internal data class DeviceInfoSnapshot(
    val sampledAtMillis: Long,
    val durationMillis: Long,
    val sections: List<DeviceInfoSection>,
    val codecs: List<DeviceCodecInfo>,
    val codecsFailed: Boolean,
)

internal data class DeviceInfoState(
    val loading: Boolean = false,
    val snapshot: DeviceInfoSnapshot? = null,
    val failed: Boolean = false,
)

/** One bounded snapshot per Activity, no polling, disk cache, or Activity/Display reference. */
internal class DeviceInfoViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(DeviceInfoState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun ensureLoaded(displayId: Int) {
        if (state.value.snapshot == null && !state.value.failed) refresh(displayId)
    }

    fun refresh(displayId: Int) {
        if (job?.isActive == true) return
        mutableState.value = state.value.copy(loading = true, failed = false)
        job = viewModelScope.launch {
            try {
                val snapshot = DeviceInfoCollector(getApplication()).collect(displayId)
                mutableState.value = DeviceInfoState(snapshot = snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(loading = false, failed = true)
            }
        }
    }
}
