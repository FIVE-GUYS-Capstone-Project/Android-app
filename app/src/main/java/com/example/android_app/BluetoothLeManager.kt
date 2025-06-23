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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class BluetoothLeManager(private val context: Context) {

    interface BleEventListener {
        fun onDeviceFound(deviceInfo: String)
        fun onScanStopped()
        fun onConnected(deviceName: String)
        fun onDisconnected()
        fun onDataReceived(length: Float, width: Float, height: Float)
    }

    var listener: BleEventListener? = null
    private var isScanning = false
    private var hasStoppedScan = false
    private val foundDevices = mutableSetOf<String>()
    private var bluetoothGatt: BluetoothGatt? = null

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    // === Replace with your real ESP32/Server UUIDs! ===
    private val SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val CHAR_UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")

    // ==== BLE Scanning ====
    fun startScan() {
        if (isScanning) return
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(context, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            listener?.onScanStopped()
            return
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e("BLE", "BluetoothLeScanner is null")
            listener?.onScanStopped()
            return
        }
        val hasScanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else true
        if (!hasScanPermission) {
            Toast.makeText(context, "Missing BLUETOOTH_SCAN permission", Toast.LENGTH_SHORT).show()
            listener?.onScanStopped()
            return
        }
        isScanning = true
        hasStoppedScan = false
        foundDevices.clear()
        try {
            scanner.startScan(scanCallback)
            Handler(Looper.getMainLooper()).postDelayed({ stopScan() }, 10_000)
            Log.d("BLE", "Started scanning...")
        } catch (e: SecurityException) {
            Log.e("BLE", "SecurityException while starting scan: ${e.message}")
            listener?.onScanStopped()
        }
    }

    fun stopScan() {
        if (!isScanning || hasStoppedScan) return
        hasStoppedScan = true
        isScanning = false
        val hasScanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else true
        if (!hasScanPermission) {
            Log.e("BLE", "Missing BLUETOOTH_SCAN permission")
            listener?.onScanStopped()
            return
        }
        try {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
            Log.d("BLE", "Stopped scanning.")
        } catch (e: SecurityException) {
            Log.e("BLE", "SecurityException while stopping scan: ${e.message}")
        }
        listener?.onScanStopped()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else true
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
            Log.e("BLE", "Scan failed: $errorCode")
            listener?.onScanStopped()
        }
    }

    // ==== BLE Connection ====
    fun connectToDevice(device: BluetoothDevice) {
        val hasConnectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
        if (!hasConnectPermission) {
            Toast.makeText(context, "Missing BLUETOOTH_CONNECT permission", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        } catch (e: SecurityException) {
            Log.e("BLE", "SecurityException in connectGatt: ${e.message}")
        }
    }

    fun disconnect() {
        val hasConnectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
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

    // ==== GATT Callback (includes binary data parsing) ====
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d("BLE", "Connected to GATT server.")
                    listener?.onConnected(gatt.device.name ?: "Unknown")
                    val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
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
                val charac = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
                charac?.let {
                    gatt.setCharacteristicNotification(it, true)
                    // Descriptor logic if needed
                }
            }
        }
        // Here: Parse 3 floats from BLE notification (length, width, height)
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == CHAR_UUID) {
                val data = characteristic.value
                if (data.size >= 12) {
                    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    val l = buffer.float
                    val w = buffer.float
                    val h = buffer.float
                    listener?.onDataReceived(l, w, h)
                }
            }
        }
    }

    // Utility: Get a BluetoothDevice object by MAC
    fun getDeviceByAddress(address: String): BluetoothDevice? {
        return try { bluetoothAdapter.getRemoteDevice(address) } catch (_: Exception) { null }
    }
}
