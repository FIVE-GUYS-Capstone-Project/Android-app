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
        val imageView = findViewById<ImageView>(R.id.imageView)
        val depthText = findViewById<TextView>(R.id.depthText)
        val backButton = findViewById<Button>(R.id.backButton)
        backButton.setOnClickListener { finish() }
        val imageBytes = intent.getByteArrayExtra("image")
        val depthBytes = intent.getByteArrayExtra("depth")
        if (imageBytes != null) {
            Log.d("DataViewerActivity", "Received image via intent with size ${
                imageBytes.size}")
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0,
                imageBytes.size)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
                depthText.text = "Image loaded (${imageBytes.size} bytes)"
            } else {
                Log.e("DataViewerActivity", "Failed to decode image (size=${
                    imageBytes.size})")
                val hexDump = imageBytes.take(16).joinToString(" ") {
                    it.toUByte().toString(16).padStart(2, '0') }
                Log.e("DataViewerActivity", "Image head (first 16 bytes): $hexDump")
                val fallbackBitmap = BitmapFactory.decodeResource(resources,
                    R.drawable.test_image)
                imageView.setImageBitmap(fallbackBitmap)
                depthText.text = "Fallback image loaded"
            }
        } else {
            Log.e("DataViewerActivity", "imageBytes is null")
        }
        if (depthBytes != null) {
            Log.d("DataViewerActivity", "Depth received with size ${depthBytes.size}")
            depthText.append("\nDepth Data (${depthBytes.size} bytes)")
        } else {
            Log.w("DataViewerActivity", "No depth data passed in intent")
        }
    }
}
