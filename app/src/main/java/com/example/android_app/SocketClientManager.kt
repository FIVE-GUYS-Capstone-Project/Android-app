package com.example.android_app

import android.os.Handler
import android.os.Looper
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SocketClientManager(
        private val host: String,
        private val port: Int,
        private val onResult: (Float, Float, Float) -> Unit,
private val onError: (String) -> Unit
) {
private var socket: Socket? = null
private var running = false

fun connectAndReceive() {
    Thread {
        try {
            socket = Socket(host, port)
            val input: InputStream = socket!!.getInputStream()
            val buffer = ByteArray(12)
            var totalRead = 0
            while (totalRead < 12) {
                val read = input.read(buffer, totalRead, 12 - totalRead)
                if (read == -1) throw Exception("Connection closed before data received")
                totalRead += read
            }
            val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
            val length = bb.float
            val width = bb.float
            val height = bb.float
            Handler(Looper.getMainLooper()).post {
                onResult(length, width, height)
            }
        } catch (e: Exception) {
            Handler(Looper.getMainLooper()).post {
                onError(e.message ?: "Socket error")
            }
        } finally {
            socket?.close()
        }
    }.start()
}

fun disconnect() {
    running = false
    socket?.close()
}
}
