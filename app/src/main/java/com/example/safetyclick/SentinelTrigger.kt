package com.example.safetyclick

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.google.firebase.database.FirebaseDatabase
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared trigger engine — called by BOTH the volume button observer
 * and the ESP32 BLE hardware button. Single source of truth.
 */
class SentinelTrigger(private val context: Context) {

    private val tag            = "SENTINEL_TRIGGER"
    private val lastTriggerAt  = AtomicLong(0L)
    private val COOLDOWN_MS    = 10_000L   // 10 s between triggers

    fun fire() {
        val now = System.currentTimeMillis()
        if (now - lastTriggerAt.get() < COOLDOWN_MS) {
            Log.d(tag, "Trigger suppressed — cooldown active")
            return
        }
        lastTriggerAt.set(now)

        try {
            // 1. Haptic confirmation
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(VibrationEffect.createOneShot(800, VibrationEffect.DEFAULT_AMPLITUDE))

            val scanner  = SafetyScanner(context)
            val contactNumber = scanner.getEmergencyContactNumber()
            val contactName   = scanner.getEmergencyContactName() ?: "Guardian"
            val database = FirebaseDatabase.getInstance().reference
                .child("emergency_alerts").push()

            // 2. Get GPS location, then send SMS + Firebase log
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            if (ActivityCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                fusedClient.lastLocation.addOnSuccessListener { location ->
                    val lat      = location?.latitude  ?: 0.0
                    val lng      = location?.longitude ?: 0.0
                    val mapsLink = "https://www.google.com/maps?q=$lat,$lng"

                    // 3. SMS alert
                    if (!contactNumber.isNullOrEmpty()) {
                        val sms = context.getSystemService(SmsManager::class.java)
                        sms.sendTextMessage(
                            contactNumber, null,
                            "EMERGENCY ($contactName): I need help! My location: $mapsLink",
                            null, null
                        )
                        Log.d(tag, "SMS dispatched to $contactNumber")
                    }

                    // 4. Firebase log
                    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    val signalLevel = try {
                        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                        tm.signalStrength?.level ?: -1
                    } catch (e: Exception) { -1 }

                    database.setValue(
                        mapOf(
                            "latitude"  to lat,
                            "longitude" to lng,
                            "battery"   to "${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%",
                            "signal"    to signalLevel,
                            "timestamp" to System.currentTimeMillis(),
                            "status"    to "SENTINEL_TRIGGERED"
                        )
                    )
                    Log.d(tag, "Firebase log written")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Trigger error: ${e.message}")
        }
    }
}
