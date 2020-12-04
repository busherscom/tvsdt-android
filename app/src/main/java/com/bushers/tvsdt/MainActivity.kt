package com.bushers.tvsdt

import android.content.Intent
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
                AppCenter.start(application, BuildConfig.APPCENTER_APP_SECRET,
                    Analytics::class.java, Crashes::class.java)
            } else {
                // Otherwise use the hardcoded string value here
                AppCenter.start(application, "<APP SECRET HERE>",
                    Analytics::class.java, Crashes::class.java)
            }
        }
        if (BuildConfig.DEBUG) {
            AppCenter.setLogLevel(Log.VERBOSE)
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
}