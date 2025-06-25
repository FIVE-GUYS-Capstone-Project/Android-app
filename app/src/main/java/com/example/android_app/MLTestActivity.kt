package com.example.android_app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.android_app.databinding.ActivityMltestBinding
import java.io.InputStream
import com.example.android_app.BoxDetector

class MLTestActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMltestBinding
    private lateinit var boxDetector: BoxDetector

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val inputStream = contentResolver.openInputStream(it)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            val resized = Bitmap.createScaledBitmap(bitmap, 640, 640, true)

            val box = boxDetector.runInference(resized)
            if (box != null) {
                binding.imageView.setOverlay(resized, box)
                binding.resultText.text = "Detected box: ${box.left},${box.top} to ${box.right},${box.bottom}"
            } else {
                binding.resultText.text = "No box detected"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMltestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        boxDetector = BoxDetector(this)

        binding.btnPickImage.setOnClickListener {
            pickImage.launch("image/*")
        }
    }
}
