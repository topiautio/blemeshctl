package io.github.topiautio.blemeshctl.timer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.github.topiautio.blemeshctl.MainActivity
import io.github.topiautio.blemeshctl.R
import io.github.topiautio.blemeshctl.ble.BleMeshClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Runs timers and fades while the app is in the background or the screen is off.
 *
 * The activity queues a spec in [TimerHub] and starts this service; it stops itself when no timer
 * is active. Timers do not survive process death or a reboot. Mesh credentials live only in the
 * queued specs and the Bluetooth clients, never in a record or notification.
 */
class TimerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val coordinator get() = TimerHub.coordinator
    private val clients = HashMap<String, BleMeshClient>()
    private lateinit var texts: TimerTexts
    private lateinit var wakeLock: TimerWakeLock
    private lateinit var notifications: NotificationManager
    private val listener: (List<TimerRecord>) -> Unit = ::onRecords

    @Volatile
    private var lastStartId = 0

    @Volatile
    private var shownActive = -1

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        texts = TimerTexts(ResourceTextSource(this))
        wakeLock = TimerWakeLock.create(this)
        createChannels()
        coordinator.registry.addListener(listener)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // startForegroundService() requires this within seconds, so it comes before anything else.
        if (!enterForeground(coordinator.registry.list().count { it.state.isActive })) {
            val dropped = coordinator.discardPending()
            if (dropped > 0) postFailure(getString(R.string.timer_service_failed))
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_CANCEL_ALL -> coordinator.registry.cancelAll()
            else -> coordinator.drain(scope, ::senderFor).forEach { rejected ->
                Log.w(TAG, "timer rejected: ${rejected.spec.request.description}", rejected.error)
                postFailure(getString(R.string.timer_rejected, rejected.spec.request.description))
            }
        }
        onRecords(coordinator.registry.list())
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            scope.cancel()
            synchronized(clients) {
                clients.values.forEach { it.shutdown() }
                clients.clear()
            }
        } finally {
            wakeLock.close()
            coordinator.registry.removeListener(listener)
        }
        super.onDestroy()
    }

    /** Called on whichever thread changed a timer. */
    private fun onRecords(records: List<TimerRecord>) {
        coordinator.takeFinished(records).forEach { postFinished(it) }
        wakeLock.update(records.any { it.state == TimerState.RUNNING })
        val active = records.count { it.state.isActive }
        if (active > 0) {
            if (active != shownActive) enterForeground(active)
        } else if (!coordinator.hasPending()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(lastStartId)
        }
    }

    private fun senderFor(spec: TimerSpec): StepSender {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            ?: return StepSender { _, _ -> StepResult(StepStatus.FAILED, getString(R.string.timer_no_adapter)) }
        val channel = { light: LightRef -> BleLightChannel(clientFor(light.address, adapter), light, spec.credentials) }
        return if (spec.target.isGroup) {
            GroupSender(spec.target.lights, channel, ResourceTextSource(this))
        } else {
            SingleLightSender(channel(spec.target.lights.first()), ResourceTextSource(this))
        }
    }

    private fun clientFor(address: String, adapter: android.bluetooth.BluetoothAdapter): BleMeshClient =
        synchronized(clients) { clients.getOrPut(address.uppercase()) { BleMeshClient(this, adapter) } }

    /** Show the ongoing notification; false when Android refuses to start the foreground service. */
    private fun enterForeground(active: Int): Boolean {
        shownActive = active
        val notification = ongoingNotification(active)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(ONGOING_ID, notification)
            }
            true
        } catch (exception: Exception) {
            // Typically a SecurityException when Bluetooth permission is missing on Android 14.
            Log.w(TAG, "could not start foreground service", exception)
            false
        }
    }

    private fun ongoingNotification(active: Int): Notification {
        val cancelAll = PendingIntent.getService(
            this,
            0,
            Intent(this, TimerService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat_timer),
            getString(R.string.timer_notification_cancel_all),
            cancelAll,
        ).build()
        return Notification.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(texts.activeCount(active))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent())
            .addAction(action)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun postFinished(record: TimerRecord) {
        if (!canNotify()) return
        val notification = Notification.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(texts.finishedTitle(record))
            .setContentText(texts.finishedBody(record))
            .setStyle(Notification.BigTextStyle().bigText(texts.finishedBody(record)))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(openAppIntent())
            .build()
        val id = record.id.toIntOrNull(16)?.plus(FINISHED_ID_BASE) ?: record.id.hashCode()
        notifications.notify(id, notification)
    }

    @SuppressLint("MissingPermission")
    private fun postFailure(message: String) {
        if (!canNotify()) return
        val notification = Notification.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(getString(R.string.timer_heading))
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        notifications.notify(FAILURE_ID, notification)
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannels() {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, getString(R.string.timer_channel_ongoing), NotificationManager.IMPORTANCE_LOW),
        )
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, getString(R.string.timer_channel_result), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        const val ACTION_SCHEDULE = "io.github.topiautio.blemeshctl.action.SCHEDULE_TIMERS"
        const val ACTION_CANCEL_ALL = "io.github.topiautio.blemeshctl.action.CANCEL_ALL_TIMERS"
        private const val TAG = "TimerService"
        private const val CHANNEL_ONGOING = "timers_ongoing"
        private const val CHANNEL_RESULT = "timers_result"
        private const val ONGOING_ID = 1
        private const val FAILURE_ID = 2
        private const val FINISHED_ID_BASE = 1000

        /** Intent that makes the service take over the specs queued in [TimerHub]. */
        fun scheduleIntent(context: Context): Intent =
            Intent(context, TimerService::class.java).setAction(ACTION_SCHEDULE)
    }
}
