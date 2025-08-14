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
        val imgBytes   = intent.getByteArrayExtra("imageBytes")
        val depthBytes = intent.getByteArrayExtra("depthBytes")
        if (imgBytes == null) {
            Log.e("DataViewerActivity", "imageBytes is null")
        } else {
            // quick sanity: JPEG should start with FF D8 and end with FF D9
            val okHeader = imgBytes.size >= 4 &&
                    (imgBytes[0].toInt() and 0xFF) == 0xFF &&
                    (imgBytes[1].toInt() and 0xFF) == 0xD8
            Log.d("DataViewerActivity", "Image bytes: " +
                    "${imgBytes.size}, JPEG_SOI=$okHeader")

            val bmp = BitmapFactory.decodeByteArray(imgBytes,
                0, imgBytes.size)
            if (bmp == null) {
                Log.e("DataViewerActivity", "BitmapFactory returned null")
            } else {
                findViewById<ImageView>(R.id.imageView).setImageBitmap(bmp)
            }
        }
        if (depthBytes == null) {
            Log.w("DataViewerActivity", "No depth data passed in intent")
        } else {
            // show depth length or parse it as you like
            findViewById<TextView>(R.id.depthText).text = "Depth bytes: ${depthBytes.size}"
        }
    }
}