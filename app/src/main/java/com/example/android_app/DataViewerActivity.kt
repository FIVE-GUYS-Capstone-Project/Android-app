package com.example.android_app

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class DataViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_viewer)

        val imageView: ImageView = findViewById(R.id.imageView)
        val depthText: TextView = findViewById(R.id.depthText)
        val backButton: Button = findViewById(R.id.backButton)

        // Retrieve data from static holder
        DataHolder.imageBytes?.let {
            val bitmap = BitmapFactory.decodeByteArray(it, 0, it.size)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            } else {
                Log.e("DataViewer", "Failed to decode image")
                imageView.setImageResource(R.drawable.error_placeholder) // Use placeholder
            }
        }

        val depthSummary = DataHolder.depthBytes?.joinToString(", ") { b -> b.toUByte().toString() } ?: ""
        depthText.text = "Depth Data (${DataHolder.depthBytes?.size ?: 0} bytes):\n$depthSummary"

        // Send ACK to ESP32-S3 using MyApplication
        val bluetoothLeManager = MyApplication.getInstance(this).bluetoothLeManager
        bluetoothLeManager.sendAck()

        // Handle back button
        backButton.setOnClickListener {
            finish()
        }
    }
}