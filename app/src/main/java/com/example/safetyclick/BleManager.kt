package com.example.safetyclick

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

// ── Data model for a discovered BLE device ───────────────────────────────────

data class ScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val device: BluetoothDevice
)

// ── BLE Manager ──────────────────────────────────────────────────────────────

class BleManager(
    private val context: Context,
    private val onTriggered: () -> Unit = {}
) {
    companion object {
        const val SERVICE_UUID        = "12345678-1234-5678-1234-56789abcdef0"
        const val CHARACTERISTIC_UUID = "abcdef01-1234-5678-1234-56789abcdef0"
        private const val CCCD_UUID          = "00002902-0000-1000-8000-00805f9b34fb"
        private const val TAG                = "SENTINEL_BLE"
        private const val PREFS_NAME         = "SentinelPrefs"
        private const val PREFS_DEVICE_ADDR  = "ble_device_address"

        // Shared state observed by all composables
        private val _state = MutableStateFlow(BleState.DISCONNECTED)
        val state = _state.asStateFlow()

        private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
        val scannedDevices = _scannedDevices.asStateFlow()
    }

    enum class BleState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var gatt: BluetoothGatt? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false

    // ── Auto-connect (reads saved address, else discovery) ───────────────────

    fun startScan() {
        val saved = getSavedAddress()
        if (saved != null && hasPermissions()) {
            Log.d(TAG, "Auto-connecting to saved: $saved")
            _state.value = BleState.CONNECTING
            connect(bluetoothAdapter.getRemoteDevice(saved))
        } else {
            startDiscovery()
        }
    }

    // ── Manual discovery — populates scannedDevices list for the UI ──────────

    fun startDiscovery() {
        if (!hasPermissions()) return
        stopScan()
        _scannedDevices.value = emptyList()
        _state.value = BleState.SCANNING
        isScanning   = true
        Log.d(TAG, "Discovery started")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // No filter — scan all BLE devices, check name in callback
        bluetoothAdapter.bluetoothLeScanner?.startScan(null, settings, scanCallback)
        handler.postDelayed({ stopScan() }, 15_000)
    }

    fun stopScan() {
        if (!isScanning) return
        isScanning = false
        if (hasPermissions()) bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        if (_state.value == BleState.SCANNING) _state.value = BleState.DISCONNECTED
    }

    // Called when user taps a device in the scanner UI
    fun connectTo(device: BluetoothDevice) {
        stopScan()
        connect(device)
    }

    fun forgetDevice() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(PREFS_DEVICE_ADDR).apply()
        disconnect()
    }

    fun disconnect() {
        stopScan()
        gatt?.close()
        gatt = null
        _state.value = BleState.DISCONNECTED
    }

    fun getSavedAddress(): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREFS_DEVICE_ADDR, null)

    fun getSavedName(): String? {
        val addr = getSavedAddress() ?: return null
        return _scannedDevices.value.firstOrNull { it.address == addr }?.name
    }

    // ── Scan callback ────────────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!hasPermissions()) return

            val name = result.scanRecord?.deviceName
                ?: @Suppress("DEPRECATION") result.device.name
                ?: return // Skip devices with no name at all

            val current = _scannedDevices.value
            if (current.none { it.address == result.device.address }) {
                _scannedDevices.value = current + ScannedDevice(
                    name    = name,
                    address = result.device.address,
                    rssi    = result.rssi,
                    device  = result.device
                )
                Log.d(TAG, "Discovered: '$name' @ ${result.rssi} dBm")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan error: $errorCode")
            isScanning   = false
            _state.value = BleState.DISCONNECTED
        }
    }

    // ── GATT connection ──────────────────────────────────────────────────────

    private fun connect(device: BluetoothDevice) {
        if (!hasPermissions()) return
        _state.value = BleState.CONNECTING
        Log.d(TAG, "Connecting to ${device.address}")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected — discovering services")
                    // Save address for future auto-reconnect
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(PREFS_DEVICE_ADDR, g.device.address).apply()
                    if (hasPermissions()) g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT disconnected")
                    _state.value = BleState.DISCONNECTED
                    gatt?.close(); gatt = null
                    handler.postDelayed({ startScan() }, 4_000)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || !hasPermissions()) return

            val characteristic = g
                .getService(UUID.fromString(SERVICE_UUID))
                ?.getCharacteristic(UUID.fromString(CHARACTERISTIC_UUID))

            if (characteristic == null) {
                Log.w(TAG, "Trigger characteristic not found — device connected but no trigger support")
                handler.post { _state.value = BleState.CONNECTED }
                return
            }

            g.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(UUID.fromString(CCCD_UUID))
            descriptor?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(it)
                }
            }
            handler.post { _state.value = BleState.CONNECTED }
            Log.d(TAG, "Subscribed to trigger notifications ✓")
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) =
            handleValue(String(c.value))

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            handleValue(String(value))
    }

    private fun handleValue(value: String) {
        Log.d(TAG, "Received: $value")
        if (value == "SENTINEL_TRIGGER") handler.post { onTriggered() }
    }

    private fun hasPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)    == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
}
