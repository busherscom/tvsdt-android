package com.bushers.tvsdt

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.FragmentManager
import com.microsoft.appcenter.AppCenter
import com.microsoft.appcenter.analytics.Analytics
import com.microsoft.appcenter.crashes.Crashes

class MainActivity : AppCompatActivity(), FragmentManager.OnBackStackChangedListener {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Initialize SDK
        if (!AppCenter.isConfigured()) {
            if (BuildConfig.APPCENTER_APP_SECRET != "") {
                // Use APPCENTER_APP_SECRET environment variable if it exists
                AppCenter.start(
                    application, BuildConfig.APPCENTER_APP_SECRET,
                    Analytics::class.java, Crashes::class.java
                )
            } else {
                // Otherwise use the hardcoded string value here
                AppCenter.start(
                    application, "<APP SECRET HERE>",
                    Analytics::class.java, Crashes::class.java
                )
            }
        }
        if (BuildConfig.DEBUG) {
            AppCenter.setLogLevel(Log.VERBOSE)
            logDeviceInfo()
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportFragmentManager.addOnBackStackChangedListener(this)
        if (savedInstanceState == null) supportFragmentManager.beginTransaction().add(
            R.id.fragment,
            DevicesFragment(),
            "devices"
        ).commit() else onBackStackChanged()
    }

    override fun onBackStackChanged() {
        supportActionBar!!.setDisplayHomeAsUpEnabled(supportFragmentManager.backStackEntryCount > 0)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onNewIntent(intent: Intent) {
        if (intent.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            val terminal = supportFragmentManager.findFragmentByTag("terminal") as TerminalFragment?
            terminal?.status("USB device detected")
        }
        super.onNewIntent(intent)
    }
    private val tag = "MainActivity"
    private fun logDeviceInfo() {
        val properties: MutableMap<String, String> = HashMap()
        properties["BRAND"] = Build.BRAND
        properties["DEVICE"] = Build.DEVICE
        properties["MANUFACTURER"] = Build.MANUFACTURER
        properties["MODEL"] = Build.MODEL
        properties["PRODUCT"] = Build.PRODUCT
        properties["CODENAME"] = Build.VERSION.CODENAME
        properties["RELEASE"] = Build.VERSION.RELEASE
        properties["SDK"] = Build.VERSION.SDK_INT.toString()
        Analytics.trackEvent("Device Info", properties)
        Log.i(tag,"BRAND: " + Build.BRAND)
        Log.i(tag,"DEVICE: " + Build.DEVICE)
        Log.i(tag,"MANUFACTURER: " + Build.MANUFACTURER)
        Log.i(tag,"MODEL: " + Build.MODEL)
        Log.i(tag,"PRODUCT: " + Build.PRODUCT)
        Log.i(tag,"CODENAME: " + Build.VERSION.CODENAME)
        Log.i(tag,"RELEASE: " + Build.VERSION.RELEASE)
        Log.i(tag,"SDK_INT: " + Build.VERSION.SDK_INT)
    }

}