package com.example.android_app

class MLTestActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMltestBinding
    private lateinit var boxDetector: BoxDetector

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val inputStream = contentResolver.openInputStream(it)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            val resized = Bitmap.createScaledBitmap(bitmap, 640, 640, true)
            binding.imageView.setImageBitmap(resized)

            // ML inference (mock for now)
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
