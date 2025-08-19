package com.example.android_app

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class FrameWaitingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Reuse your existing loading layout
        setContentView(R.layout.activity_finding_devices)

        // Change label + hide back button for this context
        findViewById<TextView>(R.id.textViewFinding).text = "Building overlay…"
        findViewById<ImageView>(R.id.backButton).visibility = View.GONE
    }

    // Finish ourselves as soon as we’re covered by the viewer
    override fun onPause() {
        super.onPause()
        finish()
    }
}
