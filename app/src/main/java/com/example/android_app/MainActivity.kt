package com.example.android_app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.android_app.BluetoothLeManager.BleEventListener

class MainActivity : AppCompatActivity(), BleEventListener {

    // Custom manager for BLE operations
    private lateinit var bluetoothLeManager: BluetoothLeManager

    // TextView to show current status
    private lateinit var statusText: TextView

    // Button to start scanning
    private lateinit var scanButton: Button

    // Layout to display found devices as clickable items
    private lateinit var deviceListLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI elements by finding them in the layout
        statusText = findViewById(R.id.statusText)
        scanButton = findViewById(R.id.scanButton)
        deviceListLayout = findViewById(R.id.deviceListLayout)

        // Initialize Bluetooth LE manager and set this activity as listener for BLE events
        bluetoothLeManager = BluetoothLeManager(this)
        bluetoothLeManager.listener = this

        // Set click listener on scan button
        scanButton.setOnClickListener {
            // Stop any ongoing scan before starting a new one
            bluetoothLeManager.stopScan()

            // Update status text to "Scanning..."
            statusText.text = getString(R.string.scanning)

            // Clear previous scan results from the device list
            deviceListLayout.removeAllViews()

            // Request necessary permissions before scanning
            requestBlePermissions()
        }
    }

    // Request required permissions based on Android version
    private fun requestBlePermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // For Android 12 and above, Bluetooth permissions are split into BLUETOOTH_SCAN and BLUETOOTH_CONNECT
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)

        } else {
            // For Android versions below 12, location permissions are needed to scan for BLE devices
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // Launch the permission request dialog
        permissionLauncher.launch(permissions.toTypedArray())
    }

    // Handle permission request results
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->

        // Check if all requested permissions were granted
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            // If granted, start scanning for BLE devices
            bluetoothLeManager.startScan()
            runOnUiThread {
                statusText.text = getString(R.string.scanning)
            }

        } else {
            // If permissions denied, log and update status
            Log.e("BLE", "Permissions not granted")
            runOnUiThread {
                statusText.text = getString(R.string.permission_denied)
            }
        }
    }

    // Callback from BluetoothLeManager when a new device is found during scanning
    override fun onDeviceFound(deviceInfo: String) {
        runOnUiThread {
            // deviceInfo format expected: "DeviceName - MAC_Address"
            val deviceName = deviceInfo.substringBefore(" - ").ifEmpty { "Unknown" }
            val deviceAddress = deviceInfo.substringAfterLast(" - ")

            // Create a clickable TextView for each device to show its name
            val deviceTextView = TextView(this).apply {
                // Display only the device name
                text = deviceName
                textSize = 16f
                setPadding(12, 12, 12, 12)
                setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
                isClickable = true
                // Accessibility description
                contentDescription = "Bluetooth device: $deviceName"

                // Store the device MAC address in the tag for reference on click
                tag = deviceAddress

                // Set what happens when the user clicks on a device name
                setOnClickListener {
                    val address = tag as String
                    val device = bluetoothLeManager.getDeviceByAddress(address)

                    if (device != null) {
                        // On Android 12+, check for BLUETOOTH_CONNECT permission before connecting
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val hasPermission = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                                    PackageManager.PERMISSION_GRANTED
                            if (!hasPermission) {
                                Log.e("BLE", "Missing BLUETOOTH_CONNECT permission")
                                Toast.makeText(
                                    this@MainActivity,
                                    "Permission denied to connect to device",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@setOnClickListener
                            }
                        }

                        // Initiate connection to the selected device
                        bluetoothLeManager.connectToDevice(device)
                        statusText.text = getString(R.string.connecting_to, device.name ?: "Unknown")

                    } else {
                        // Device could not be found by address
                        Log.e("BLE", "Device not found by address: $address")
                        Toast.makeText(this@MainActivity, "Failed to connect", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // Add the TextView representing the device to the list layout
            deviceListLayout.addView(deviceTextView)
        }
    }

    // Callback when scanning is stopped
    override fun onScanStopped() {
        runOnUiThread {
            statusText.text = getString(R.string.scan_stopped)
        }
    }

    // Callback when connected to a device successfully
    override fun onConnected(deviceName: String) {
        runOnUiThread {
            statusText.text = getString(R.string.connected_to, deviceName)
        }
    }

    // Callback when disconnected from the device
    override fun onDisconnected() {
        runOnUiThread {
            statusText.append("\n${getString(R.string.disconnected)}")
        }
    }

    // Clean up when activity is destroyed
    override fun onDestroy() {
        super.onDestroy()
        bluetoothLeManager.stopScan()
        bluetoothLeManager.disconnect()
    }
}