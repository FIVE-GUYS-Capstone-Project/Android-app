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
import androidx.core.app.ActivityCompat
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

    // TODO: Replace with your real UUIDs!
    private val DIMENSION_SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val DIMENSION_CHAR_UUID    = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else true

            val deviceName = device.name ?: "Unknown"
            val deviceAddress = device.address
            val deviceInfo = "$deviceName - $deviceAddress"

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

    // Start scan, with permission check!
    fun startScan() {
        if (isScanning) return
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(context, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            listener?.onScanStopped()
            return
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner
        val hasScanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else true

        if (scanner == null || !hasScanPermission) {
            listener?.onScanStopped()
            Log.e("BLE", "No scanner or scan permission denied!")
            return
        }
        isScanning = true
        hasStoppedScan = false
        foundDevices.clear()
        scanner.startScan(scanCallback)
        Handler(Looper.getMainLooper()).postDelayed({ stopScan() }, 10_000)
    }

    // Stop scan, with permission check!
    fun stopScan() {
        if (!isScanning || hasStoppedScan) return
        hasStoppedScan = true
        isScanning = false
        val hasScanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else true

        if (!hasScanPermission) {
            Log.e("BLE", "BLUETOOTH_SCAN permission not granted for stopScan!")
            return
        }
        bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        listener?.onScanStopped()
    }

    // Connect to device, with permission check!
    fun connectToDevice(device: BluetoothDevice) {
        val hasConnectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true

        if (!hasConnectPermission) {
            Toast.makeText(context, "Bluetooth connect permission denied", Toast.LENGTH_SHORT).show()
            Log.e("BLE", "BLUETOOTH_CONNECT permission denied for connectToDevice!")
            return
        }
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null

        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("BLE", "Connected to GATT server.")
                listener?.onConnected(gatt.device.name ?: "Unknown")
                // Discover services only if connect permission
                val hasConnectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                } else true

                if (hasConnectPermission) {
                    gatt.discoverServices()
                } else {
                    Log.e("BLE", "BLUETOOTH_CONNECT permission denied for discoverServices!")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("BLE", "Disconnected from GATT server.")
                listener?.onDisconnected()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE", "Services discovered.")
                val service = gatt.getService(DIMENSION_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(DIMENSION_CHAR_UUID)
                if (characteristic != null) {
                    // Check permission before setting notification!
                    val hasConnectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    } else true

                    if (hasConnectPermission) {
                        gatt.setCharacteristicNotification(characteristic, true)
                        // Some BLE devices need this descriptor for notification
                        val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        descriptor?.let {
                            @Suppress("DEPRECATION") // This is still safe!
                            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(it)
                        }
                    } else {
                        Log.e("BLE", "BLUETOOTH_CONNECT permission denied for notifications!")
                    }
                }
            }
        }

        // Handle notification, no permission needed here (already checked before subscribe)
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == DIMENSION_CHAR_UUID) {
                val data = characteristic.value
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
        val hasConnectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true

        if (!hasConnectPermission) {
            Log.e("BLE", "BLUETOOTH_CONNECT permission not granted for disconnect!")
            return
        }
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
