package com.example.android_app

import android.app.Application

class MyApp : Application() {
    lateinit var bluetoothLeManager: BluetoothLeManager
    override fun onCreate() {
        super.onCreate()
        bluetoothLeManager = BluetoothLeManager(this)
    }
}
