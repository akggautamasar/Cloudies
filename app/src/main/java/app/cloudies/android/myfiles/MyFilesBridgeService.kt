package app.cloudies.android.myfiles

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.cloudies.android.MainActivity
import app.cloudies.android.R
import app.cloudies.android.cloudies
import app.cloudies.android.storage.Connection
import app.cloudies.android.storage.ConnectionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class MyFilesBridgeStatus(val starting: Boolean = false, val running: Boolean = false, val message: String? = null)
data class MyFilesLogin(val username: String, val password: String)

class MyFilesBridgeStore(context: Context) {
    private val store = ConnectionStore(context, "myfiles_bridge_credentials")
    fun login(): MyFilesLogin = synchronized(credentialLock) {
        val saved = store.all().firstOrNull()
        if (saved != null) return@synchronized MyFilesLogin(saved.username, saved.password)
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val password = bytes.joinToString("") { "%02x".format(it) }
        store.put(Connection("myfiles", "Samsung My Files", "local", username = "cloudies", password = password))
        MyFilesLogin("cloudies", password)
    }
    companion object {
        private val credentialLock = Any()
    }
}

object MyFilesBridge {
    const val PORT = 2121
    const val OPEN_SETUP = "app.cloudies.android.OPEN_MY_FILES_SETUP"
    private val mutable = MutableStateFlow(MyFilesBridgeStatus())
    val status = mutable.asStateFlow()
    internal fun update(value: MyFilesBridgeStatus) { mutable.value = value }

    @Synchronized fun start(context: Context) {
        if (mutable.value.running || mutable.value.starting) return
        update(MyFilesBridgeStatus(starting = true))
        try { ContextCompat.startForegroundService(context, Intent(context, MyFilesBridgeService::class.java)) }
        catch (_: Exception) { update(MyFilesBridgeStatus(message = "Could not start. Keep Cloudies open and try again.")) }
    }

    // stopService matches the explicit component, not a listener's object identity.
    @SuppressLint("ImplicitSamInstance")
    fun stop(context: Context) { context.stopService(Intent(context, MyFilesBridgeService::class.java)) }
}

/** Explicit user-started session. No boot receiver, LAN listener, or background auto-restart. */
class MyFilesBridgeService : Service() {
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val launchRequested = AtomicBoolean(false)
    private var foregroundReady = false
    @Volatile private var server: LoopbackFtpServer? = null
    @Volatile private var destroyed = false
    private val idleCheck = object : Runnable {
        override fun run() {
            val current = server ?: return
            if (System.nanoTime() - current.lastActivityNanos > TimeUnit.MINUTES.toNanos(15)) {
                MyFilesBridge.update(MyFilesBridgeStatus(message = "Connection stopped after 15 minutes without activity. Start it when needed."))
                stopSelf()
            } else handler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Samsung My Files connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 70, Intent(this, MainActivity::class.java).putExtra(MyFilesBridge.OPEN_SETUP, true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 71, Intent(this, MyFilesBridgeService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cloud_notification).setContentTitle("Cloudies in Samsung My Files")
            .setContentText("Read-only connection on this phone · tap for setup")
            .setContentIntent(open).setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", stop).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(72, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(72, notification)
            foregroundReady = true
        } catch (_: Exception) {
            MyFilesBridge.update(MyFilesBridgeStatus(message = "Android could not start the connection. Reopen Cloudies and try again."))
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (!foregroundReady || destroyed || !launchRequested.compareAndSet(false, true)) return START_NOT_STICKY
        io.execute {
            var created: LoopbackFtpServer? = null
            try {
                check(cloudies.connections.all().isNotEmpty())
                val login = MyFilesBridgeStore(this).login()
                val bridge = LoopbackFtpServer(CloudBridgeFileSystem({ cloudies.connections.all() }, { cloudies.client(it) }, cloudies.ranges), login.username, login.password, MyFilesBridge.PORT)
                created = bridge
                synchronized(this) {
                    if (destroyed) { bridge.close(); return@execute }
                    bridge.start()
                    server = bridge
                    MyFilesBridge.update(MyFilesBridgeStatus(running = true))
                    handler.postDelayed(idleCheck, 60_000)
                }
            } catch (_: Exception) {
                created?.close()
                if (!destroyed) {
                    MyFilesBridge.update(MyFilesBridgeStatus(message = "Could not open the connection. Connect a cloud account and check that port 2121 is free, then retry."))
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        MyFilesBridge.update(MyFilesBridgeStatus(message = "Android stopped the connection at its background time limit. Reopen Cloudies to start again."))
        stopSelf()
    }

    override fun onDestroy() {
        synchronized(this) {
            destroyed = true
            handler.removeCallbacks(idleCheck)
            server?.close(); server = null
            io.shutdownNow()
            MyFilesBridge.update(MyFilesBridgeStatus(message = MyFilesBridge.status.value.message))
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "cloudies-myfiles"
        private const val STOP = "app.cloudies.android.STOP_MY_FILES"
    }
}
