package com.example.safetyclick


import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioManager
import android.os.*
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.google.firebase.database.FirebaseDatabase


class VolumeObserver(handler: Handler, private val context: Context) : ContentObserver(handler) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var lastVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    private var originalVolumeBeforeHold = lastVolume


    private var lastTriggerTime: Long = 0
    private var isPressing = false
    private var hasTriggeredThisHold = false
    private val tag = "SENTINEL_ENGINE"
    private val mainHandler = Handler(Looper.getMainLooper())


    override fun onChange(selfChange: Boolean) {
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentTime = System.currentTimeMillis()


        // 1. Detect the UP press
        if (currentVolume > lastVolume || (currentVolume == maxVolume && lastVolume == maxVolume)) {
            if (!isPressing) {
                isPressing = true
                hasTriggeredThisHold = false
                originalVolumeBeforeHold = lastVolume
                Log.d(tag, "Hold Started at: $originalVolumeBeforeHold")


                // Start the 1.5s Trigger Timer
                mainHandler.postDelayed({
                    val now = System.currentTimeMillis()
                    if (isPressing && !hasTriggeredThisHold && (now - lastTriggerTime > 10000)) {
                        triggerSentinel()
                        lastTriggerTime = now
                        hasTriggeredThisHold = true


                        // ONLY START LOCKDOWN AFTER TRIGGER
                        startLockdownLoop()
                    }
                }, 1500)
            }
        } else if (currentVolume < lastVolume) {
            // Manual Volume Down resets everything
            isPressing = false
            hasTriggeredThisHold = false
        }


        lastVolume = currentVolume
    }


    private fun startLockdownLoop() {
        mainHandler.post(object : Runnable {
            override fun run() {
                // If they are still holding AFTER the 1.5s trigger, force it back
                if (isPressing) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolumeBeforeHold, 0)
                    mainHandler.postDelayed(this, 100) // Keep it locked every 100ms
                }
            }
        })
    }


    private fun triggerSentinel() {
        try {
            // Long Vibration Confirmation
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(VibrationEffect.createOneShot(800, VibrationEffect.DEFAULT_AMPLITUDE))


            val scanner = SafetyScanner(context)
            val contact = scanner.getEmergencyContact()
            val database = FirebaseDatabase.getInstance().reference.child("emergency_alerts").push()


            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedClient.lastLocation.addOnSuccessListener { location ->
                    val lat = location?.latitude ?: 0.0
                    val lng = location?.longitude ?: 0.0
                    val mapsLink = "https://www.google.com/maps?q=$lat,$lng"


                    if (!contact.isNullOrEmpty()) {
                        val smsManager = SmsManager.getDefault()
                        val msg = "EMERGENCY: I need help! My Location: $mapsLink"
                        smsManager.sendTextMessage(contact, null, msg, null, null)
                        Log.d(tag, "SMS Sent with Link")
                    }


                    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    database.setValue(mapOf(
                        "latitude" to lat,
                        "longitude" to lng,
                        "battery" to "${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%",
                        "timestamp" to System.currentTimeMillis(),
                        "status" to "SENTINEL_LONG_PRESS_SUCCESS"
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
        }
    }
}



