package com.example.android_app

import android.Manifest
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*

class MainActivity : AppCompatActivity(), BluetoothLeManager.BleEventListener {

    private lateinit var bluetoothLeManager: BluetoothLeManager
    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var openScannerButton: Button
    private lateinit var deviceListLayout: LinearLayout
    private lateinit var dataText: TextView
    private lateinit var progressBar: ProgressBar

    private val handler = Handler(Looper.getMainLooper())
    private var cachedImageBytes: ByteArray? = null
    private var cachedDepthBytes: ByteArray? = null
    private var hasHandledOneCapture = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val backButton = findViewById<Button>(R.id.backButton)
        backButton.setOnClickListener {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
        }

        statusText = findViewById(R.id.statusText)
        scanButton = findViewById(R.id.scanButton)
        openScannerButton = findViewById(R.id.openScannerButton)
        deviceListLayout = findViewById(R.id.deviceListLayout)
        dataText = findViewById(R.id.dataText)
        progressBar = findViewById(R.id.progressBar)
        progressBar.visibility = View.GONE

        bluetoothLeManager = BluetoothLeManager(this)
        bluetoothLeManager.listener = this

        scanButton.setOnClickListener {
            bluetoothLeManager.stopScan()
            statusText.text = "Scanning BLE..."
            deviceListLayout.removeAllViews()
            requestPermissionsAndStartScan()
        }

        openScannerButton.setOnClickListener {
            startActivity(Intent(this, PackageScannerActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        cachedImageBytes = null
        cachedDepthBytes = null
        hasHandledOneCapture = false
    }

    override fun onImageReceived(imageBytes: ByteArray) {
        if (hasHandledOneCapture) return
        Log.d("MainActivity", "📸 onImageReceived triggered")
        cachedImageBytes = imageBytes
        runOnUiThread {
            progressBar.visibility = View.VISIBLE
            scheduleCheckAndNavigate()
        }
    }

    override fun onDepthReceived(depthBytes: ByteArray) {
        if (hasHandledOneCapture) return
        Log.d("MainActivity", "🌊 onDepthReceived triggered")
        cachedDepthBytes = depthBytes
        runOnUiThread {
            progressBar.visibility = View.VISIBLE
            scheduleCheckAndNavigate()
        }
    }

    private fun scheduleCheckAndNavigate() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ checkAndNavigateToViewer() }, 300)
    }

    private fun checkAndNavigateToViewer() {
        Log.d("MainActivity", "🧪 Invoked checkAndNavigateToViewer")
        Log.d("MainActivity", "  image = ${cachedImageBytes?.size}, depth = ${cachedDepthBytes?.size}, handled = $hasHandledOneCapture")

        if (!hasHandledOneCapture && cachedImageBytes != null && cachedDepthBytes != null) {
            hasHandledOneCapture = true
            bluetoothLeManager.disableNotifications()
            try {
                Log.d("MainActivity", "Launching DataViewerActivity")
                val intent = Intent(this, DataViewerActivity::class.java)
                intent.putExtra("imageBytes", cachedImageBytes)
                intent.putExtra("depthBytes", cachedDepthBytes)
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to start DataViewerActivity: ${e.message}")
            }
        } else {
            Log.d("MainActivity", "Not ready to launch. Waiting for both data types.")
        }
    }

    private fun requestPermissionsAndStartScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(permissions)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            checkAndPromptEnableLocation()
        } else {
            Toast.makeText(this, "Permissions not granted", Toast.LENGTH_SHORT).show()
            statusText.text = getString(R.string.permission_denied)
        }
    }

    private fun checkAndPromptEnableLocation() {
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)

        LocationServices.getSettingsClient(this).checkLocationSettings(builder.build())
            .addOnSuccessListener { bluetoothLeManager.startScan() }
            .addOnFailureListener { e ->
                if (e is ResolvableApiException) {
                    try {
                        e.startResolutionForResult(this, 1001)
                    } catch (ex: IntentSender.SendIntentException) {
                        ex.printStackTrace()
                    }
                } else {
                    Toast.makeText(this, "Please enable location services", Toast.LENGTH_LONG).show()
                }
            }
    }

    override fun onDeviceFound(deviceInfo: String) {
        runOnUiThread {
            val deviceName = deviceInfo.substringBefore(" - ").ifEmpty { return@runOnUiThread }
            val deviceAddress = deviceInfo.substringAfterLast(" - ")

            for (i in 0 until deviceListLayout.childCount) {
                val child = deviceListLayout.getChildAt(i) as? TextView
                if (child?.tag == deviceAddress) return@runOnUiThread
            }

            val deviceTextView = TextView(this).apply {
                text = deviceName
                textSize = 16f
                setPadding(12, 12, 12, 12)
                setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
                isClickable = true
                contentDescription = "Bluetooth device: $deviceName"
                tag = deviceAddress

                setOnClickListener {
                    val device = bluetoothLeManager.getDeviceByAddress(deviceAddress)
                    if (device != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            ActivityCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            Toast.makeText(this@MainActivity, "Permission denied to connect", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        bluetoothLeManager.connectToDevice(device)
                        statusText.text = getString(R.string.connecting_to, device.name ?: "Unknown")
                    }
                }
            }

            deviceListLayout.addView(deviceTextView)
        }
    }

    override fun onScanStopped() {
        runOnUiThread { statusText.text = "BLE scan stopped." }
    }

    override fun onConnected(deviceName: String) {
        runOnUiThread { statusText.text = "Connected to $deviceName (BLE)" }
    }

    override fun onDisconnected() {
        runOnUiThread { statusText.text = "Disconnected." }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothLeManager.stopScan()
        bluetoothLeManager.disconnect()
    }
}
