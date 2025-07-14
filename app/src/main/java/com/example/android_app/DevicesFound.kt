package com.example.android_app

import android.content.res.Resources
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View

class DevicesFound : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_devices_found)

        val circleView = findViewById<View>(R.id.circleView)

        circleView.post {
            val screenWidth = Resources.getSystem().displayMetrics.widthPixels
            val params = circleView.layoutParams
            params.width = (screenWidth * 1.5).toInt()
            params.height = params.width // keep it a circle
            circleView.layoutParams = params

            // Shift up so only 30% of it shows (70% hidden)
            circleView.translationY = -(params.height * 0.7f)
        }
    }
}