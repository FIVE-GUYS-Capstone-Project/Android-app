package com.example.android_app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

            // Mock box position — center 200x200 box
            val mockBox = Rect(220, 220, 420, 420)

            // Set image + box
            binding.imageView.setOverlay(resized, mockBox)

            // Display mock result
            val result = boxDetector.runInferenceMock(resized)
            binding.resultText.text = result

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
