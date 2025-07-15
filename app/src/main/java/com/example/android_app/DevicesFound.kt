package com.example.android_app

import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Button
import android.widget.Toast

class DevicesFound : AppCompatActivity() {
    private lateinit var deviceContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_devices_found)

        deviceContainer = findViewById(R.id.deviceContainer)

        val backButton = findViewById<ImageView>(R.id.backButton)
        backButton.setOnClickListener {
            finish()
        }

        // Example device list (replace with real BLE devices)
        val devices = listOf("DEVICE 1", "DEVICE 2", "DEVICE 3", "DEVICE 4", "DEVICE 5", "DEVICE 6")
        devices.forEach { addDeviceRow(it) }
    }

    private fun addDeviceRow(name: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val label = TextView(this).apply {
            text = name
            textSize = 16f
            setTextColor(Color.parseColor("#0A0E54"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val button = Button(this).apply {
            text = "Connect"
            setBackgroundColor(Color.parseColor("#0A0E54"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                Toast.makeText(this@DevicesFound, "Connecting to $name", Toast.LENGTH_SHORT).show()
            }
        }

        row.addView(label)
        row.addView(button)
        deviceContainer.addView(row)
    }
}
