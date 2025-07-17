package com.example.android_app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class DataViewerActivity : AppCompatActivity(), BluetoothLeManager.BleEventListener {

    private lateinit var bluetoothLeManager: BluetoothLeManager
    private lateinit var imageView: ImageView
    private lateinit var depthText: TextView
    private lateinit var statusText: TextView
    private lateinit var frameInfo: TextView
    private var lastImageTimestamp: Long = 0
    private var frameCount: Int = 0
    private var isStreaming: Boolean = false
    private var lastCapturedBitmap: Bitmap? = null // For the "Capture" action

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_viewer)

        imageView = findViewById(R.id.imageView)
        depthText = findViewById(R.id.depthText)
        statusText = findViewById(R.id.statusText)
        frameInfo = findViewById(R.id.frameInfo)

        bluetoothLeManager = MyApplication.getInstance(this).bluetoothLeManager
        bluetoothLeManager.listener = this

        findViewById<Button>(R.id.startButton).setOnClickListener {
            bluetoothLeManager.sendCommand("START")
            statusText.text = "Preview: Streaming..."
            isStreaming = true
            frameCount = 0
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            bluetoothLeManager.sendCommand("STOP")
            statusText.text = "Preview stopped"
            isStreaming = false
        }
        findViewById<Button>(R.id.captureButton).setOnClickListener {
            bluetoothLeManager.sendCommand("CAPTURE")
            statusText.text = "Requested capture"
            // For single capture, you might want to stop streaming after
            // one image received, up to you!
        }
        findViewById<Button>(R.id.backButton).setOnClickListener {
            bluetoothLeManager.disableNotifications()
            bluetoothLeManager.listener = null
            finish()
        }
    }

    override fun onImageReceived(imageBytes: ByteArray) {
        runOnUiThread {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            imageView.setImageBitmap(bitmap)
            frameCount++
            lastImageTimestamp = System.currentTimeMillis()
            frameInfo.text = "Frame: $frameCount | Last: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastImageTimestamp))}"
            bluetoothLeManager.sendAck()
            lastCapturedBitmap = bitmap // Save for potential "save" feature
            if (!isStreaming) {
                statusText.text = "Capture complete"
            }
        }
    }

    override fun onDepthReceived(depthBytes: ByteArray) {
        runOnUiThread {
            val summary = depthBytes.take(24).joinToString(", ") { b -> b.toUByte().toString() }
            depthText.text = "Depth Data (${depthBytes.size} bytes): $summary"
            bluetoothLeManager.sendAck()
        }
    }

    override fun onConnected(deviceName: String) {
        runOnUiThread { statusText.text = "Connected to $deviceName" }
    }

    override fun onDisconnected() {
        runOnUiThread { statusText.text = "Disconnected"; finish() }
    }
}
