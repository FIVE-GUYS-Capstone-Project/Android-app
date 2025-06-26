package com.example.android_app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.android_app.databinding.ActivityPackageScannerBinding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Activity responsible for scanning packages using a pre-trained TFLite model.
 * Future support for dimension calculation from hardware will be integrated here.
 */
class PackageScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPackageScannerBinding
    private lateinit var boxDetector: BoxDetector

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    val inputStream: InputStream? = contentResolver.openInputStream(it)
                    val bmp = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()
                    bmp
                }

                val resized = withContext(Dispatchers.Default) {
                    Bitmap.createScaledBitmap(bitmap, 640, 640, true)
                }

                val boxes: List<Rect> = withContext(Dispatchers.Default) {
                    boxDetector.runInference(resized)
                }

                if (boxes.isNotEmpty()) {
                    binding.imageView.setOverlay(resized, boxes)
                    binding.resultText.text = "Detected ${boxes.size} box(es)"
                    binding.dimensionText.text = "Dimension: -- cm × -- cm × -- cm"
                } else {
                    binding.imageView.setOverlay(resized, emptyList())
                    binding.resultText.text = "No box detected"
                    binding.dimensionText.text = "Dimension: --"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPackageScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        boxDetector = BoxDetector(this)

        binding.btnPickImage.setOnClickListener {
            pickImage.launch("image/*")
        }
    }
}
