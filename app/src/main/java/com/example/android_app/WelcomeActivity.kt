package com.example.android_app

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class WelcomeActivity : AppCompatActivity() {

    private lateinit var bluetoothStatusText: TextView
    private lateinit var bluetoothCircle: FrameLayout
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        BluetoothAdapter.getDefaultAdapter()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main1)

        bluetoothStatusText = findViewById(R.id.bluetoothStatus)
        bluetoothCircle = findViewById(R.id.bluetoothCircle)

        updateBluetoothStatus()

        bluetoothCircle.setOnClickListener {
            if (bluetoothAdapter?.isEnabled == true) {
                // ✅ Only go to MainActivity when user taps AND Bluetooth is ON
                val intent = Intent(this, MainActivity::class.java)
                startActivity(intent)
                finish()
            } else {
                Toast.makeText(this, "Please turn on Bluetooth first.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateBluetoothStatus()
    }

    private fun updateBluetoothStatus() {
        if (bluetoothAdapter == null) {
            bluetoothStatusText.text = "Bluetooth not supported on this device."
            bluetoothCircle.setBackgroundResource(R.drawable.circle_background)
        } else if (bluetoothAdapter?.isEnabled == true) {
            bluetoothStatusText.text = "Bluetooth is ON. You're ready to go!"
            bluetoothCircle.setBackgroundResource(R.drawable.circle_background_on) // ORANGE
        } else {
            bluetoothStatusText.text =
                "Your Bluetooth is off. Please turn on\nBluetooth to continue your next steps."
            bluetoothCircle.setBackgroundResource(R.drawable.circle_background) // GRAY
        }
    }

}
