package com.example.android_app

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.example.android_app.R

class DataViewerActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var depthText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_viewer)

        imageView = findViewById(R.id.imageView)
        depthText = findViewById(R.id.depthText)
        val backButton: Button = findViewById(R.id.backButton)

        val imageBytes = intent.getByteArrayExtra("imageBytes")
        val depthBytes = intent.getByteArrayExtra("depthBytes")

        // Display initial data (if already complete)
        imageBytes?.let {
            val bitmap = BitmapFactory.decodeByteArray(it, 0, it.size)
            imageView.setImageBitmap(bitmap)
        }

        val depthSummary = depthBytes?.joinToString(", ") { b -> b.toUByte().toString() }
        depthText.text = "Depth Data (${depthBytes?.size ?: 0} bytes):\n$depthSummary"

        backButton.setOnClickListener {
            finish()
        }
    }
}
