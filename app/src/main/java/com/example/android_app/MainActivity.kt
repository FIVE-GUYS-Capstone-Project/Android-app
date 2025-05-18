package com.example.android_app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
//import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var statusText: TextView
    private lateinit var scanButton: Button

    // BLE scan callback
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device

            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true // pre-Android 12 doesn't require BLUETOOTH_CONNECT for device info
            }

            if (hasPermission) {
                val deviceName = device.name ?: "Unknown"
                val deviceAddress = device.address
                Log.d("BLE", "Found device: $deviceName - $deviceAddress")
                runOnUiThread {
                    statusText.text = getString(R.string.found_device, deviceName, deviceAddress)
                }
            } else {
                Log.d("BLE", "Found device: <no permission to access name/address>")
            }
        }
    }

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startBleScan()
        } else {
            Log.e("BLE", "Permissions not granted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        scanButton = findViewById(R.id.scanButton)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        scanButton.setOnClickListener {
            requestBlePermissions()
        }
    }

    // Request necessary permissions
    private fun requestBlePermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    // Start scanning for BLE devices
    private fun startBleScan() {
        // VERIFY IF BLUETOOTH IS ENABLE
        if (!bluetoothAdapter.isEnabled) {
            runOnUiThread {
                statusText.text = getString(R.string.please_enable_bluetooth)
            }
            Log.e("BLE", "Bluetooth is disabled")
            return
        }

        try {
            val permissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            }

            if (!permissionGranted) {
                Log.e("BLE", "Missing BLE scan permission")
                return
            }

            bluetoothAdapter.bluetoothLeScanner.startScan(scanCallback)
            Log.d("BLE", "Started scanning...")

            if (Build.FINGERPRINT.contains("generic")) {
                runOnUiThread {
                    val deviceName = "Demo Device"
                    val deviceAddress = "00:11:22:33:44:55"
                    statusText.text = getString(R.string.found_device, deviceName, deviceAddress)
                }
                Log.d("BLE", "Simulated device found on emulator")
            }

            Handler(mainLooper).postDelayed({
                bluetoothAdapter.bluetoothLeScanner.stopScan(scanCallback)
                Log.d("BLE", "Stopped scanning.")
                runOnUiThread {
                    // Only show "Scan stopped" if not emulator, or you can comment this out
                    if (!Build.FINGERPRINT.contains("generic")) {
                        statusText.text = getString(R.string.scan_stopped)
                    }
                }
            }, 10_000)

        } catch (e: SecurityException) {
            Log.e("BLE", "SecurityException while scanning: ${e.message}")
        }
    }
}