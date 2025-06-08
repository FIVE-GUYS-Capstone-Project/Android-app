package com.example.android_app

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import android.widget.Toast


// Manager class to handle Bluetooth Low Energy scanning, connection, and callbacks
class BluetoothLeManager(private val context: Context) {

    // Listener interface to communicate BLE events back to the UI or other components
    var listener: BleEventListener? = null

    // Flag indicating if scanning is active
    var isScanning = false

    // Flag to avoid stopping scan multiple times
    private var hasStoppedScan = false

    // Lazy initialization of BluetoothAdapter via BluetoothManager system service
    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    // Holds the active GATT connection
    private var bluetoothGatt: BluetoothGatt? = null

    // Keeps track of discovered devices to avoid duplicates
    private val foundDevices = mutableSetOf<String>()

    // Callback invoked on BLE scan results
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device

            // Check if BLUETOOTH_CONNECT permission is granted on Android 12+ before accessing device info
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                // Permission not required on older versions
                true
            }

            if (hasPermission) {
                val deviceName = device.name ?: "Unknown"
                val deviceAddress = device.address
                val deviceInfo = "$deviceName - $deviceAddress"

                // Only notify listener if device is new (not already found)
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

    // Start scanning for BLE devices
    fun startScan() {
        if (isScanning) return

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

        if (!hasScanPermission) {
            Log.e("BLE", "Missing BLUETOOTH_SCAN permission")
            return
        }

        try {
            scanner.startScan(scanCallback)
            Log.d("BLE", "Started scanning...")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                stopScan()
            }, 10_000)
        } catch (e: SecurityException) {
            Log.e("BLE", "SecurityException while starting scan: ${e.message}")
        }
    }


    // Stop the ongoing BLE scan
    fun stopScan() {
        // Avoid stopping scan if it already stopped or not running
        if (!isScanning || hasStoppedScan) return
        hasStoppedScan = true
        isScanning = false

        // Check scan permission again before stopping
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

        // Notify listener scan is stopped
        listener?.onScanStopped()
    }

    // Connect to a BLE device given a BluetoothDevice object
    fun connectToDevice(device: BluetoothDevice) {

        // Check BLUETOOTH_CONNECT permission on Android 12+ before connecting
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
            // Initiate GATT connection to the device with autoConnect=false
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        } catch (e: SecurityException) {
            Log.e("BLE", "SecurityException in connectGatt: ${e.message}")
        }
    }

    // GATT callback to handle connection state and service discovery events
    private val gattCallback = object : BluetoothGattCallback() {

        // Called when connection state changes (connected/disconnected)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d("BLE", "Connected to GATT server.")
                    listener?.onConnected(gatt.device.name ?: "Unknown")

                    // Check permission before discovering services on Android 12+
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

        // Called when services have been discovered on the connected device
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE", "Services discovered.")
            } else {
                Log.w("BLE", "onServicesDiscovered received: $status")
            }
        }
    }

    // Disconnect and clean up the current GATT connection
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

    // Retrieve a BluetoothDevice object by its MAC address
    fun getDeviceByAddress(address: String): BluetoothDevice? {
        return bluetoothAdapter.getRemoteDevice(address)
    }

    // Interface to deliver BLE events to listeners
    interface BleEventListener {
        // Called when a BLE device is discovered
        fun onDeviceFound(deviceInfo: String)

        // Called when scanning stops
        fun onScanStopped()

        // Called on successful connection
        fun onConnected(deviceName: String)

        // Called when disconnected
        fun onDisconnected()
    }

}