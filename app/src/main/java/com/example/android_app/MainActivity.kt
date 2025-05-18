package com.example.android_app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.android_app.BluetoothLeManager.BleEventListener

class MainActivity : AppCompatActivity(), BleEventListener {

    private lateinit var bluetoothLeManager: BluetoothLeManager
    private lateinit var statusText: TextView
    private lateinit var devicesText: TextView
    private lateinit var scanButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        devicesText = findViewById(R.id.devicesText)
        scanButton = findViewById(R.id.scanButton)

        bluetoothLeManager = BluetoothLeManager(this)
        bluetoothLeManager.listener = this

        scanButton.setOnClickListener {
            bluetoothLeManager.stopScan()  // Stop any ongoing scan
            statusText.text = getString(R.string.scanning)
            devicesText.text = ""           // Clear previous devices list
            requestBlePermissions()
        }
    }

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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            bluetoothLeManager.startScan()
            runOnUiThread {
                statusText.text = getString(R.string.scanning)
            }
        } else {
            Log.e("BLE", "Permissions not granted")
            runOnUiThread {
                statusText.text = getString(R.string.permission_denied)
            }
        }
    }

    // BLE callback implementations

    override fun onDeviceFound(deviceInfo: String) {
        runOnUiThread {
            devicesText.append("$deviceInfo\n")
        }
    }

    override fun onScanStopped() {
        runOnUiThread {
            statusText.text = getString(R.string.scan_stopped)
        }
    }

    override fun onConnected(deviceName: String) {
        runOnUiThread {
            statusText.text = getString(R.string.connected_to, deviceName)
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            statusText.append("\n${getString(R.string.disconnected)}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothLeManager.stopScan()
        bluetoothLeManager.disconnect()
    }
}
