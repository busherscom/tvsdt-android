package com.bushers.tvsdt

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.security.InvalidParameterException
import java.util.concurrent.Executors

class SerialSocket internal constructor(context: Context, connection: UsbDeviceConnection?, serialPort: UsbSerialPort?) : SerialInputOutputManager.Listener {
    private val disconnectBroadcastReceiver: BroadcastReceiver
    private val context: Context
    private var listener: SerialListener? = null
    private var connection: UsbDeviceConnection?
    private var serialPort: UsbSerialPort?
    private var ioManager: SerialInputOutputManager? = null
    val name: String
        get() = serialPort!!.driver.javaClass.simpleName.replace("SerialDriver", "")

    @Throws(IOException::class)
    fun connect(listener: SerialListener?) {
        this.listener = listener
        context.registerReceiver(disconnectBroadcastReceiver, IntentFilter(Constants.INTENT_ACTION_DISCONNECT))
        serialPort!!.dtr = true // for arduino, ...
        serialPort!!.rts = true
        ioManager = SerialInputOutputManager(serialPort, this)
        Executors.newSingleThreadExecutor().submit(ioManager)
    }

    fun disconnect() {
        listener = null // ignore remaining data and errors
        if (ioManager != null) {
            ioManager!!.listener = null
            ioManager!!.stop()
            ioManager = null
        }
        if (serialPort != null) {
            try {
                serialPort!!.dtr = false
                serialPort!!.rts = false
            } catch (ignored: Exception) {
            }
            try {
                serialPort!!.close()
            } catch (ignored: Exception) {
            }
            serialPort = null
        }
        if (connection != null) {
            connection!!.close()
            connection = null
        }
        try {
            context.unregisterReceiver(disconnectBroadcastReceiver)
        } catch (ignored: Exception) {
        }
    }

    @Throws(IOException::class)
    fun write(data: ByteArray?) {
        if (serialPort == null) throw IOException("not connected")
        serialPort!!.write(data, WRITE_WAIT_MILLIS)
    }

    override fun onNewData(data: ByteArray) {
        if (listener != null) listener!!.onSerialRead(data)
    }

    override fun onRunError(e: Exception) {
        if (listener != null) listener!!.onSerialIoError(e)
    }

    companion object {
        private const val WRITE_WAIT_MILLIS = 2000 // 0 blocked infinitely on unprogrammed arduino
    }

    init {
        if (context is Activity) throw InvalidParameterException("expected non UI context")
        this.context = context
        this.connection = connection
        this.serialPort = serialPort
        disconnectBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (listener != null) listener!!.onSerialIoError(IOException("background disconnect"))
                disconnect() // disconnect now, else would be queued until UI re-attached
            }
        }
    }
}