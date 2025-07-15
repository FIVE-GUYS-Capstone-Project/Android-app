package com.example.android_app

import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import androidx.core.content.ContextCompat

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

        val devices = listOf("DEVICE 1", "DEVICE 2", "DEVICE 3", "DEVICE 4", "DEVICE 5", "DEVICE 6")
        devices.forEach { addDeviceRow(it) }
    }

    private fun addDeviceRow(name: String) {
        // Container đại diện cho 1 card
        val cardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 16, 0, 16) // spacing between cards
            }
            setPadding(24, 24, 24, 24) // inner padding in the card
            background = ContextCompat.getDrawable(this@DevicesFound, R.drawable.device_card_background)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val label = TextView(this).apply {
            text = name
            textSize = 16f
            setTextColor(Color.parseColor("#0E4174"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val button = Button(this).apply {
            text = "Connect"
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@DevicesFound, R.drawable.button_connect_background)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            setOnClickListener {
                isEnabled = false
                text = "Connecting"
                setTextColor(Color.WHITE)
                background = ContextCompat.getDrawable(this@DevicesFound, R.drawable.button_connecting_background)
                Toast.makeText(this@DevicesFound, "Connecting to $name", Toast.LENGTH_SHORT).show()
            }
        }

        row.addView(label)
        row.addView(button)
        cardContainer.addView(row)
        deviceContainer.addView(cardContainer)
    }
}
