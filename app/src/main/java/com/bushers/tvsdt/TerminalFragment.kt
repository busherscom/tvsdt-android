package com.bushers.tvsdt

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.*
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.*
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.fragment.app.Fragment
import com.bushers.tvsdt.CustomProber.customProber
import com.bushers.tvsdt.SerialService.SerialBinder
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialPort.ControlLine
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import java.util.*

class TerminalFragment : Fragment(), ServiceConnection, SerialListener {
    private enum class Connected {
        False, Pending, True
    }

    private var deviceId = 0
    private var portNum = 0
    private var baudRate = 0
    private var newline = "\r\n"
    private var receiveText: TextView? = null
    private var usbSerialPort: UsbSerialPort? = null
    private var service: SerialService? = null
    private var initialStart = true
    private var connected = Connected.False
    private val broadcastReceiver: BroadcastReceiver
    private var controlLines: ControlLines? = null

    /*
     * Lifecycle
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        retainInstance = true
        deviceId = arguments!!.getInt("device")
        portNum = arguments!!.getInt("port")
        baudRate = arguments!!.getInt("baud")
    }

    override fun onDestroy() {
        if (connected != Connected.False) disconnect()
        activity!!.stopService(Intent(activity, SerialService::class.java))
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (service != null) service!!.attach(this) else activity!!.startService(Intent(activity, SerialService::class.java)) // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    override fun onStop() {
        if (service != null && !activity!!.isChangingConfigurations) service!!.detach()
        super.onStop()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        activity!!.bindService(Intent(activity, SerialService::class.java), this, Context.BIND_AUTO_CREATE)
    }

    override fun onDetach() {
        try {
            activity!!.unbindService(this)
        } catch (ignored: Exception) {
        }
        super.onDetach()
    }

    override fun onResume() {
        super.onResume()
        activity!!.registerReceiver(broadcastReceiver, IntentFilter(Constants.INTENT_ACTION_GRANT_USB))
        if (initialStart && service != null) {
            initialStart = false
            activity!!.runOnUiThread { connect() }
        }
        if (controlLines != null && connected == Connected.True) controlLines!!.start()
    }

    override fun onPause() {
        activity!!.unregisterReceiver(broadcastReceiver)
        if (controlLines != null) controlLines!!.stop()
        super.onPause()
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = (binder as SerialBinder).service
        service!!.attach(this)
        if (initialStart && isResumed) {
            initialStart = false
            activity!!.runOnUiThread { connect() }
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
    }

    /*
     * UI
     */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_terminal, container, false)
        val color = getString(java.lang.String.valueOf(R.color.colorReceiveText).toInt())
        receiveText = view.findViewById(R.id.receive_text) // TextView performance decreases with number of spans
        receiveText?.setTextColor(Color.parseColor(color)) // set as default color to reduce number of spans
        receiveText?.movementMethod = ScrollingMovementMethod.getInstance()
        val sendText = view.findViewById<TextView>(R.id.send_text)
        val sendBtn = view.findViewById<View>(R.id.send_btn)
        sendBtn.setOnClickListener { send(sendText.text.toString()) }
        controlLines = ControlLines(view)
        return view
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_terminal, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.clear -> {
                receiveText!!.text = ""
                true
            }
            R.id.newline -> {
                val newlineNames = resources.getStringArray(R.array.newline_names)
                val newlineValues = resources.getStringArray(R.array.newline_values)
                val pos = listOf(*newlineValues).indexOf(newline)
                val builder = AlertDialog.Builder(activity)
                builder.setTitle("Newline")
                builder.setSingleChoiceItems(newlineNames, pos) { dialog: DialogInterface, item1: Int ->
                    newline = newlineValues[item1]
                    dialog.dismiss()
                }
                builder.create().show()
                true
            }
            else -> {
                super.onOptionsItemSelected(item)
            }
        }
    }

    /*
     * Serial + UI
     */
    private fun connect(permissionGranted: Boolean? = null) {
        var device: UsbDevice? = null
        val usbManager = activity!!.getSystemService(Context.USB_SERVICE) as UsbManager
        for (v in usbManager.deviceList.values) if (v.deviceId == deviceId) device = v
        if (device == null) {
            status("connection failed: device not found")
            return
        }
        var driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            driver = customProber.probeDevice(device)
        }
        if (driver == null) {
            status("connection failed: no driver for device")
            return
        }
        if (driver.ports.size < portNum) {
            status("connection failed: not enough ports at device")
            return
        }
        usbSerialPort = driver.ports[portNum]
        val usbConnection = usbManager.openDevice(driver.device)
        if (usbConnection == null && permissionGranted == null && !usbManager.hasPermission(driver.device)) {
            val usbPermissionIntent = PendingIntent.getBroadcast(activity, 0, Intent(Constants.INTENT_ACTION_GRANT_USB), 0)
            usbManager.requestPermission(driver.device, usbPermissionIntent)
            return
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(driver.device)) status("connection failed: permission denied") else status("connection failed: open failed")
            return
        }
        connected = Connected.Pending
        try {
            usbSerialPort?.open(usbConnection)
            usbSerialPort?.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            val socket = SerialSocket(activity!!.applicationContext, usbConnection, usbSerialPort)
            service!!.connect(socket)
            // usb connect is not asynchronous. connect-success and connect-error are returned immediately from socket.connect
            // for consistency to bluetooth/bluetooth-LE app use same SerialListener and SerialService classes
            onSerialConnect()
        } catch (e: Exception) {
            onSerialConnectError(e)
        }
    }

    private fun disconnect() {
        connected = Connected.False
        controlLines!!.stop()
        service!!.disconnect()
        usbSerialPort = null
    }

    private fun send(str: String) {
        if (connected != Connected.True) {
            Toast.makeText(activity, "not connected", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val color = getString(java.lang.String.valueOf(R.color.colorSendText).toInt())
            val spn = SpannableStringBuilder("""
    $str
    
    """.trimIndent())
            spn.setSpan(ForegroundColorSpan(Color.parseColor(color)), 0, spn.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            receiveText!!.append(spn)
            val data = (str + newline).toByteArray()
            service!!.write(data)
        } catch (e: Exception) {
            onSerialIoError(e)
        }
    }

    private fun receive(data: ByteArray?) {
        receiveText!!.append(String(data!!))
    }

    fun status(str: String) {
        val color = getString(java.lang.String.valueOf(R.color.colorStatusText).toInt())
        val spn = SpannableStringBuilder("""
    $str
    
    """.trimIndent())
        spn.setSpan(ForegroundColorSpan(Color.parseColor(color)), 0, spn.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        receiveText!!.append(spn)
    }

    /*
     * SerialListener
     */
    override fun onSerialConnect() {
        status("connected")
        connected = Connected.True
        controlLines!!.start()
    }

    override fun onSerialConnectError(e: Exception?) {
        status("connection failed: " + e!!.message)
        disconnect()
    }

    override fun onSerialRead(data: ByteArray?) {
        receive(data)
    }

    override fun onSerialIoError(e: Exception?) {
        status("connection lost: " + e!!.message)
        disconnect()
    }

    internal inner class ControlLines(view: View) {
        private val mainLooper: Handler = Handler(Looper.getMainLooper())
        private val runnable: Runnable
        private val rtsBtn: ToggleButton
        private val ctsBtn: ToggleButton
        private val dtrBtn: ToggleButton
        private val dsrBtn: ToggleButton
        private val cdBtn: ToggleButton
        private val riBtn: ToggleButton
        private fun toggle(v: View) {
            val btn = v as ToggleButton
            if (connected != Connected.True) {
                btn.isChecked = !btn.isChecked
                Toast.makeText(activity, "not connected", Toast.LENGTH_SHORT).show()
                return
            }
            var ctrl = ""
            try {
                if (btn == rtsBtn) {
                    ctrl = "RTS"
                    usbSerialPort!!.rts = btn.isChecked
                }
                if (btn == dtrBtn) {
                    ctrl = "DTR"
                    usbSerialPort!!.dtr = btn.isChecked
                }
            } catch (e: IOException) {
                status("set" + ctrl + " failed: " + e.message)
            }
        }

        private fun run() {
            if (connected != Connected.True) return
            try {
                val controlLines = usbSerialPort!!.controlLines
                rtsBtn.isChecked = controlLines.contains(ControlLine.RTS)
                ctsBtn.isChecked = controlLines.contains(ControlLine.CTS)
                dtrBtn.isChecked = controlLines.contains(ControlLine.DTR)
                dsrBtn.isChecked = controlLines.contains(ControlLine.DSR)
                cdBtn.isChecked = controlLines.contains(ControlLine.CD)
                riBtn.isChecked = controlLines.contains(ControlLine.RI)
                mainLooper.postDelayed(runnable, refreshInterval.toLong())
            } catch (e: IOException) {
                status("getControlLines() failed: " + e.message + " -> stopped control line refresh")
            }
        }

        fun start() {
            if (connected != Connected.True) return
            try {
                val controlLines = usbSerialPort!!.supportedControlLines
                if (!controlLines.contains(ControlLine.RTS)) rtsBtn.visibility = View.INVISIBLE
                if (!controlLines.contains(ControlLine.CTS)) ctsBtn.visibility = View.INVISIBLE
                if (!controlLines.contains(ControlLine.DTR)) dtrBtn.visibility = View.INVISIBLE
                if (!controlLines.contains(ControlLine.DSR)) dsrBtn.visibility = View.INVISIBLE
                if (!controlLines.contains(ControlLine.CD)) cdBtn.visibility = View.INVISIBLE
                if (!controlLines.contains(ControlLine.RI)) riBtn.visibility = View.INVISIBLE
                run()
            } catch (e: IOException) {
                Toast.makeText(activity, "getSupportedControlLines() failed: " + e.message, Toast.LENGTH_SHORT).show()
            }
        }

        fun stop() {
            mainLooper.removeCallbacks(runnable)
            rtsBtn.isChecked = false
            ctsBtn.isChecked = false
            dtrBtn.isChecked = false
            dsrBtn.isChecked = false
            cdBtn.isChecked = false
            riBtn.isChecked = false
        }


            private val refreshInterval = 200 // msec


        init {
            runnable = Runnable { this.run() } // w/o explicit Runnable, a new lambda would be created on each postDelayed, which would not be found again by removeCallbacks
            rtsBtn = view.findViewById(R.id.controlLineRts)
            ctsBtn = view.findViewById(R.id.controlLineCts)
            dtrBtn = view.findViewById(R.id.controlLineDtr)
            dsrBtn = view.findViewById(R.id.controlLineDsr)
            cdBtn = view.findViewById(R.id.controlLineCd)
            riBtn = view.findViewById(R.id.controlLineRi)
            rtsBtn.setOnClickListener { v: View -> toggle(v) }
            dtrBtn.setOnClickListener { v: View -> toggle(v) }
        }
    }

    init {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Constants.INTENT_ACTION_GRANT_USB) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    connect(granted)
                }
            }
        }
    }
}