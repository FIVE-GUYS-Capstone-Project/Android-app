package com.example.android_app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.UUID

class BluetoothLeManager(private val context: Context) {

    interface BleEventListener {
        fun onDeviceFound(deviceInfo: String)
        fun onScanStopped()
        fun onConnected(deviceName: String)
        fun onDisconnected()
        fun onImageReceived(imageBytes: ByteArray)
        fun onDepthReceived(depthBytes: ByteArray)
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

    // --- BLE UUIDs for Hardware (camelCase, rxUuid removed as it's unused) ---
    private val serviceUuid = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val txUuid = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb") // Image/Depth notifications

    // === For chunked data handling ===
    private var isReceivingPayload = false
    private var currentPayloadType: Byte = 0
    private var expectedPayloadLength: Int = 0
    private var receivedPayloadBytes = 0
    private var payloadBuffer = ByteArrayOutputStream()

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
                val name = device.name
                if (name.isNullOrBlank()) return  // Skip unnamed devices

                val address = device.address
                val deviceInfo = "$name - $address"

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
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d("BLE", "Disconnected from GATT server.")
                    listener?.onDisconnected()
                    resetPayloadBuffer()
                }
            }
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val txChar = gatt.getService(serviceUuid)?.getCharacteristic(txUuid)
                txChar?.let {
                    val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    } else true
                    if (hasPermission) {
                        try {
                            gatt.setCharacteristicNotification(it, true)
                            val descriptor = it.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                            descriptor?.let { d ->
                                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                gatt.writeDescriptor(d)
                            }
                        } catch (e: SecurityException) {
                            Log.e("BLE", "No Bluetooth Connect permission: ${e.message}")
                        }
                    } else {
                        Log.e("BLE", "BLUETOOTH_CONNECT permission not granted")
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == txUuid) {
                val data = characteristic.value
                handleIncomingData(data)
            }
        }
    }

    // === Chunked Data Handler ===
    private fun handleIncomingData(data: ByteArray) {
        if (data.size == 6 && data[0] == 0xAA.toByte()) {
            // --- HEADER RECEIVED ---
            currentPayloadType = data[1]
            expectedPayloadLength = (data[2].toInt() and 0xFF) or
                    ((data[3].toInt() and 0xFF) shl 8) or
                    ((data[4].toInt() and 0xFF) shl 16) or
                    ((data[5].toInt() and 0xFF) shl 24)
            payloadBuffer.reset()
            receivedPayloadBytes = 0
            isReceivingPayload = true
            Log.d("BLE", "Header: type=${currentPayloadType.toUByte().toString(16)}, len=$expectedPayloadLength")
        } else if (isReceivingPayload) {
            // --- PAYLOAD CHUNK ---
            payloadBuffer.write(data)
            receivedPayloadBytes += data.size
            Log.d("BLE", "Received $receivedPayloadBytes/$expectedPayloadLength bytes for type $currentPayloadType")
            if (receivedPayloadBytes >= expectedPayloadLength) {
                val fullPayload = payloadBuffer.toByteArray()
                when (currentPayloadType) {
                    0x01.toByte() -> {
                        Log.d("BLE_Debug", "Image payload complete (${fullPayload.size} bytes)")
                        listener?.onImageReceived(fullPayload)
                    }
                    0x02.toByte() -> {
                        Log.d("BLE_Debug", "Depth payload complete (${fullPayload.size} bytes)")
                        listener?.onDepthReceived(fullPayload)
                    }
                    else -> Log.w("BLE_Debug", "Unknown payload type: $currentPayloadType")
                }
                resetPayloadBuffer()
            }
        }
    }

    private fun resetPayloadBuffer() {
        isReceivingPayload = false
        receivedPayloadBytes = 0
        expectedPayloadLength = 0
        payloadBuffer.reset()
    }

    fun disableNotifications() {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true

        if (!hasPermission) {
            Log.e("BLE", "Missing BLUETOOTH_CONNECT permission — cannot disable notifications.")
            return
        }

        val characteristic = bluetoothGatt?.getService(serviceUuid)?.getCharacteristic(txUuid)
        val descriptor = characteristic?.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))

        try {
            characteristic?.let {
                bluetoothGatt?.setCharacteristicNotification(it, false)
                descriptor?.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                bluetoothGatt?.writeDescriptor(descriptor)
                Log.d("BLE", "Notifications disabled")
            }
        } catch (e: SecurityException) {
            Log.e("BLE", "SecurityException: ${e.message}")
        } catch (e: Exception) {
            Log.e("BLE", "Failed to disable notifications: ${e.message}")
        }
    }

    // Utility: Get a BluetoothDevice object by MAC
    fun getDeviceByAddress(address: String): BluetoothDevice? {
        return try { bluetoothAdapter.getRemoteDevice(address) } catch (_: Exception) { null }
    }
}
