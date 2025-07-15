package com.example.android_app

import android.app.Application
import android.content.Context

class MyApplication : Application() {
    lateinit var bluetoothLeManager: BluetoothLeManager

    override fun onCreate() {
        super.onCreate()
        bluetoothLeManager = BluetoothLeManager(this)
    }

    companion object {
        fun getInstance(context: Context): MyApplication {
            return context.applicationContext as MyApplication
        }
    }
}