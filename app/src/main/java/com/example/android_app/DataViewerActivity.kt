package com.example.android_app

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.example.android_app.R
import android.util.Log

class DataViewerActivity : AppCompatActivity(), BluetoothLeManager.BleEventListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_viewer)

        val bleManager = (application as MyApp).bluetoothLeManager
        bleManager.listener = this

        findViewById<TextView>(R.id.depthText).text = "Loading image..."
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
    }

    // === Implement all interface methods ===
    override fun onDeviceFound(deviceInfo: String) {}
    override fun onScanStopped() {}
    override fun onConnected(deviceName: String) {}
    override fun onDisconnected() {}

    override fun onImageReceived(imageBytes: ByteArray) {
        runOnUiThread {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (bitmap != null) {
                findViewById<ImageView>(R.id.imageView).setImageBitmap(bitmap)
                findViewById<TextView>(R.id.depthText).text = "Image loaded (${imageBytes.size} bytes)"
            } else {
                Log.e("DataViewerActivity", "Failed to decode image")
            }
        }
    }


    override fun onDepthReceived(depthBytes: ByteArray) {
        runOnUiThread {
            findViewById<TextView>(R.id.depthText).text = "Depth Data (${depthBytes.size} bytes)"
        }
    }
}
