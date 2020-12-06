package com.bushers.tvsdt

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
import androidx.fragment.app.Fragment
import com.bushers.tvsdt.CustomProber.customProber
import com.bushers.tvsdt.SerialService.SerialBinder
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.microsoft.appcenter.analytics.Analytics
import com.microsoft.appcenter.crashes.Crashes
import java.io.IOException


class TerminalFragment : Fragment(), ServiceConnection, SerialListener {
    private enum class Connected {
        False, Pending, True
    }

    private var deviceId = 0
    private var portNum = 0
    private var baudRate = 0
    private var newline = "\n"
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
        if (service != null) service!!.attach(this) else activity!!.startService(
            Intent(
                activity,
                SerialService::class.java
            )
        ) // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    override fun onStop() {
        if (service != null && !activity!!.isChangingConfigurations) service!!.detach()
        super.onStop()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        activity!!.bindService(
            Intent(activity, SerialService::class.java),
            this,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onDetach() {
        try {
            activity!!.unbindService(this)
        } catch (ignored: Exception) {
            Crashes.trackError(ignored)
        }
        super.onDetach()
    }

    override fun onResume() {
        super.onResume()
        activity!!.registerReceiver(
            broadcastReceiver,
            IntentFilter(Constants.INTENT_ACTION_GRANT_USB)
        )
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
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_terminal, container, false)
        val color = getString(java.lang.String.valueOf(R.color.secondaryTextColor).toInt())
        receiveText =
            view.findViewById(R.id.receive_text) // TextView performance decreases with number of spans
        receiveText?.setTextColor(Color.parseColor(color)) // set as default color to reduce number of spans
        receiveText?.movementMethod = ScrollingMovementMethod.getInstance()
        val sendText = view.findViewById<TextView>(R.id.send_text)
        val sendBtn = view.findViewById<View>(R.id.send_btn)
        sendBtn.setOnClickListener {
            send(sendText.text.toString())
            Analytics.trackEvent("OnClick Send Text")
        }
        controlLines = ControlLines(view)
        return view
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_terminal, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        Analytics.trackEvent("OnClick New Line")
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
                builder.setSingleChoiceItems(
                    newlineNames,
                    pos
                ) { dialog: DialogInterface, item1: Int ->
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
            val usbPermissionIntent = PendingIntent.getBroadcast(
                activity,
                0,
                Intent(Constants.INTENT_ACTION_GRANT_USB),
                0
            )
            usbManager.requestPermission(driver.device, usbPermissionIntent)
            return
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(driver.device)) status("connection failed: permission denied") else status(
                "connection failed: open failed"
            )
            return
        }
        connected = Connected.Pending
        try {
            usbSerialPort?.open(usbConnection)
            usbSerialPort?.setParameters(
                baudRate,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            val socket = SerialSocket(activity!!.applicationContext, usbConnection, usbSerialPort)
            service!!.connect(socket)
            // usb connect is not asynchronous. connect-success and connect-error are returned immediately from socket.connect
            // for consistency to bluetooth/bluetooth-LE app use same SerialListener and SerialService classes
            onSerialConnect()
        } catch (e: Exception) {
            onSerialConnectError(e)
            Crashes.trackError(e)
        }
    }

