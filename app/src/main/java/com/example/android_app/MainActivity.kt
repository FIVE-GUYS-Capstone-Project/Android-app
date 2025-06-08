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
    private lateinit var socketButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        scanButton = findViewById(R.id.scanButton)
        deviceList = findViewById(R.id.deviceListLayout)
        dataText = findViewById(R.id.dataText)
        socketButton = findViewById(R.id.socketButton)

        bluetoothLeManager = BluetoothLeManager(this)
        bluetoothLeManager.listener = this

        scanButton.setOnClickListener {
            statusText.text = "Scanning BLE..."
            deviceList.removeAllViews()
            bluetoothLeManager.startScan()
        }

        socketButton.setOnClickListener {
            statusText.text = "Connecting by Wi-Fi..."
            // Replace with your ESP32 IP and port
            val client = SocketClientManager(
                "192.168.4.1", 1234,
                onResult = { l, w, h ->
                    statusText.text = "Wi-Fi data received!"
                    dataText.text = "L: %.2f\nW: %.2f\nH: %.2f".format(l, w, h)
                },
                onError = { msg ->
                    statusText.text = "Wi-Fi error: $msg"
                }
            )
            client.connectAndReceive()
        }
    }

    override fun onDeviceFound(deviceInfo: String) {
        runOnUiThread {
            val deviceName = deviceInfo.substringBefore(" - ")
            val deviceAddress = deviceInfo.substringAfter(" - ")
            val btn = Button(this).apply {
                text = deviceName
                setOnClickListener {
                    statusText.text = "Connecting to $deviceName (BLE)..."
                    bluetoothLeManager.connect(deviceAddress)
                }
            }
            deviceList.addView(btn)
        }
    }

    override fun onScanStopped() {
        runOnUiThread { statusText.text = "BLE scan stopped." }
    }

    override fun onConnected(deviceName: String) {
        runOnUiThread { statusText.text = "Connected to $deviceName (BLE)" }
    }

    override fun onDisconnected() {
        runOnUiThread { statusText.text = "Disconnected." }
    }

    override fun onDataReceived(length: Float, width: Float, height: Float) {
        runOnUiThread {
            dataText.text = "L: %.2f\nW: %.2f\nH: %.2f".format(length, width, height)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothLeManager.disconnect()
    }
}
