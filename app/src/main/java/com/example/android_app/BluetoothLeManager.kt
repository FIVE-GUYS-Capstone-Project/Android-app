package com.example.android_app

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat

// Manager class to handle Bluetooth Low Energy scanning, connection, and callbacks
class BluetoothLeManager(private val context: Context) {

    var listener: BleEventListener? = null
    var isScanning = false
    private var hasStoppedScan = false

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private val foundDevices = mutableSetOf<String>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device

            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

            if (hasPermission) {
                val deviceName = device.name ?: "Unknown"
                val deviceAddress = device.address
                val deviceInfo = "$deviceName - $deviceAddress"

                // Optionally skip devices with no name to avoid "Unknown" spam
                if (deviceName == "Unknown") {
                    Log.w("BLE", "Skipping unnamed device: $deviceAddress")
                    return
                }

                if (foundDevices.add(deviceInfo)) {
                    Log.d("BLE", "Found device: $deviceInfo")
                    listener?.onDeviceFound(deviceInfo)
                }
            } else {
                Log.e("BLE", "Missing BLUETOOTH_CONNECT permission during scan")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val message = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                else -> "Unknown error"
            }
            Log.e("BLE", "Scan failed: $message ($errorCode)")
            listener?.onScanStopped()
        }
    }

    fun startScan() {
        if (isScanning) {
            Log.w("BLE", "Scan already in progress. Ignored.")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.e("BLE", "Bluetooth is not enabled.")
            Toast.makeText(context, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            listener?.onScanStopped()
            return
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e("BLE", "BluetoothLeScanner is null. Is Bluetooth supported?")
            listener?.onScanStopped()
            return
        }

        isScanning = true
        hasStoppedScan = false
        foundDevices.clear()

        val hasScanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        Log.d("BLE", "Scan permission: $hasScanPermission")

        if (!hasScanPermission) {
            Log.e("BLE", "Missing BLUETOOTH_SCAN permission")
            Toast.makeText(context, "Bluetooth scan permission denied", Toast.LENGTH_SHORT).show()
            listener?.onScanStopped()
            return
        }

        try {
            scanner.startScan(scanCallback)
            Log.d("BLE", "Started scanning...")
            Handler(Looper.getMainLooper()).postDelayed({
                stopScan()
            }, 10_000)
        } catch (e: SecurityException) {
            Log.e("BLE", "SecurityException while starting scan: ${e.message}")
            listener?.onScanStopped()
        }
    }

    fun stopScan() {
        if (!isScanning || hasStoppedScan) {
            Log.d("BLE", "Scan already stopped or not running.")
            return
        }
        hasStoppedScan = true
        isScanning = false

        val hasScanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (!hasScanPermission) {
            Log.e("BLE", "Missing BLUETOOTH_SCAN permission")
            return
        }

        try {
            bluetoothAdapter.bluetoothLeScanner.stopScan(scanCallback)
            Log.d("BLE", "Stopped scanning.")
        } catch (e: SecurityException) {
            Log.e("BLE", "SecurityException while stopping scan: ${e.message}")
        }

        listener?.onScanStopped()
    }

    fun connectToDevice(device: BluetoothDevice) {
        val hasConnectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        Log.d("BLE", "Connect permission: $hasConnectPermission")

        if (!hasConnectPermission) {
            Log.e("BLE", "Missing BLUETOOTH_CONNECT permission")
            Toast.makeText(context, "Bluetooth connect permission denied", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            if (bluetoothGatt != null) {
                Log.w("BLE", "Already connected or connecting. Disconnecting first.")
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
                bluetoothGatt = null
            }
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
            Log.d("BLE", "Connecting to device: ${device.address}")
        } catch (e: SecurityException) {
            Log.e("BLE", "SecurityException in connectGatt: ${e.message}")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d("BLE", "Connected to GATT server.")
                    listener?.onConnected(gatt.device.name ?: "Unknown")

                    val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    } else true

                    if (hasPermission) {
                        gatt.discoverServices()
                    } else {
                        Log.e("BLE", "No BLUETOOTH_CONNECT permission to discover services")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d("BLE", "Disconnected from GATT server.")
                    listener?.onDisconnected()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE", "Services discovered.")
            } else {
                Log.w("BLE", "onServicesDiscovered received: $status")
            }
        }
    }

    fun disconnect() {
        val hasConnectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (!hasConnectPermission) {
            Log.e("BLE", "Missing BLUETOOTH_CONNECT permission")
            return
        }

        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
            Log.d("BLE", "Disconnected and closed GATT.")
        } catch (e: SecurityException) {
            Log.e("BLE", "SecurityException while disconnecting: ${e.message}")
        }
    }

    fun getDeviceByAddress(address: String): BluetoothDevice? {
        return try {
            bluetoothAdapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Log.e("BLE", "Invalid MAC address: $address")
            null
        }
    }

    interface BleEventListener {
        fun onDeviceFound(deviceInfo: String)
        fun onScanStopped()
        fun onConnected(deviceName: String)
        fun onDisconnected()
    }
}
