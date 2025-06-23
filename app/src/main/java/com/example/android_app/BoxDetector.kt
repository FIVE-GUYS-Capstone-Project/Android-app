package com.example.android_app

import android.content.Context
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.IOException
import java.nio.MappedByteBuffer

class BoxDetector(context: Context) {

    private var tflite: Interpreter? = null

    init {
        try {
            val model: MappedByteBuffer = FileUtil.loadMappedFile(context, "best-fp16.tflite")
            tflite = Interpreter(model)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}
