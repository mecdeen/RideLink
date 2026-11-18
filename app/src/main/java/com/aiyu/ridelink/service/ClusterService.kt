package com.aiyu.ridelink.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.aiyu.ridelink.MainActivity
import com.aiyu.ridelink.core.ClusterController
import com.aiyu.ridelink.core.ble.LinkState
import com.aiyu.ridelink.core.settings.AppLocale
import com.aiyu.ridelink.core.settings.SettingsRepository
import com.aiyu.ridelink.ui.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Keeps the cluster session alive while the screen is off. */
@AndroidEntryPoint
class ClusterService : LifecycleService() {
    @Inject lateinit var controller: ClusterController
    @Inject lateinit var settings: SettingsRepository
    private var watchJob: Job? = null
    private var idleJob: Job? = null
    private var statusJob: Job? = null
    private var listenerJob: Job? = null
    private var calls: CallStateWatcher? = null
    /** Set while the Exit button winds the app down; the process ends with this service. */
    private var exitJob: Job? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    /**
     * Held while the display is connected, like stock BaseBleConnectHelperService.startWakelock (partial, 30 min).
     * Without it the phone sleeps with the screen off and the 2 s status, nav and caller frames stall.
     */
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RideLink:ride").apply {
            setReferenceCounted(false)
        }
    }
    private var wakeJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_EXIT) {
            exitApp()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            // The rider ended the session: stay off until they press Connect, even if the scooter is nearby.
            watchJob?.cancel()
            NearbyReceiver.watch(this, null)
            shutDown()
            return START_NOT_STICKY
        }
        // Opened again while exiting: the rider wants the app after all.
        exitJob?.cancel()
        exitJob = null
        startForegroundTyped(notification(getString(R.string.status_connecting)))
        running = true
        controller.start()
        // Armed while the session runs, so the scooter wakes RideLink again if Android kills it.
        if (watchJob == null) watchJob = lifecycleScope.launch {
            settings.settings.map { it.deviceAddress }.distinctUntilChanged().collect { NearbyReceiver.watch(this@ClusterService, it) }
        }
        // Scooter off or out of range: end the session (and its "Waiting for scooter" notification);
        // the nearby scan above starts it again at the next ignition on.
        if (idleJob == null) idleJob = lifecycleScope.launch {
            controller.linkState.collectLatest { state ->
                if (state is LinkState.Ready) return@collectLatest
                delay(IDLE_STOP_MS)
                shutDown()
            }
        }
        if (calls == null) calls = CallStateWatcher(this, controller).takeIf { it.start() }
        // Android can leave the listener unbound after an app update or Exit; without it no Maps or call events arrive.
        // The flag goes first: a listener that binds while it is still set unbinds itself again.
        lifecycleScope.launch {
            settings.setAppExited(false)
            RideNotificationListener.rebind(this@ClusterService)
        }
        if (listenerJob == null) listenerJob = lifecycleScope.launch { watchListener() }
        if (statusJob == null) statusJob = lifecycleScope.launch {
            controller.linkState.collect { state ->
                val text = when (state) {
                    is LinkState.Ready -> getString(R.string.status_connected, state.name ?: state.address)
                    is LinkState.Connecting -> getString(R.string.status_waiting)
                    is LinkState.Failed -> state.reason
                    LinkState.Idle -> getString(R.string.status_idle)
                }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
                if (state is LinkState.Ready) holdWakeLock() else releaseWakeLock()
            }
        }
        return START_STICKY
    }

    /**
     * Keeps Maps detection alive for the whole session: picks up updates the listener missed and reconnects it when
     * Android drops it. A plain rebind goes first; a binding that stays dead is forced.
     */
    private suspend fun watchListener() {
        var failures = 0
        while (true) {
            delay(LISTENER_CHECK_MS)
            val granted = packageName in NotificationManagerCompat.getEnabledListenerPackages(this)
            if (!granted || RideNotificationListener.sync()) {
                failures = 0
                continue
            }
            failures++
            if (failures == 1) {
                controller.note("Notification listener disconnected, reconnecting")
                RideNotificationListener.rebind(this)
            } else if (failures % FORCE_REBIND_EVERY == FORCE_REBIND_AFTER) {
                controller.note("Notification listener still disconnected, forcing a reconnect")
                RideNotificationListener.rebind(this, force = true)
            }
        }
    }

    /**
     * Also typed `location` when location is granted, so the parked spot, check-in links and weather get a fix
     * while the screen is off. Android refuses that type when the service is started from the background (boot):
     * then it runs as `connectedDevice` only, and the next start from the app adds location.
     */
    private fun startForegroundTyped(n: Notification) {
        val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val withLocation = Build.VERSION.SDK_INT >= 29 && hasLocation() && runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, device or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        }.isSuccess
        if (!withLocation) ServiceCompat.startForeground(this, NOTIFICATION_ID, n, device)
    }

    private fun shutDown() {
        calls?.stop()
        calls = null
        controller.stop()
        releaseWakeLock()
        stopSelf()
    }

    /**
     * Exit button: stops everything that keeps RideLink running or can start it again (session, nearby scan,
     * notification listener, boot start), then ends the process. After a live link the process stays for
     * [EXIT_WRAP_UP_MS], because the drop saves the ride, the parked spot and the check-in SMS in the background.
     */
    private fun exitApp() {
        if (exitJob != null) return
        val linked = controller.linkState.value is LinkState.Ready
        watchJob?.cancel()
        idleJob?.cancel()
        statusJob?.cancel()
        listenerJob?.cancel()
        watchJob = null
        idleJob = null
        statusJob = null
        listenerJob = null
        NearbyReceiver.watch(this, null)
        RideNotificationListener.release()
        calls?.stop()
        calls = null
        controller.stop()
        exitJob = lifecycleScope.launch {
            settings.setAppExited(true)
            if (linked) {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(getString(R.string.status_closing), canDisconnect = false))
                delay(EXIT_WRAP_UP_MS)
            }
            stopSelf()
        }
    }

    private fun hasLocation() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    /** Renewed before the timeout for as long as the link stays up, so a long ride keeps it. */
    private fun holdWakeLock() {
        if (wakeJob?.isActive == true) return
        wakeJob = lifecycleScope.launch {
            while (true) {
                wakeLock.acquire(WAKE_LOCK_MS)
                delay(WAKE_LOCK_MS / 2)
            }
        }
    }

    private fun releaseWakeLock() {
        wakeJob?.cancel()
        wakeJob = null
        if (wakeLock.isHeld) wakeLock.release()
    }

    override fun onDestroy() {
        running = false
        calls?.stop()
        releaseWakeLock()
        super.onDestroy()
        if (exitJob != null) Process.killProcess(Process.myPid())
    }

    private fun notification(text: String, canDisconnect: Boolean = true): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_ride), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ClusterService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_ridelink)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .apply { if (canDisconnect) addAction(0, getString(R.string.action_end_ride), stop) }
            .build()
    }

    companion object {
        private const val CHANNEL = "ride"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "stop"
        private const val ACTION_EXIT = "exit"
        private const val WAKE_LOCK_MS = 30 * 60_000L
        /** Rides out short drops (tunnel, restart) before ending the session. */
        private const val IDLE_STOP_MS = 60_000L
        /** Next recorder tick (5 s) + the longest location fix (30 s) + the place lookup. */
        private const val EXIT_WRAP_UP_MS = 40_000L
        private const val LISTENER_CHECK_MS = 5_000L
        /** Failed checks: forced at the 3rd, then once every 12 (a minute) while it stays dead. */
        private const val FORCE_REBIND_AFTER = 3
        private const val FORCE_REBIND_EVERY = 12

        /** True while this process runs the session; a killed process reads false again. */
        @Volatile var running = false
            private set

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, ClusterService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, ClusterService::class.java).setAction(ACTION_STOP))
        fun exit(context: Context) = context.startService(Intent(context, ClusterService::class.java).setAction(ACTION_EXIT))
    }
}
