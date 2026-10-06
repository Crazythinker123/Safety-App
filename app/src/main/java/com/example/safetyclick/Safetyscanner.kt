package com.example.safetyclick

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.PowerManager
import androidx.core.content.ContextCompat

class SafetyScanner(private val context: Context) {

    // Check if GPS is physically ON
    fun isGpsEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    // Check for critical permissions
    fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    }

    fun isBatteryOptimized(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun saveEmergencyContact(name: String, number: String) {
        val prefs = context.getSharedPreferences("SentinelPrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("emergency_name", name)
            .putString("emergency_number", number)
            .apply()
    }

    fun getEmergencyContactNumber(): String? {
        val prefs = context.getSharedPreferences("SentinelPrefs", Context.MODE_PRIVATE)
        return prefs.getString("emergency_number", null)
    }

    fun getEmergencyContactName(): String? {
        val prefs = context.getSharedPreferences("SentinelPrefs", Context.MODE_PRIVATE)
        return prefs.getString("emergency_name", null)
    }
}