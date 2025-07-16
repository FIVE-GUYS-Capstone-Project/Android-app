package com.example.android_app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class FindingDevices : AppCompatActivity() {
    private lateinit var textView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val baseText = "Finding devices"
    private var dotCount = 0

    private val dotsRunnable = object : Runnable {
        override fun run() {
            dotCount = (dotCount + 1) % 4
            textView.text = baseText + ".".repeat(dotCount)
            handler.postDelayed(this, 500)
        }
    }

    private val transitionRunnable = Runnable {
        val intent = Intent(this, DevicesFound::class.java)
        startActivity(intent)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_finding_devices)

        textView = findViewById(R.id.textViewFinding)
        val backButton = findViewById<ImageView>(R.id.backButton)

        handler.post(dotsRunnable)
        handler.postDelayed(transitionRunnable, 3000)

        backButton.setOnClickListener {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(dotsRunnable)
        handler.removeCallbacks(transitionRunnable)
    }
}
