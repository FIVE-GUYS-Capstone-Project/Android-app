package com.example.android_app

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.Animation
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.view.animation.AnimationUtils

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

        val pulse1 = findViewById<View>(R.id.pulse1)
        val pulse2 = findViewById<View>(R.id.pulse2)
        val pulse3 = findViewById<View>(R.id.pulse3)
        val anim = AnimationUtils.loadAnimation(this, R.anim.spread_pulse)
        anim.repeatCount = Animation.INFINITE

        checkBluetoothState()

        bluetoothCircle.setOnClickListener {
            checkBluetoothState()
        }
    }

    override fun onResume() {
        super.onResume()
        checkBluetoothState()
    }

    private fun checkBluetoothState() {
        if (bluetoothAdapter?.isEnabled == true) {
            bluetoothStatusText.text = "Bluetooth is ON. You're ready to go!"
            bluetoothCircle.setBackgroundResource(R.drawable.circle_background_on)
            startPulseAnimation()
        } else {
            bluetoothStatusText.text = "Bluetooth is OFF..."
            bluetoothCircle.setBackgroundResource(R.drawable.circle_background)
            stopPulseAnimation()
        }
    }

    private fun startPulseAnimation() {
        val pulse1 = findViewById<View>(R.id.pulse1)
        val pulse2 = findViewById<View>(R.id.pulse2)
        val pulse3 = findViewById<View>(R.id.pulse3)

        val anim1 = AnimationUtils.loadAnimation(this, R.anim.spread_pulse)
        val anim2 = AnimationUtils.loadAnimation(this, R.anim.spread_pulse)
        val anim3 = AnimationUtils.loadAnimation(this, R.anim.spread_pulse)

        anim1.startOffset = 0
        anim2.startOffset = 600
        anim3.startOffset = 1200

        anim1.repeatCount = Animation.INFINITE
        anim2.repeatCount = Animation.INFINITE
        anim3.repeatCount = Animation.INFINITE

        pulse1.visibility = View.VISIBLE
        pulse2.visibility = View.VISIBLE
        pulse3.visibility = View.VISIBLE

        pulse1.startAnimation(anim1)
        pulse2.startAnimation(anim2)
        pulse3.startAnimation(anim3)
    }

    private fun stopPulseAnimation() {
        val pulse1 = findViewById<View>(R.id.pulse1)
        val pulse2 = findViewById<View>(R.id.pulse2)
        val pulse3 = findViewById<View>(R.id.pulse3)

        pulse1.clearAnimation()
        pulse2.clearAnimation()
        pulse3.clearAnimation()

        pulse1.visibility = View.GONE
        pulse2.visibility = View.GONE
        pulse3.visibility = View.GONE
    }
}
