package com.example.safetyclick

import android.app.*
import android.content.Intent
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that runs while Sentinel is armed.
 * Initialises both trigger sources:
 *   1. VolumeObserver  — physical volume-up hold on the phone
 *   2. BleManager      — button press on the ESP32 hardware device
 * Both delegate to the same SentinelTrigger instance.
 */
class SecurityService : Service() {

    private lateinit var volumeObserver: VolumeObserver
    private lateinit var bleManager:     BleManager
    private lateinit var trigger:        SentinelTrigger

    private val CHANNEL_ID      = "SentinelSecurityChannel"
    private val NOTIFICATION_ID = 1

    override fun onCreate() {
        super.onCreate()

        // ── Foreground notification ──────────────────────────────────────
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Monitoring active — hardware triggers armed."))

        // ── Shared trigger engine ────────────────────────────────────────
        trigger = SentinelTrigger(this)

        // ── Volume button observer ───────────────────────────────────────
        volumeObserver = VolumeObserver(Handler(Looper.getMainLooper()), this, trigger)
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        contentResolver.registerContentObserver(Settings.System.getUriFor("volume_music"),    true, volumeObserver)
        contentResolver.registerContentObserver(Settings.System.getUriFor("volume_music_bt"), true, volumeObserver)

        // ── BLE hardware device ──────────────────────────────────────────
        bleManager = BleManager(this) {
            Log.d("SENTINEL_SERVICE", "Hardware button trigger received from ESP32")
            trigger.fire()
        }
        bleManager.startScan()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(volumeObserver)
        bleManager.disconnect()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification helpers ─────────────────────────────────────────────────

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sentinel OS — Armed")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sentinel Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}