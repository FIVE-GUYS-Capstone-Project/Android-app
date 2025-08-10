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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

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
    private val serviceUuid = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val txUuid =
        UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb") // Image/Depth notifications
    private val rxUuid = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    private val ctrlUuid = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb")
    private val chunkMap = mutableMapOf<Int, ByteArray>()
    private var eofReceived = false
    private var isReceivingPayload = false
    private var currentPayloadType: Byte = 0
    private var expectedPayloadLength: Int = 0
    private var receivedPayloadBytes = 0
    private var payloadBuffer = ByteArrayOutputStream()
    private var expectedLen = 0
    private var received = 0
    private var frameBuf: ByteArray? = null
    private var seen = java.util.BitSet()
    private var currentType = 0
    private var payloadSize = 242

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
                    Log.d("BLE", "Connected to GATT server.requesting MTU 247...")
                    gatt.requestMtu(247)
                    listener?.onConnected(gatt.device.name ?: "Unknown")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d("BLE", "Disconnected from GATT server.")
                    listener?.onDisconnected()
                    resetPayloadBuffer()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE", "MTU changed successfully, new MTU = $mtu")
                // Tell firmware our MTU
                val ctrlChar = gatt.getService(serviceUuid)?.getCharacteristic(ctrlUuid)
                ctrlChar?.let {
                    // ATT(3) + our 2B seq header
                    payloadSize = (mtu - 3 - 2).coerceAtLeast(0)
                    it.value = byteArrayOf(0xAA.toByte(), (mtu and 0xFF).toByte(),
                        ((mtu shr 8) and 0xFF).toByte())
                    gatt.writeCharacteristic(it)
                }
                gatt.discoverServices()
            } else {
                Log.e("BLE", "MTU change failed, status = $status")
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
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

        override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val v = ch.value ?: return
            if (v.isEmpty()) return
            val tag = v[0].toInt() and 0xFF
            // 6-byte control/header from firmware
            if (tag == 0xAA) {
                if (v.size < 6) return
                val type = v[1].toInt() and 0xFF
                val len  = ((v[5].toInt() and 0xFF) shl 8) or (v[4].toInt() and 0xFF)
                when (type) {
                    0x01, 0x02 -> { // start of IMAGE / DEPTH
                        expectedLen = len
                        received = 0
                        seen.clear()
                        frameBuf = ByteArray(len)
                        currentType = if (type == 0x01) 1 else 2
                        Log.d("BLE", "Frame start: type=$type (currentType=" +
                                "$currentType), expected=$len")
                    }
                    0x09 -> { // IMAGE EOF
                        Log.d("BLE", "Image EOF received (bytes so far: " +
                                "$received / $expectedLen)")
                        if (currentType == 1 && frameBuf != null && received == expectedLen) {
                            finalImage = frameBuf!!.copyOf(expectedLen)
                            imageReady = true
                        } else {
                            Log.e("BLE", "Image incomplete, dropping (" +
                                    "${received}/${expectedLen})")
                        }
                        frameBuf = null
                        currentType = 0
                        checkAndLaunchViewer()
                    }
                    0x0A -> { // DEPTH EOF
                        Log.d("BLE", "Depth EOF received (bytes so far: " +
                                "$received / $expectedLen)")
                        if (currentType == 2 && frameBuf != null && received == expectedLen) {
                            finalDepth = frameBuf!!.copyOf(expectedLen)
                            depthReady = true
                        } else {
                            Log.e("BLE", "Depth incomplete, dropping (" +
                                    "${received}/${expectedLen})")
                        }
                        frameBuf = null
                        currentType = 0
                        checkAndLaunchViewer()
                    }
                    else -> {
                        // Unknown control; ignore
                    }
                }
                return
            }
            // Data chunk: [seqLo, seqHi, payload...]
            if (v.size >= 2) {
                val buf = frameBuf ?: return
                val seq = (v[0].toInt() and 0xFF) or ((v[1].toInt() and 0xFF) shl 8)
                val payLen = v.size - 2
                val offset = seq * payloadSize
                // ACK every chunk
                sendAck(gatt, seq)
                if (seen.get(seq)) {
                    Log.w("BLE", "Duplicate chunk $seq skipped")
                    return
                }
                val copyLen = kotlin.math.min(payLen, kotlin.math.max(0,
                    expectedLen - offset))
                if (copyLen <= 0 || offset + copyLen > buf.size) {
                    Log.e("BLE", "Chunk $seq out of range (off=$offset " +
                            "len=$copyLen exp=$expectedLen)")
                    return
                }
                System.arraycopy(v, 2, buf, offset,
                    copyLen)
                seen.set(seq)
                received += copyLen
                Log.d("BLE", "Received chunk $seq size=$payLen (total " +
                        "$received/$expectedLen)")
                return
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
                    Log.d("BLE", "Image EOF received (bytes so far: " +
                            "$receivedPayloadBytes / $expectedPayloadLength)")
                    if (receivedPayloadBytes == expectedPayloadLength) {
                        assembleAndStoreImage()
                    } else {
                        Log.e("BLE", "❌ Image EOF: missing data! Received " +
                                "$receivedPayloadBytes / $expectedPayloadLength. Not decoding.")
                    // Optionally: show user message, or request retransmit if protocol supports
                    }
                    return
                }
                0x0A.toByte() -> { // Depth EOF
                    eofReceived = true
                    Log.d("BLE", "Depth EOF received (bytes so far: " +
                            "$receivedPayloadBytes / $expectedPayloadLength)")
                    if (receivedPayloadBytes == expectedPayloadLength) {
                        assembleAndStoreDepth()
                    } else {
                        Log.e("BLE", "❌ Depth EOF: missing data! Received " +
                                "$receivedPayloadBytes / $expectedPayloadLength. Not decoding.")
                    }
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
            Log.d("BLE", "Received chunk $seq size=${chunkData.size} (" +
                    "total $receivedPayloadBytes/$expectedPayloadLength)")
            bluetoothGatt?.let { sendAck(it, seq) }
        }
    }

    private fun sendAck(gatt: BluetoothGatt, seq: Int) {
        val svc = gatt.getService(serviceUuid) ?: return
        val ctrl = svc.getCharacteristic(ctrlUuid) ?: return
        val ack = byteArrayOf(0xA1.toByte(), (seq and 0xFF).toByte(),
            ((seq ushr 8) and 0xFF).toByte())
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(ctrl, ack,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            ctrl.value = ack
            ctrl.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(ctrl)
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
        sorted.forEach { (_, bytes) ->
            output.write(bytes)
            totalBytes += bytes.size
        }
        Log.d("BLE", "Total image bytes before decoding = $totalBytes " +
                "(expected $expectedPayloadLength)")
        if (totalBytes != expectedPayloadLength) {
            Log.e("BLE", "❌ Image assembly failed: only $totalBytes / " +
                    "$expectedPayloadLength bytes received. Not decoding.")
            return
        }
        val fullData = output.toByteArray()
        finalImage = fullData
        imageReady = true
        chunkMap.clear()
        checkAndLaunchViewer()
    }

    private fun assembleAndStoreDepth() {
        val sorted = chunkMap.toSortedMap()
        val output = ByteArrayOutputStream()
        var totalBytes = 0
        sorted.forEach { (_, bytes) -> output.write(bytes); totalBytes += bytes.size }
        Log.d("BLE", "Total depth bytes before decoding = $totalBytes " +
                "(expected $expectedPayloadLength)")
        if (totalBytes != expectedPayloadLength) {
            Log.e("BLE", "❌ Depth assembly failed: only $totalBytes / " +
                    "$expectedPayloadLength bytes received. Not decoding.")
            return
        }
        finalDepth = output.toByteArray()
        depthReady = true
        chunkMap.clear()
        checkAndLaunchViewer()
    }

    private fun areAllChunksPresent(expectedCount: Int): Boolean {
        for (i in 0 until expectedCount) {
            if (!chunkMap.containsKey(i)) return false
        }
        return true
    }

    private fun checkAndLaunchViewer() {
        val haveImg   = (imageReady && finalImage != null)
        val haveDepth = (depthReady && finalDepth != null)
        if (!haveImg && !haveDepth) return
        val intent = Intent(context.applicationContext,
            DataViewerActivity::class.java).apply {
            if (haveImg)   putExtra("imageBytes", finalImage)
            if (haveDepth) putExtra("depthBytes", finalDepth)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // launching from non-Activity
        }
        context.applicationContext.startActivity(intent)
        // reset for next frame
        imageReady = false; depthReady = false
        finalImage = null;  finalDepth = null
    }
}
