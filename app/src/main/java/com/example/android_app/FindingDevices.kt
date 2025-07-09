package com.example.android_app

import android.content.Intent
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

    private val transitionRunnable = Runnable {
        // 👇 Replace TargetActivity with the actual activity you want to go to
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish() // Optional: close this screen so user can't go back
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_finding_devices)

        val backButton = findViewById<ImageView>(R.id.backButton)
        textView = findViewById(R.id.textViewFinding)

        // Start animated dots
        handler.post(runnable)

        // Start transition after 10 seconds
        handler.postDelayed(transitionRunnable, 5_000)

        backButton.setOnClickListener {
            finish() // User pressed back, go back to previous screen
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(runnable) // prevent memory leak
    }
}
