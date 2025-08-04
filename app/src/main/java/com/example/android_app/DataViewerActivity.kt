package com.example.android_app

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.example.android_app.R
import android.util.Log

class DataViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_viewer)

        val imageView: ImageView = findViewById(R.id.imageView)
        val depthText: TextView = findViewById(R.id.depthText)
        val backButton: Button = findViewById(R.id.backButton)

        // Extract image and depth data from the Intent
        val imageBytes = intent.getByteArrayExtra("imageBytes")
        val depthBytes = intent.getByteArrayExtra("depthBytes")

        // Debugging the size of received data
        Log.d("DataViewerActivity", "Received imageBytes size: ${imageBytes?.size ?: 0}")
        Log.d("DataViewerActivity", "Received depthBytes size: ${depthBytes?.size ?: 0}")

        // Handle depth data
        val depthSummary = if (depthBytes != null && depthBytes.isNotEmpty()) {
            depthBytes.joinToString(", ") { b -> b.toUByte().toString() }
        } else {
            "No depth data received"
        }
        depthText.text = "Depth Data (${depthBytes?.size ?: 0} bytes):\n$depthSummary"

        // Handle image data
        if (imageBytes != null) {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap) // Display the image
                Log.d("DataViewerActivity", "Image displayed successfully")
            } else {
                Log.e("DataViewerActivity", "Failed to decode image bytes")
            }
        } else {
            Log.e("DataViewerActivity", "No image data received")
        }

        // Handle back button
        backButton.setOnClickListener {
            finish()
        }
    }
}
