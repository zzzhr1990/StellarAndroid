package cn.coldlake.stellar.opensource.medialibrary.catalog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import cn.coldlake.stellar.opensource.MainActivity
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.platform.LanguagePreference
import kotlinx.coroutines.*

class LibraryScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var task: Job? = null
    private var latestStartId = 0
    private var identifying = false
    private var pendingIdentify: Intent? = null
    private val runtime get() = LocalLibraryRuntime.get(this)
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(LanguagePreference.wrap(newBase)) }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == "pause" || intent?.action == "cancel") {
            pendingIdentify = null
            runtime.stop(intent.action == "cancel"); stopSelf(); return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.scan_title), NotificationManager.IMPORTANCE_LOW))
        fun action(name: String) = PendingIntent.getService(this, name.hashCode(), Intent(this, LibraryScanService::class.java).setAction(name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.material_symbol_folder)
            .setContentTitle(getString(R.string.scan_title)).setContentText(getString(R.string.scan_running))
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).addAction(Notification.Action.Builder(null, getString(R.string.scan_pause), action("pause")).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.scan_cancel), action("cancel")).build()).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(431, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(431, notification)
        } catch (_: Exception) { runtime.launchFailed(); stopSelf(); return START_NOT_STICKY }
        if (task?.isActive == true) {
            // Coalesce an identify request arriving during a scan. A resume of
            // the already active identify job needs no second full-library pass.
            if (intent?.action == "identify" && (!identifying || !intent.getBooleanExtra("resume", false)))
                pendingIdentify = Intent(intent)
        } else {
            val previous = task
            task = scope.launch {
                // A fast Pause -> Resume must wait for the cancelled owner to release its claim.
                previous?.join()
                try {
                    var request = intent
                    do {
                        identifying = request?.action == "identify"
                        if (identifying) runtime.identify(request?.getStringExtra("locale") ?: "en-US", request?.getBooleanExtra("resume", false) == true)
                        else runtime.runScan(request?.getBooleanExtra("resume", false) == true, request?.getBooleanExtra("force", false) == true)
                        request = pendingIdentify; pendingIdentify = null
                    } while (request != null)
                }
                finally {
                    identifying = false
                    if (task === currentCoroutineContext()[Job] && stopSelfResult(latestStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { runtime.stop(); stopSelf() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        private const val CHANNEL = "local_library_scan"
        internal fun identify(context: Context, locale: String, resume: Boolean = false) {
            try { context.startForegroundService(Intent(context, LibraryScanService::class.java).setAction("identify").putExtra("locale", locale).putExtra("resume", resume)) }
            catch (_: Exception) { LocalLibraryRuntime.get(context).launchFailed() }
        }
        internal fun start(context: Context, resume: Boolean = false, force: Boolean = false) {
            try { context.startForegroundService(Intent(context, LibraryScanService::class.java).putExtra("resume", resume).putExtra("force", force)) }
            catch (_: Exception) { LocalLibraryRuntime.get(context).launchFailed() }
        }
    }
}
