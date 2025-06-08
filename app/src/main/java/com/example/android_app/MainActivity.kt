package com.example.android_app

import android.Manifest
import android.app.Activity
import android.content.IntentSender
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
import androidx.core.app.ActivityCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.example.android_app.BluetoothLeManager.BleEventListener

class MainActivity : AppCompatActivity(), BleEventListener {

    private lateinit var bluetoothLeManager: BluetoothLeManager
    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var deviceListLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        scanButton = findViewById(R.id.scanButton)
        deviceListLayout = findViewById(R.id.deviceListLayout)

        bluetoothLeManager = BluetoothLeManager(this)
        bluetoothLeManager.listener = this

        scanButton.setOnClickListener {
            bluetoothLeManager.stopScan()
            statusText.text = getString(R.string.scanning)
            deviceListLayout.removeAllViews()
            requestPermissionsAndStartScan()
        }
    }

    private fun requestPermissionsAndStartScan() {
        val permissions = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
            else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        permissionLauncher.launch(permissions)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            checkAndPromptEnableLocation()
        } else {
            Toast.makeText(this, "Permissions not granted", Toast.LENGTH_SHORT).show()
            statusText.text = getString(R.string.permission_denied)
        }
    }

    private fun checkAndPromptEnableLocation() {
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)

        val client = LocationServices.getSettingsClient(this)
        val task = client.checkLocationSettings(builder.build())

        task.addOnSuccessListener {
            bluetoothLeManager.startScan()
        }

        task.addOnFailureListener { e ->
            if (e is ResolvableApiException) {
                try {
                    e.startResolutionForResult(this, 1001)
                } catch (ex: IntentSender.SendIntentException) {
                    ex.printStackTrace()
                }
            } else {
                Toast.makeText(this, "Please enable location services", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDeviceFound(deviceInfo: String) {
        runOnUiThread {
            val deviceName = deviceInfo.substringBefore(" - ").ifEmpty { "Unknown" }
            val deviceAddress = deviceInfo.substringAfterLast(" - ")

            val deviceTextView = TextView(this).apply {
                text = deviceName
                textSize = 16f
                setPadding(12, 12, 12, 12)
                setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
                isClickable = true
                contentDescription = "Bluetooth device: $deviceName"
                tag = deviceAddress

                setOnClickListener {
                    val address = tag as String
                    val device = bluetoothLeManager.getDeviceByAddress(address)
                    if (device != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            ActivityCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) != PackageManager.PERMISSION_GRANTED) {
                            Toast.makeText(this@MainActivity, "Permission denied to connect", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }

                        bluetoothLeManager.connectToDevice(device)
                        statusText.text = getString(R.string.connecting_to, device.name ?: "Unknown")
                    }
                }
            }

            deviceListLayout.addView(deviceTextView)
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