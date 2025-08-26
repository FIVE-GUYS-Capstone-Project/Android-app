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
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import java.util.UUID

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
    private var imageReady = false
    private var depthReady = false
    private var finalImage: ByteArray? = null
    private var finalDepth: ByteArray? = null
    private val FORCE_DEPTH_100_DEBUG = true
    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }
    private val serviceUuid = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val txUuid =
        UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb") // Image/Depth notifications
    private val ctrlUuid = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb")
    private val ctrlCccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val main = Handler(Looper.getMainLooper())
    private val writeQueue: ArrayDeque<ByteArray> = ArrayDeque()
    @Volatile
    private var writeBusy = false

    // === Composite frame gating (waiting screen + timeout) ===
        private var waitingShown = false
        private var launchedPartial = false
        private var launchedFull = false
        private var waitTimeoutRunnable: Runnable? = null
        private val WAIT_TIMEOUT_MS = 7000L   // fallback for partial launch; tweak as you like

        private fun showWaitingOnce() {
        if (!waitingShown) {
            waitingShown = true
            val i = Intent(context.applicationContext, FrameWaitingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.applicationContext.startActivity(i)
            }
        }

    private fun cancelWaitTimeout() {
        waitTimeoutRunnable?.let { main.removeCallbacks(it) }
        waitTimeoutRunnable = null
    }

    private fun schedulePartialTimeoutIfNeeded() {
        if (waitTimeoutRunnable != null) return
        waitTimeoutRunnable = Runnable {
            // If we still don't have both, deliver whatever we have (once).
            if (!launchedFull) {
                internalLaunchViewer(allowPartial = true)
            }
        }.also { main.postDelayed(it, WAIT_TIMEOUT_MS) }
    }

    private fun resetCompositeLatch() {
        launchedPartial = false
        launchedFull = false
        waitingShown = false
        cancelWaitTimeout()
    }

    // === Depth META / control opcodes ===
    private companion object {
        const val TAG_META = 0x12
        const val TYPE_DEPTH = 0x02
        const val CREDITS_INIT = 10
        val OP_CREDITS: Byte = 0xA0.toByte()
        val OP_ACK: Byte = 0xA1.toByte()
        val OP_NAK: Byte = 0xA2.toByte()
    }

    private var expectedLen = 0
    private var received = 0
    private var frameBuf: ByteArray? = null
    private var seen = java.util.BitSet()
    private var currentType = 0
    private var payloadSize = 242

    // Depth META state
    private var metaAwaiting = false
    private var currentMeta: DepthMeta? = null
    private var creditTopUpCounter = 0

    // Throughput metrics / guard
    private var frameStartMs: Long = 0
    private var depthNakCount = 0
    private var fastStreak = 0
    private var currentDepthRes = 100 // 100, 50, 25 (for UI toast only)
    private val SLOW_FRAME_MS = 1500L
    private val HIGH_NAKS = 8

    // ==== BLE Scanning ====
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        if (isScanning) return
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(
                context, "Please enable Bluetooth",
                Toast.LENGTH_SHORT
            ).show()
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
            Toast.makeText(
                context, "Missing BLUETOOTH_SCAN permission",
                Toast.LENGTH_SHORT
            ).show()
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

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
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
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectToDevice(device: BluetoothDevice) {
        val hasConnectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        if (!hasConnectPermission) {
            Toast.makeText(
                context, "Missing BLUETOOTH_CONNECT permission",
                Toast.LENGTH_SHORT
            )
                .show()
            return
        }
        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        } catch (e: SecurityException) {
            Log.e("BLE", "SecurityException in connectGatt: ${e.message}")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
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

    data class DepthMeta(
        val frameId: Int,
        val bitDepth: Int,
        val comp: Int,
        val width: Int,
        val height: Int,
        val scaleMinMm: Int,
        val scaleMaxMm: Int,
        val origLen: Int,
        val compLen: Int,
        val crc16: Int
    )

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        else true

    // ==== GATT Callback (includes binary data parsing) ====
    private val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (hasConnectPermission()) {
                        resetPayloadBuffer()
                        try {
                            gatt.requestMtu(247)
                        } catch (e: SecurityException) {
                            Log.w("BLE", "MTU req", e)
                        }
                        listener?.onConnected(gatt.device.name ?: "Unknown")
                    } else {
                        Log.e("BLE", "Missing BLUETOOTH_CONNECT for requestMtu")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d("BLE", "Disconnected from GATT server.")
                    listener?.onDisconnected()
                    resetPayloadBuffer()
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == ctrlUuid) {
                synchronized(writeQueue) { writeBusy = false }
                // Drain on main to avoid binder re-entrancy quirks
                main.post { drainCtrlQueue(gatt) }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE", "MTU changed successfully, new MTU = $mtu")
                // ATT(3) + our 2B seq header
                payloadSize = (mtu - 3 - 2).coerceAtLeast(0)
                // Tell firmware our MTU, then prime credits (queued/serialized)
                enqueueCtrl(
                    gatt,
                    byteArrayOf(
                        0xAA.toByte(),
                        (mtu and 0xFF).toByte(),
                        ((mtu shr 8) and 0xFF).toByte()
                    )
                )
                sendCredits(gatt, CREDITS_INIT)
                if (hasConnectPermission()) {
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        Log.w("BLE", "discoverServices", e)
                    }
                }
            } else {
                Log.e("BLE", "MTU change failed, status = $status")
                if (hasConnectPermission()) {
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        Log.w("BLE", "discoverServices", e)
                    }
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            if (!hasConnectPermission()) { Log.e("BLE","Missing BLUETOOTH_CONNECT"); return }
            resetPayloadBuffer()
            val txChar = gatt.getService(serviceUuid)?.getCharacteristic(txUuid) ?: return

            // Enable notifications
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } else true
            if (!hasPermission) {
                Log.e("BLE", "BLUETOOTH_CONNECT permission not granted")
                return
            }
            try {
                gatt.setCharacteristicNotification(txChar, true)
                val cccd = txChar.getDescriptor(ctrlCccdUuid)
                cccd?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(it)
                }
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            } catch (e: SecurityException) {
                Log.e("BLE", "No Bluetooth Connect permission: ${e.message}")
            }

            // Boost radio and kick off capture via the queued control channel
            main.postDelayed({
                try {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    sendCredits(gatt, CREDITS_INIT)
                    maybeRequestDepthRes(gatt, 100)
                } catch (e: Exception) {
                    Log.e("BLE", "Failed to send credits/CAPTURE: ${e.message}")
                }
            }, 150)
        }

        // CRC16-CCITT (X25)
        private fun crc16Ccitt(data: ByteArray): Int {
            var crc = 0xFFFF
            for (b in data) {
                crc = crc xor ((b.toInt() and 0xFF) shl 8)
                repeat(8) {
                    crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else crc shl 1
                }
                crc = crc and 0xFFFF
            }
            return crc
        }

        private fun parseDepthMeta(b: ByteArray): DepthMeta? {
            if (b.size != 24) return null
            if (b[0] != 0xAA.toByte() || b[1] != TYPE_DEPTH.toByte() || b[2] != 0x01.toByte()) return null
            val frameId = b[3].toInt() and 0xFF
            val bitDepth = b[4].toInt() and 0xFF
            val comp = b[5].toInt() and 0xFF
            fun u16(i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
            fun u32(i: Int) =
                (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

            val w = u16(6);
            val h = u16(8)
            val sMin = u16(10);
            val sMax = u16(12)
            val oLen = u32(14);
            val cLen = u32(18)
            val crc = u16(22)
            return DepthMeta(frameId, bitDepth, comp, w, h, sMin, sMax, oLen, cLen, crc)
        }

        private fun maybeRequestDepthRes(gatt: BluetoothGatt, target: Int) {
            val tgt = if (FORCE_DEPTH_100_DEBUG) 100 else target
            if (tgt == currentDepthRes) return
            currentDepthRes = tgt
            val bin = when (tgt) { 100 -> 1; 50 -> 2; else -> 4 }  // firmware: 1=100x100, 2=50x50, 4=25x25
            enqueueCtrl(gatt, "BINN=$bin".toByteArray())
            main.post {
                Toast.makeText(context, "Depth set to ${tgt}×${tgt}", Toast.LENGTH_SHORT).show()
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            ch: BluetoothGattCharacteristic
        ) {
            val v = ch.value ?: return
            // Handle Depth META tag [AA,12] then 24-byte header
            if (v.size == 2 && (v[0].toInt() and 0xFF) == 0xAA && (v[1].toInt() and 0xFF) == TAG_META) {
                metaAwaiting = true
                return
            }
            if (metaAwaiting) {
                val meta = parseDepthMeta(v)
                if (meta == null) {
                    Log.e("BLE", "Bad Depth META len=${v.size}"); metaAwaiting = false; return
                }
                currentMeta = meta
                // Allocate using compLen (32-bit) and reset counters (Depth only)
                frameBuf = ByteArray(meta.compLen)
                expectedLen = meta.compLen
                received = 0
                seen.clear()
                currentType = 2 // depth
                // Prime credits
                bluetoothGatt?.let { sendCredits(it, CREDITS_INIT / 2) }
                metaAwaiting = false
                return
            }
            if (v.isEmpty()) return
            val tag = v[0].toInt() and 0xFF
            // 6-byte control/header from firmware
            if (tag == 0xAA) {
                if (v.size < 6) return
                val type = v[1].toInt() and 0xFF
                val len = ((v[5].toInt() and 0xFF) shl 8) or (v[4].toInt() and 0xFF)
                when (type) {
                    0x01 -> { // IMAGE start
                        expectedLen = len
                        received = 0
                        seen.clear()
                        frameBuf = ByteArray(len)
                        currentType = 1
                        frameStartMs = System.currentTimeMillis()
                        depthNakCount = 0
                        Log.d("BLE", "Frame start: IMAGE expected=$len")
                        // New frame window begins — clear any previous waiting/launch state
                        resetCompositeLatch()
                        imageReady = false; depthReady = false
                        finalImage = null; finalDepth = null
                    }

                    0x02 -> { // DEPTH start
                        val meta = currentMeta
                        val eLen = if (meta != null && meta.compLen > 0) meta.compLen else len
                        expectedLen = eLen
                        received = 0
                        seen.clear()
                        frameBuf = ByteArray(eLen)
                        currentType = 2
                        Log.d("BLE", "Frame start: DEPTH expected=$eLen (len16=$len)")
                    }

                    0x09 -> { // IMAGE EOF
                        Log.d(
                            "BLE", "Image EOF received (bytes so far: " +
                                    "$received / $expectedLen)"
                        )
                        if (currentType == 1 && frameBuf != null && received == expectedLen) {
                            finalImage = frameBuf!!.copyOf(expectedLen)
                            imageReady = true
                        } else {
                            Log.e(
                                "BLE", "Image incomplete, dropping (" +
                                        "${received}/${expectedLen})"
                            )
                        }
                        frameBuf = null
                        currentType = 0
                        checkAndLaunchViewer()
                    }

                    0x0A -> { // DEPTH EOF
                        Log.d(
                            "BLE", "Depth EOF received (bytes so far: " +
                                    "$received / $expectedLen)"
                        )
                        // If any chunk missing, request one at a time and wait
                        a@ for (i in 0 until ((expectedLen + payloadSize - 1) / payloadSize)) {
                            if (!seen.get(i)) {
                                depthNakCount++
                                bluetoothGatt?.let { sendNAK(it, i) }
                                return
                            }
                        }
                        // CRC over the assembled compressed payload
                        currentMeta?.let { meta ->
                            val buf = frameBuf ?: return
                            val crcOk = (crc16Ccitt(buf) == meta.crc16)
                            if (!crcOk) {
                                Log.e("BLE", "Depth CRC failed"); return
                            }
                        }
                        if (currentType == 2 && frameBuf != null && received == expectedLen) {
                            finalDepth = frameBuf!!.copyOf(expectedLen)
                            depthReady = true
                        } else {
                            Log.e(
                                "BLE", "Depth incomplete, dropping (" +
                                        "${received}/${expectedLen})"
                            )
                        }
                        // ---- Guardrail logic
                        val elapsed = (System.currentTimeMillis() - frameStartMs).coerceAtLeast(0)
                        val wasSlow = elapsed > SLOW_FRAME_MS
                        val wasLossy = depthNakCount >= HIGH_NAKS

                        if (!FORCE_DEPTH_100_DEBUG) {
                            if (wasSlow || wasLossy) {
                                fastStreak = 0
                                maybeRequestDepthRes(gatt, 50)
                            } else {
                                fastStreak++
                                if (fastStreak >= 3 && currentDepthRes < 100) {
                                    maybeRequestDepthRes(gatt, 100)
                                }
                            }
                        } else {
                            fastStreak++ // keep a counter, but never downscale in debug mode
                        }
                        Log.d("BLE", "Frame done: ${elapsed}ms, NAKs=$depthNakCount, streak=$fastStreak, res=$currentDepthRes")
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

                if (seen.get(seq)) {
                    Log.w("BLE", "Duplicate chunk $seq skipped")
                    return
                }

                val remaining = (expectedLen - offset).coerceAtLeast(0)
                val copyLen = if (payLen < remaining) payLen else remaining
                if (copyLen <= 0 || offset + copyLen > buf.size) {
                    Log.e("BLE", "Chunk $seq out of range (off=$offset len=$copyLen exp=$expectedLen)")
                    return
                }

                System.arraycopy(v, 2, buf, offset, copyLen)
                seen.set(seq)
                received += copyLen

                // ACK once, only after we accepted the chunk
                sendAck(gatt, seq)

                // Top up credits periodically
                if (++creditTopUpCounter >= CREDITS_INIT / 2) {
                    sendCredits(gatt, CREDITS_INIT / 2)
                    creditTopUpCounter = 0
                }

                Log.d("BLE", "Received chunk $seq size=$payLen (total $received/$expectedLen)")
                return
            }
        }
    }

    private fun enqueueCtrl(gatt: BluetoothGatt, bytes: ByteArray) {
        synchronized(writeQueue) {
            writeQueue.add(bytes)
            if (!writeBusy) {
                if (hasConnectPermission()) {
                    try { drainCtrlQueue(gatt) }
                    catch (se: SecurityException) { Log.w("BLE", "drain", se) }
                } else {
                    Log.w("BLE", "No BLUETOOTH_CONNECT; skipping drain")
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun drainCtrlQueue(gatt: BluetoothGatt) {
        val svc = gatt.getService(serviceUuid) ?: return
        val ctrl = svc.getCharacteristic(ctrlUuid) ?: return

        val next: ByteArray = (synchronized(writeQueue) {
            if (writeBusy || writeQueue.isEmpty()) null
            else {
                writeBusy = true
                writeQueue.removeFirst()
            }
        } as ByteArray?) ?: return

        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(ctrl, next, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            ctrl.value = next
            ctrl.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(ctrl)
        }
    }

    private fun sendCredits(gatt: BluetoothGatt, n: Int) =
        enqueueCtrl(gatt, byteArrayOf(OP_CREDITS, n.toByte()))

    private fun sendNAK(gatt: BluetoothGatt, seq: Int) =
        enqueueCtrl(
            gatt,
            byteArrayOf(OP_NAK, (seq and 0xFF).toByte(), ((seq ushr 8) and 0xFF).toByte())
        )

    private fun sendAck(gatt: BluetoothGatt, seq: Int) =
        enqueueCtrl(
            gatt,
            byteArrayOf(OP_ACK, (seq and 0xFF).toByte(), ((seq ushr 8) and 0xFF).toByte())
        )

    private fun resetPayloadBuffer() {
        expectedLen = 0
        received = 0
        frameBuf = null
        seen.clear()
        currentType = 0
        metaAwaiting = false
        currentMeta = null
        creditTopUpCounter = 0
        imageReady = false
        depthReady = false
        finalImage = null
        finalDepth = null
        resetCompositeLatch()
        Log.d("BLE_Debug", "Buffer/META reset")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disableNotifications() {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission
                    .BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        if (!hasPermission) {
            Log.e(
                "BLE",
                "Missing BLUETOOTH_CONNECT permission — cannot disable notifications."
            )
            return
        }
        val characteristic = bluetoothGatt?.getService(serviceUuid)?.getCharacteristic(txUuid)
        val descriptor = characteristic?.getDescriptor(
            UUID.fromString(
                "00002902-0000-1000-8000-00805f9b34fb"
            )
        )
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

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun getDeviceByAddress(address: String): BluetoothDevice? {
        return try {
            bluetoothAdapter.getRemoteDevice(address)
        } catch (_: Exception) {
            null
        }
    }

    /**
         * After each EOF, call this. It:
         * - shows a loading screen if only one stream arrived,
         * - launches DataViewer once both are present,
         * - (optionally) launches partial after WAIT_TIMEOUT_MS.
    */
    private fun checkAndLaunchViewer() {
        val haveImg = imageReady && (finalImage != null)
        // Require META so viewer can size/scale correctly
        val haveDepth = depthReady && (finalDepth != null) && ((currentMeta?.width ?: 0) > 0)

        if (launchedFull) return

        if (haveImg && haveDepth) {
            internalLaunchViewer(allowPartial = false)
            return
        }

        // We have at least one — show waiting UI and arm timeout for partial fallback
        if (haveImg || haveDepth) {
            showWaitingOnce()
            schedulePartialTimeoutIfNeeded()
        }
    }

    /**
         * Actually start DataViewerActivity with what we have.
         * If allowPartial=false, this will only run when both buffers are ready.
     */
    private fun internalLaunchViewer(allowPartial: Boolean) {
        val haveImg = imageReady && (finalImage != null)
        val haveDepth = depthReady && (finalDepth != null) && ((currentMeta?.width ?: 0) > 0)
        if (!allowPartial && !(haveImg && haveDepth)) return

        val intent = Intent(context.applicationContext, DataViewerActivity::class.java).apply {
            if (haveImg)   putExtra("imageBytes", finalImage)
            if (haveDepth) {
                val m = currentMeta!!
                putExtra("depthBytes",   finalDepth)
                putExtra("depthWidth",   m.width)
                putExtra("depthHeight",  m.height)
                putExtra("depthScaleMin", m.scaleMinMm)
                putExtra("depthScaleMax", m.scaleMaxMm)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.applicationContext.startActivity(intent)

        // Mark launched & clean up for the next frame
        if (allowPartial) launchedPartial = true else launchedFull = true
        cancelWaitTimeout()

        if (!allowPartial) {
            imageReady = false
            depthReady = false
            finalImage = null
            finalDepth = null
            if (haveDepth) currentMeta = null // META is frame-scoped
        }
    }
}
