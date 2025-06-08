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

    var listener: BleEventListener? = null
    var isScanning = false
    private var hasStoppedScan = false

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private val foundDevices = mutableSetOf<String>()

    // TODO: Replace with your ESP32's Service & Characteristic UUIDs
    private val DIMENSION_SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val DIMENSION_CHAR_UUID    = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else true

            val deviceName = device.name ?: "Unknown"
            val deviceAddress = device.address
            val deviceInfo = "$deviceName - $deviceAddress"

            // Optionally skip unknown devices
            if (deviceName == "Unknown") return

            if (hasPermission && foundDevices.add(deviceInfo)) {
                Log.d("BLE", "Found device: $deviceInfo")
                listener?.onDeviceFound(deviceInfo)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BLE", "Scan failed with error code: $errorCode")
            listener?.onScanStopped()
        }
    }

    fun startScan() {
        if (isScanning) return
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(context, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            listener?.onScanStopped()
            return
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            listener?.onScanStopped()
            return
        }
        isScanning = true
        hasStoppedScan = false
        foundDevices.clear()
        scanner.startScan(scanCallback)
        Handler(Looper.getMainLooper()).postDelayed({ stopScan() }, 10_000)
    }

    fun stopScan() {
        if (!isScanning || hasStoppedScan) return
        hasStoppedScan = true
        isScanning = false
        bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        listener?.onScanStopped()
    }

    fun connectToDevice(device: BluetoothDevice) {
        val hasConnectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true

        if (!hasConnectPermission) {
            Toast.makeText(context, "Bluetooth connect permission denied", Toast.LENGTH_SHORT).show()
            return
        }
        // If already connected, disconnect first
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null

        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    // -- The BLE GATT callback for connection and data events --
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("BLE", "Connected to GATT server.")
                listener?.onConnected(gatt.device.name ?: "Unknown")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("BLE", "Disconnected from GATT server.")
                listener?.onDisconnected()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE", "Services discovered.")
                // Enable notifications on the dimension characteristic
                val service = gatt.getService(DIMENSION_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(DIMENSION_CHAR_UUID)
                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, true)
                    // Some devices require a descriptor to enable notifications:
                    val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    descriptor?.let {
                        it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(it)
                    }
                }
            }
        }

        // --- Receive data via BLE notifications ---
        // Handle incoming binary data from the ESP32 hardware via BLE notifications.
        // The characteristic value contains 12 bytes representing three 4-byte floats:
        // [0..3]: Length (float, little endian)
        // [4..7]: Width  (float, little endian)
        // [8..11]: Height (float, little endian)
        // This block parses the incoming bytes, extracts the package dimensions,
        // and passes them to the UI via the BleEventListener for display.
        // NOTE: Both Android and ESP32 must agree on the byte order (endianness)
        // and structure to ensure correct parsing.
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == DIMENSION_CHAR_UUID) {
                val data = characteristic.value // 12 bytes expected
                if (data.size >= 12) {
                    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    val length = buffer.float
                    val width = buffer.float
                    val height = buffer.float
                    Log.d("BLE", "Received dims: L=$length, W=$width, H=$height")
                    listener?.onDimensionReceived(length, width, height)
                } else {
                    Log.w("BLE", "Received data of unexpected length: ${data.size}")
                }
            }
        }
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    fun getDeviceByAddress(address: String): BluetoothDevice? {
        return try { bluetoothAdapter.getRemoteDevice(address) } catch (e: IllegalArgumentException) { null }
    }

    interface BleEventListener {
        fun onDeviceFound(deviceInfo: String)
        fun onScanStopped()
        fun onConnected(deviceName: String)
        fun onDisconnected()
        fun onDimensionReceived(length: Float, width: Float, height: Float)
    }
}
