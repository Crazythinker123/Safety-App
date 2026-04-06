package com.example.safetyclick

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat

class SecurityService : Service() {

    private lateinit var volumeObserver: VolumeObserver
    private val CHANNEL_ID = "SentinelSecurityChannel"
    private val NOTIFICATION_ID = 1

    override fun onCreate() {
        super.onCreate()

        // 1. Setup Foreground Notification
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sentinel OS Protected")
            .setContentText("Hardware triggers are active and monitoring.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        // 2. Initialize Observer
        volumeObserver = VolumeObserver(Handler(Looper.getMainLooper()), this)

        // 3. MULTI-URI REGISTRATION (To catch the "First Click")
        val cr = contentResolver

        // This URI often fires even if the volume level hasn't changed yet
        cr.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)

        // Specific Music Stream URIs
        cr.registerContentObserver(Settings.System.getUriFor("volume_music"), true, volumeObserver)
        cr.registerContentObserver(Settings.System.getUriFor("volume_music_bt"), true, volumeObserver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(volumeObserver)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Sentinel Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}