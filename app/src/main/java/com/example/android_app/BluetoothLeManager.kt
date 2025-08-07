package com.example.android_app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import android.os.HandlerThread
import androidx.annotation.RequiresPermission

class BluetoothLeManager(private val context: Context) {

    interface BleEventListener {
        fun onDeviceFound(deviceInfo: String)
        fun onScanStopped()
        fun onConnected(deviceName: String)
        fun onDisconnected()
        fun onImageReceived(imageBytes: ByteArray)
        fun onDepthReceived(depthBytes: ByteArray)
    }

    var listener: BleEventListener? = null
    private var isScanning = false
    private var hasStoppedScan = false
    private val foundDevices = mutableSetOf<String>()
    private var bluetoothGatt: BluetoothGatt? = null
    private val ackQueue = ConcurrentLinkedQueue<ByteArray>()
    private val ackThread = HandlerThread("AckThread").apply { start() }
    private val ackHandler = Handler(ackThread.looper)
    private var imageReady = false
    private var depthReady = false
    private var finalImage: ByteArray? = null
    private var finalDepth: ByteArray? = null
    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }
    // --- BLE UUIDs for Hardware (camelCase, rxUuid removed as it's unused) ---
    private val serviceUuid = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val txUuid =
        UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb") // Image/Depth notifications
    private val rxUuid = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    private val ctrlUuid = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb")
    // Add at class level
    private data class Chunk(val seq: Int, val data: ByteArray)
    private val chunkMap = mutableMapOf<Int, ByteArray>()
    private var eofReceived = false
    // === For chunked data handling ===
    private var isReceivingPayload = false
    private var currentPayloadType: Byte = 0
    private var expectedPayloadLength: Int = 0
    private var receivedPayloadBytes = 0
    private var payloadBuffer = ByteArrayOutputStream()

    // ==== BLE Scanning ====
    fun startScan() {
        if (isScanning) return
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(context, "Please enable Bluetooth",
                Toast.LENGTH_SHORT).show()
            listener?.onScanStopped()
            return
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e("BLE", "BluetoothLeScanner is null")
            listener?.onScanStopped()
            return
        }
        val hasScanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        if (!hasScanPermission) {
            Toast.makeText(context, "Missing BLUETOOTH_SCAN permission",
                Toast.LENGTH_SHORT).show()
            listener?.onScanStopped()
            return
        }
        isScanning = true
        hasStoppedScan = false
        foundDevices.clear()
        try {
            scanner.startScan(scanCallback)
            Handler(Looper.getMainLooper()).postDelayed({ stopScan() }, 10_000)
            Log.d("BLE", "Started scanning...")
        } catch (e: SecurityException) {
            Log.e("BLE", "SecurityException while starting scan: ${e.message}")
            listener?.onScanStopped()
        }
    }

    fun stopScan() {
        if (!isScanning || hasStoppedScan) return
        hasStoppedScan = true
        isScanning = false
        val hasScanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        if (!hasScanPermission) {
            Log.e("BLE", "Missing BLUETOOTH_SCAN permission")
            listener?.onScanStopped()
            return
        }
        try {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
            Log.d("BLE", "Stopped scanning.")
        } catch (e: SecurityException) {
            Log.e("BLE", "SecurityException while stopping scan: ${e.message}")
        }
        listener?.onScanStopped()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } else true

            if (hasPermission) {
                val name = device.name
                if (name.isNullOrBlank()) return  // Skip unnamed devices

                val address = device.address
                val deviceInfo = "$name - $address"

                if (foundDevices.add(deviceInfo)) {
                    Log.d("BLE", "Found device: $deviceInfo")
                    listener?.onDeviceFound(deviceInfo)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BLE", "Scan failed: $errorCode")
            listener?.onScanStopped()
        }
    }

    // ==== BLE Connection ====
    fun connectToDevice(device: BluetoothDevice) {
        val hasConnectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        if (!hasConnectPermission) {
            Toast.makeText(context, "Missing BLUETOOTH_CONNECT permission",
                Toast.LENGTH_SHORT)
                .show()
            return
        }
        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        } catch (e: SecurityException) {
            Log.e("BLE", "SecurityException in connectGatt: ${e.message}")
        }
    }

    fun disconnect() {
        val hasConnectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        if (!hasConnectPermission) {
            Log.e("BLE", "Missing BLUETOOTH_CONNECT permission")
            return
        }
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
        } catch (e: SecurityException) {
            Log.e("BLE", "SecurityException while disconnecting: ${e.message}")
        }
    }

    // ==== GATT Callback (includes binary data parsing) ====
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d("BLE", "Connected to GATT server.")
                    listener?.onConnected(gatt.device.name ?: "Unknown")
                    val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    } else true
                    if (hasPermission) {
                        gatt.discoverServices()
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d("BLE", "Disconnected from GATT server.")
                    listener?.onDisconnected()
                    resetPayloadBuffer()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val ctrlChar = gatt.getService(serviceUuid)?.getCharacteristic(ctrlUuid)
                val txChar = gatt.getService(serviceUuid)?.getCharacteristic(txUuid)
                txChar?.let {
                    val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    } else true
                    if (hasPermission) {
                        try {
                            gatt.setCharacteristicNotification(it, true)
                            val descriptor =
                                it.getDescriptor(UUID.fromString(
                                    "00002902-0000-1000-8000-00805f9b34fb"))
                            descriptor?.let { d ->
                                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                gatt.writeDescriptor(d)
                            }
                        } catch (e: SecurityException) {
                            Log.e("BLE", "No Bluetooth Connect permission: ${
                                e.message}")
                        }
                    } else {
                        Log.e("BLE", "BLUETOOTH_CONNECT permission not granted")
                    }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == txUuid) {
                val data = characteristic.value
                handleIncomingData(data)

                // Queue ACK instead of blocking write
                ackQueue.offer("ACK".toByteArray())
                if (!isAckInProgress) {
                    processAckQueue(gatt)
                }
            }
        }

        private var isAckInProgress = false
        private fun processAckQueue(gatt: BluetoothGatt) {
            ackHandler.post {
                while (ackQueue.isNotEmpty()) {
                    val ackValue = ackQueue.poll() ?: continue
                    val ackChar = gatt.getService(serviceUuid)?.getCharacteristic(rxUuid)

                    ackChar?.let {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                // Modern signature (API 33+)
                                gatt.writeCharacteristic(
                                    it,
                                    ackValue,
                                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                )
                            } else {
                                // Legacy signature
                                it.value = ackValue
                                it.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                gatt.writeCharacteristic(it)
                            }
                            Log.d("BLE", "ACK sent (queued)")
                        } catch (e: SecurityException) {
                            Log.e("BLE", "No permission to write ACK: ${e.message}")
                        }
                    }
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private fun processNextAck(gatt: BluetoothGatt) {
            val ackValue = ackQueue.poll() ?: run {
                isAckInProgress = false
                return
            }
            isAckInProgress = true
            val ackChar = gatt.getService(serviceUuid)?.getCharacteristic(rxUuid) ?: return
            ackChar.value = ackValue
            ackChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(ackChar)
        }
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == rxUuid) {
                isAckInProgress = false
                processNextAck(gatt) // send the next queued ACK
            }
        }
    }

    // === Chunked Data Handler ===
    fun handleIncomingData(data: ByteArray) {
        if (data.size == 6 && data[0] == 0xAA.toByte()) {
            val type = data[1]
            val seq = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
            val len = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)

            when (type) {
                0x09.toByte() -> { // Image EOF
                    eofReceived = true
                    Log.d("BLE", "Image EOF received")
                    assembleAndStoreImage()
                    return
                }
                0x0A.toByte() -> { // Depth EOF
                    eofReceived = true
                    Log.d("BLE", "Depth EOF received")
                    assembleAndStoreDepth()
                    return
                }
            }

            currentPayloadType = type
            expectedPayloadLength = len
            receivedPayloadBytes = 0
            if (!eofReceived) {
                chunkMap.clear()
            }
            eofReceived = false
            Log.d("BLE", "Header: type=$type seq=$seq len=$len")
            return
        }

        if (data.size > 2) {
            val seq = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
            if (chunkMap.containsKey(seq)) {
                Log.w("BLE", "Duplicate chunk $seq skipped")
                return
            }
            val chunkData = data.copyOfRange(2, data.size)
            chunkMap[seq] = chunkData
            receivedPayloadBytes += chunkData.size
            Log.d("BLE", "Received chunk $seq size=${chunkData.size}")
            bluetoothGatt?.let { sendAck(it, seq) }
        }
    }

    fun sendAck(gatt: BluetoothGatt, seq: Int) {
        val hasConnectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context,
                Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.
            PERMISSION_GRANTED
        } else true
        if (!hasConnectPermission) {
            Log.e("BLE", "Missing BLUETOOTH_CONNECT permission — cannot send ACK.")
            return
        }
        val ack = byteArrayOf(0xAC.toByte(), (seq and 0xFF).toByte(), ((seq shr 8) and 0xFF)
            .toByte())
        val ctrlChar = gatt.getService(serviceUuid)?.getCharacteristic(ctrlUuid)
        ctrlChar?.let {
            it.value = ack
            it.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            gatt.writeCharacteristic(it)
            Log.d("BLE", "ACK sent for chunk $seq")
        }
    }

    private fun resetPayloadBuffer() {
        isReceivingPayload = false
        receivedPayloadBytes = 0
        expectedPayloadLength = 0
        payloadBuffer.reset()
        Log.d("BLE_Debug", "Buffer reset after receiving full payload")
    }

    fun disableNotifications() {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission
                .BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
        if (!hasPermission) {
            Log.e("BLE",
                "Missing BLUETOOTH_CONNECT permission — cannot disable notifications.")
            return
        }
        val characteristic = bluetoothGatt?.getService(serviceUuid)?.getCharacteristic(txUuid)
        val descriptor = characteristic?.getDescriptor(UUID.fromString(
            "00002902-0000-1000-8000-00805f9b34fb"))
        try {
            characteristic?.let {
                bluetoothGatt?.setCharacteristicNotification(it, false)
                descriptor?.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                bluetoothGatt?.writeDescriptor(descriptor)
                Log.d("BLE", "Notifications disabled")
            }
        } catch (e: SecurityException) {
            Log.e("BLE", "SecurityException: ${e.message}")
        } catch (e: Exception) {
            Log.e("BLE", "Failed to disable notifications: ${e.message}")
        }
    }

    fun getDeviceByAddress(address: String): BluetoothDevice? {
        return try { bluetoothAdapter.getRemoteDevice(address) } catch (_: Exception) { null }
    }
    var imageBuffer = ByteArrayOutputStream()
    var expectedImageSize = 0
    var waitingForAck = false
    fun onImageReceived(data: ByteArray) {
        imageBuffer.write(data)
        if (imageBuffer.size() == expectedImageSize) {
            val fullImageData = imageBuffer.toByteArray()
            decompressAndDisplayImage(fullImageData)
        }
    }

    fun decompressAndDisplayImage(compressedData: ByteArray) {
        val decompressedData = decompressDeltaEncodedData(compressedData)
        val bitmap = BitmapFactory.decodeByteArray(decompressedData, 0,
            decompressedData.size)
    }

    fun decompressDeltaEncodedData(compressedData: ByteArray): ByteArray {
        return compressedData
    }

    private fun deltaDecode(input: ByteArray): ByteArray {
        if (input.isEmpty()) return input
        val output = ByteArray(input.size)
        output[0] = input[0]
        for (i in 1 until input.size) {
            output[i] = ((output[i - 1] + input[i]).toInt() and 0xFF).toByte()
        }
        return output
    }

    private fun assembleAndStoreImage() {
        val sorted = chunkMap.toSortedMap()
        val output = ByteArrayOutputStream()
        var totalBytes = 0
        sorted.forEach { (seq, bytes) ->
            Log.d("BLE", "Appending chunk seq=$seq, size=${bytes.size}")
            output.write(bytes)
            totalBytes += bytes.size
        }
        Log.d("BLE", "Total image bytes before decoding = $totalBytes")
        val fullData = output.toByteArray()
        Log.d("BLE", "Assembled ${sorted.size} chunks, total size = ${fullData.size} "
                + "bytes")
        val decoded = deltaDecode(fullData)
        Log.d("BLE", "Delta-decoded size = ${decoded.size} bytes")
        finalImage = decoded
        imageReady = true
        chunkMap.clear()
        checkAndLaunchViewer()
    }

    private fun assembleAndStoreDepth() {
        val sorted = chunkMap.toSortedMap()
        val output = ByteArrayOutputStream()
        sorted.forEach { (_, bytes) -> output.write(bytes) }
        finalDepth = output.toByteArray()
        depthReady = true
        chunkMap.clear()
        checkAndLaunchViewer()
    }

    private fun checkAndLaunchViewer() {
        if (imageReady && depthReady) {
            Log.d("BLE", "Launching DataViewerActivity with image=${
                finalImage?.size} depth=${finalDepth?.size}")

            val intent = Intent(context, DataViewerActivity::class.java)
                .apply {
                putExtra("image", finalImage)
                putExtra("depth", finalDepth)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            imageReady = false
            depthReady = false
        }
    }
}
