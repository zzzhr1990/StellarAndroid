package cn.coldlake.stellar.opensource.platform

import android.content.*
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stellar.player.ui.R
import kotlinx.coroutines.delay
import java.util.Date
import java.text.NumberFormat

/** A leaf in the chrome: ticks and broadcasts never flow into the player, timeline or Surface. */
@Composable
fun BoxScope.FullscreenDeviceStatus(options: com.stellar.player.ui.PlaybackInteractionOptions) {
    if (!options.showTime && !options.showNetworkSpeed && !options.showBattery) return
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var battery by remember { mutableIntStateOf(-1) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var bytesPerSecond by remember { mutableLongStateOf(-1) }
    DisposableEffect(context, options.showBattery) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                battery = if (level >= 0 && scale > 0) level * 100 / scale else -1
            }
        }
        if (options.showBattery) context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { if (options.showBattery) context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(options.showTime, options.showNetworkSpeed) {
        if (!options.showTime && !options.showNetworkSpeed) return@LaunchedEffect
        var last = if (options.showNetworkSpeed) TrafficStats.getUidRxBytes(Process.myUid()) else -1
        var time = SystemClock.elapsedRealtime()
        while (true) {
            delay(1000)
            if (options.showTime) now = System.currentTimeMillis()
            val current = if (options.showNetworkSpeed) TrafficStats.getUidRxBytes(Process.myUid()) else -1
            val next = SystemClock.elapsedRealtime()
            bytesPerSecond = if (current >= last && last >= 0) (current - last) * 1000 / (next - time).coerceAtLeast(1) else -1
            last = current; time = next
        }
    }
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(20.dp).graphicsLayer().testTag("fullscreen-device-status")) {
        if (options.showTime) Text(DateFormat.getTimeFormat(context).format(Date(now)), color = Color(0xFFE7E0EC), fontSize = 12.sp,
            modifier = Modifier.align(Alignment.Center).testTag("fullscreen-time"))
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (options.showNetworkSpeed && bytesPerSecond >= 0) {
                val kilo = bytesPerSecond >= 1024
                Text(stringResource(if (kilo) R.string.hills_kbytes_second else R.string.hills_bytes_second,
                    NumberFormat.getIntegerInstance(locale).format(if (kilo) bytesPerSecond / 1024 else bytesPerSecond)),
                    color = Color(0xFFE7E0EC), fontSize = 12.sp, modifier = Modifier.testTag("fullscreen-network-speed"))
            }
            if (options.showBattery && battery >= 0) {
                Text(NumberFormat.getPercentInstance(locale).format(battery / 100.0), color = Color(0xFFE7E0EC), fontSize = 12.sp,
                    modifier = Modifier.testTag("fullscreen-battery"))
                Icon(painterResource(R.drawable.hills_battery_full), stringResource(R.string.hills_battery),
                    Modifier.size(18.dp).graphicsLayer { rotationZ = 90f }, tint = Color(0xFFE7E0EC))
            }
        }
    }
}
