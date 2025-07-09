package com.example.android_app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.widget.ImageView
import com.example.android_app.R // optional if needed

class FindingDevices : AppCompatActivity() {
    private lateinit var textView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var dotCount = 0
    private val baseText = "Finding devices"

    private val runnable = object : Runnable {
        override fun run() {
            // Cycle through 0 to 3 dots
            dotCount = (dotCount + 1) % 4
            val dots = ".".repeat(dotCount)
            textView.text = "$baseText$dots"
            handler.postDelayed(this, 500) // update every 500ms
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_finding_devices)

        val backButton = findViewById<ImageView>(R.id.backButton)
        textView = findViewById(R.id.textViewFinding) // Make sure this matches your XML ID
        handler.post(runnable)

        backButton.setOnClickListener {
            // 👈 This returns to the previous screen
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(runnable) // prevent memory leak
    }
}