    private fun disconnect() {
        connected = Connected.False
        controlLines!!.stop()
        service!!.disconnect()
        usbSerialPort = null
    }
/*
    private fun openWhatsApp() {
        val smsNumber = "7****" // E164 format without '+' sign
        val sendIntent = Intent(Intent.ACTION_SEND)
        sendIntent.type = "text/plain"
        sendIntent.putExtra(Intent.EXTRA_TEXT, "This is my text to send.")
        sendIntent.putExtra("jid", "$smsNumber@s.whatsapp.net") //phone number without "+" prefix
        sendIntent.setPackage("com.whatsapp")
        if (sendIntent.resolveActivity(activity!!.packageManager) == null) {
            Toast.makeText(this, "Error/n" + e.toString(), Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(sendIntent)
    }
*/
    private fun send(str: String) {
        if (connected != Connected.True) {
            Toast.makeText(activity, "not connected", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val color = getString(java.lang.String.valueOf(R.color.primaryTextColor).toInt())
            val spn = SpannableStringBuilder(
                """
    $str
    
    """.trimIndent()
            )
            spn.setSpan(
                ForegroundColorSpan(Color.parseColor(color)),
                0,
                spn.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            receiveText!!.append(spn)
            val data = (str + newline).toByteArray()
            service!!.write(data)
        } catch (e: Exception) {
            onSerialIoError(e)
            Crashes.trackError(e)
        }
    }

    private fun sendCommand(str: String) {
        if (connected != Connected.True) {
            Toast.makeText(activity, "not connected", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val data = (str + newline).toByteArray()
            service!!.write(data)
        } catch (e: Exception) {
            onSerialIoError(e)
            Crashes.trackError(e)
        }
    }

    private fun receive(data: ByteArray?) {
        receiveText!!.append(String(data!!))
    }

    fun status(str: String) {
        val color = getString(java.lang.String.valueOf(R.color.primaryTextColor).toInt())
        val spn = SpannableStringBuilder(
            """
    $str
    
    """.trimIndent()
        )
        spn.setSpan(
            ForegroundColorSpan(Color.parseColor(color)),
            0,
            spn.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
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
        Crashes.trackError(e)
        disconnect()
    }

    override fun onSerialRead(data: ByteArray?) {
        receive(data)
    }

    override fun onSerialIoError(e: Exception?) {
        status("connection lost: " + e!!.message)
        Crashes.trackError(e)
        disconnect()
    }

    internal inner class ControlLines(view: View) {
        private val mainLooper: Handler = Handler(Looper.getMainLooper())
        private val runnable: Runnable

        private fun run() {
            if (connected != Connected.True) return
            try {
                mainLooper.postDelayed(runnable, refreshInterval.toLong())
            } catch (e: IOException) {
                status("getControlLines() failed: " + e.message + " -> stopped control line refresh")
                Crashes.trackError(e)
            }
        }

        fun start() {
            if (connected != Connected.True) return
            try {
                run()
            } catch (e: IOException) {
                Toast.makeText(
                    activity,
                    "getSupportedControlLines() failed: " + e.message,
                    Toast.LENGTH_SHORT
                ).show()
                Crashes.trackError(e)
            }
        }

        fun stop() {
            mainLooper.removeCallbacks(runnable)
        }


        private val refreshInterval = 200


        init {
            runnable =
                Runnable { this.run() } // w/o explicit Runnable, a new lambda would be created on each postDelayed, which would not be found again by removeCallbacks

            // val keyAccessEnter = view.findViewById<View>(R.id.key_access_1)
            // val keyAccessEsc = view.findViewById<View>(R.id.key_access_2)
            val bootLogo = view.findViewById<View>(R.id.bootlogo)
            val destroyLogo = view.findViewById<View>(R.id.destroy_logo)
            val panelInit = view.findViewById<View>(R.id.panel_init)
            val usbstart = view.findViewById<View>(R.id.usbstart)
            val restoreBackup = view.findViewById<View>(R.id.restore_backup)
            val audioPreinit = view.findViewById<View>(R.id.audio_preinit)
            val custar = view.findViewById<View>(R.id.custar)
            val mmcinfo = view.findViewById<View>(R.id.mmcinfo)
            val reset = view.findViewById<View>(R.id.reset)

            destroyLogo.setOnClickListener {
                sendCommand("destroy_logo")
                Analytics.trackEvent("OnClick Logo Off")
            }
            bootLogo.setOnClickListener {
                sendCommand("bootlogo")
                Analytics.trackEvent("OnClick Logo On")
            }
            panelInit.setOnClickListener {
                sendCommand("panel_init")
                Analytics.trackEvent("OnClick Panel Init")
            }
            usbstart.setOnClickListener {
                sendCommand("usbstart 0")
                sendCommand("emmcbin 0")
                Analytics.trackEvent("OnClick USB Start")
            }
            restoreBackup.setOnClickListener {
                sendCommand("restore_backup")
                Analytics.trackEvent("OnClick Restore Backup")
            }
            audioPreinit.setOnClickListener {
                sendCommand("audio_preinit")
                sendCommand("bootmusic")
                Analytics.trackEvent("OnClick Sound Tests")
            }
            custar.setOnClickListener {
                sendCommand("custar")
                Analytics.trackEvent("OnClick custar")
            }
            mmcinfo.setOnClickListener {
                sendCommand("mmcinfo")
                Analytics.trackEvent("OnClick MMC Info")
            }
            reset.setOnClickListener {
                sendCommand("reset")
                Analytics.trackEvent("OnClick Reset")
            }


/*
            keyAccessEnter.setOnClickListener {
                try {
                    Timer().scheduleAtFixedRate(object : TimerTask() {
                        var t0 = System.currentTimeMillis()
                        override fun run() {
                            if (System.currentTimeMillis() - t0 > 4 * 1000) {
                                cancel()
                            } else {
                                send(newline)
                            }
                        }
                    }, 0, 100)
                } catch (e: Exception) {
                    Crashes.trackError(e)
                }
                Analytics.trackEvent("OnClick Access Key")
            }
            keyAccessEsc.setOnClickListener {
                try {
                    Timer().scheduleAtFixedRate(
                        object : TimerTask() {
                            var t0 = System.currentTimeMillis()
                            override fun run() {
                                if (System.currentTimeMillis() - t0 > 4 * 1000) {
                                    cancel()
                                } else {
                                    send("\u001B")
                                }
                            }
                        },
                        0, 100,
                    )
                } catch (e: Exception) {
                    Crashes.trackError(e)
                }

                Analytics.trackEvent("OnClick Access Key")
            }
            */
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