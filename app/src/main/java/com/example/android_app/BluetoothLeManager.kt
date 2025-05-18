package com.example.android_app

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class BluetoothLeManager(private val context: Context) {
    var listener: BleEventListener? = null
    var isScanning = false
    private var hasStoppedScan = false

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
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

                if (foundDevices.add(deviceInfo)) {
                    Log.d("BLE", "Found device: $deviceInfo")
                    listener?.onDeviceFound(deviceInfo)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BLE", "Scan failed with error: $errorCode")
            listener?.onScanStopped()
        }
    }

    fun startScan() {
        if (isScanning) return
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

        if (!hasScanPermission) {
            Log.e("BLE", "Missing BLUETOOTH_SCAN permission")
            return
        }

        try {
            bluetoothAdapter.bluetoothLeScanner.startScan(scanCallback)
            Log.d("BLE", "Started scanning...")

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                Log.d("BLE", "Scan timeout reached. Stopping scan.")
                stopScan() // This will now only trigger once
            }, 10_000)
        } catch (e: SecurityException) {
            Log.e("BLE", "SecurityException while starting scan: ${e.message}")
        }
    }

    fun stopScan() {
        if (!isScanning || hasStoppedScan) return
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

        if (!hasConnectPermission) {
            Log.e("BLE", "Missing BLUETOOTH_CONNECT permission")
            return
        }

        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
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
                    // Check permission before calling discoverServices
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasPermission) {
                            gatt.discoverServices()
                        } else {
                            Log.e("BLE", "No BLUETOOTH_CONNECT permission to discover services")
                        }
                    } else {
                        gatt.discoverServices()
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
                // Interact with services/characteristics here
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
        } catch (e: SecurityException) {
            Log.e("BLE", "SecurityException while disconnecting: ${e.message}")
        }
    }

    interface BleEventListener {
        fun onDeviceFound(deviceInfo: String)
        fun onScanStopped()
        fun onConnected(deviceName: String)
        fun onDisconnected()
    }

}