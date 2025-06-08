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
import androidx.core.app.ActivityCompat
import java.util.*

class BluetoothLeManager(private val context: Context) {
    interface BleEventListener {
        fun onDeviceFound(deviceInfo: String)
        fun onScanStopped()
        fun onConnected(deviceName: String)
        fun onDisconnected()
        fun onDataReceived(data: ByteArray)
    }

    var listener: BleEventListener? = null
    private var isScanning = false
    private var bluetoothGatt: BluetoothGatt? = null

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    // Replace with your service and characteristic UUIDs from ESP32
    private val SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val CHAR_UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")

    // Device scan
    fun startScan() {
        if (isScanning) return
        if (!bluetoothAdapter.isEnabled) {
            listener?.onScanStopped()
            return
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            listener?.onScanStopped()
            return
        }
        isScanning = true
        scanner.startScan(scanCallback)
        Handler(Looper.getMainLooper()).postDelayed({ stopScan() }, 10_000)
    }

    fun stopScan() {
        if (!isScanning) return
        isScanning = false
        bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        listener?.onScanStopped()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: "Unknown"
            val info = "$name - ${device.address}"
            listener?.onDeviceFound(info)
        }
        override fun onScanFailed(errorCode: Int) {
            listener?.onScanStopped()
        }
    }

    fun connect(address: String) {
        val device = bluetoothAdapter.getRemoteDevice(address)
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener?.onConnected(gatt.device.name ?: "Unknown")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                listener?.onDisconnected()
            }
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
            characteristic?.let {
                gatt.setCharacteristicNotification(it, true)
                // For notifications on real devices: set CCCD descriptor here if needed
            }
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == CHAR_UUID) {
                listener?.onDataReceived(characteristic.value)
            }
        }
    }
}
