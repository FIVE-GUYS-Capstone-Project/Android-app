package com.example.android_app

import android.Manifest
import android.R.attr.button
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class DevicesFound : AppCompatActivity(), BluetoothLeManager.BleEventListener {
    private lateinit var deviceContainer: LinearLayout
    private lateinit var bluetoothLeManager: BluetoothLeManager
    private val foundAddresses = mutableSetOf<String>()
    private val deviceButtons = mutableMapOf<String, Button>()
    private var connectedAddress: String? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_devices_found)

        deviceContainer = findViewById(R.id.deviceContainer)
        bluetoothLeManager = BluetoothLeManager(this)
        bluetoothLeManager.listener = this


        requestPermissionsAndStartScan()

        val backButton = findViewById<ImageView>(R.id.backButton)
        backButton.setOnClickListener { finish() }
    }

    private fun requestPermissionsAndStartScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(permissions)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            bluetoothLeManager.startScan()
        } else {
            Toast.makeText(this, "Bluetooth permissions denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDeviceFound(deviceInfo: String) {
        val name = deviceInfo.substringBefore(" - ").ifEmpty { "Unnamed" }
        val address = deviceInfo.substringAfterLast(" - ")

        runOnUiThread {
            if (foundAddresses.contains(address)) return@runOnUiThread
            foundAddresses.add(address)
            addDeviceRow(name, address)
        }
    }

    private fun addDeviceRow(name: String, address: String) {
        val cardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 16, 0, 16) }
            setPadding(24, 24, 24, 24)
            background = ContextCompat.getDrawable(this@DevicesFound, R.drawable.device_card_background)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val label = TextView(this).apply {
            text = name
            textSize = 16f
            setTextColor(Color.parseColor("#0E4174"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val button = Button(this).apply {
            text = "Connect"
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@DevicesFound, R.drawable.button_connect_background)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            setOnClickListener {
                val device = bluetoothLeManager.getDeviceByAddress(address)
                if (device != null) {
                    bluetoothLeManager.stopScan() // <-- IMPORTANT
                    bluetoothLeManager.connectToDevice(device)
                    isEnabled = false
                    text = "Connecting"
                    background = ContextCompat.getDrawable(this@DevicesFound, R.drawable.button_connecting_background)
                    Toast.makeText(this@DevicesFound, "Connecting to $name", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@DevicesFound, "Device not found", Toast.LENGTH_SHORT).show()
                }
            }

        }

        row.addView(label)
        row.addView(button)
        cardContainer.addView(row)
        deviceContainer.addView(cardContainer)
        deviceButtons[address] = button

    }

    override fun onScanStopped() {
        runOnUiThread {
            Toast.makeText(this, "Scan completed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConnected(deviceName: String) {
        runOnUiThread {
            for ((address, button) in deviceButtons) {
                if (button.text == "Connecting") {
                    connectedAddress = address
                    button.text = "Connected"
                    button.isEnabled = false
                    button.background = ContextCompat.getDrawable(this, R.drawable.button_connecting_background)
                    break
                }
            }

            Toast.makeText(this, "Connected to $deviceName", Toast.LENGTH_SHORT).show()

            // Start the new activity after successful connection
            val intent = Intent(this, DataViewerActivity::class.java)
            intent.putExtra("DEVICE_NAME", deviceName)
            connectedAddress?.let { intent.putExtra("DEVICE_ADDRESS", it) }
            startActivity(intent)

            // Optionally finish current activity if you don’t want user to return
            // finish()
        }
    }



    override fun onDisconnected() {
        runOnUiThread {
            Toast.makeText(this, "Disconnected from device", Toast.LENGTH_SHORT).show()

            connectedAddress?.let { address ->
                deviceButtons[address]?.let { button ->
                    button.text = "Connect"
                    button.isEnabled = true
                    button.background = ContextCompat.getDrawable(this, R.drawable.button_connect_background)
                }
            }

            connectedAddress = null
        }
    }



    override fun onImageReceived(imageBytes: ByteArray) {}
    override fun onDepthReceived(depthBytes: ByteArray) {}
}
