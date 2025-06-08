package com.example.android_app

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), BluetoothLeManager.BleEventListener {
    private lateinit var bluetoothLeManager: BluetoothLeManager
    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var deviceList: LinearLayout
    private lateinit var dataText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        scanButton = findViewById(R.id.scanButton)
        deviceList = findViewById(R.id.deviceListLayout)
        dataText = findViewById(R.id.dataText)

        bluetoothLeManager = BluetoothLeManager(this)
        bluetoothLeManager.listener = this

        scanButton.setOnClickListener {
            statusText.text = "Scanning..."
            deviceList.removeAllViews()
            bluetoothLeManager.startScan()
        }
    }

    override fun onDeviceFound(deviceInfo: String) {
        runOnUiThread {
            val deviceName = deviceInfo.substringBefore(" - ")
            val deviceAddress = deviceInfo.substringAfter(" - ")
            val btn = Button(this).apply {
                text = deviceName
                setOnClickListener {
                    statusText.text = "Connecting to $deviceName..."
                    bluetoothLeManager.connect(deviceAddress)
                }
            }
            deviceList.addView(btn)
        }
    }

    override fun onScanStopped() {
        runOnUiThread { statusText.text = "Scan stopped." }
    }

    override fun onConnected(deviceName: String) {
        runOnUiThread { statusText.text = "Connected to $deviceName" }
    }

    override fun onDisconnected() {
        runOnUiThread { statusText.text = "Disconnected." }
    }

    override fun onDataReceived(data: ByteArray) {
        // Example: interpret as 3 floats (length, width, height)
        if (data.size >= 12) {
            val buffer = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val l = buffer.float
            val w = buffer.float
            val h = buffer.float
            val msg = "Length: %.2f\nWidth: %.2f\nHeight: %.2f".format(l, w, h)
            runOnUiThread { dataText.text = msg }
        } else {
            runOnUiThread { dataText.text = "Data: ${data.joinToString()}" }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothLeManager.disconnect()
    }
}
