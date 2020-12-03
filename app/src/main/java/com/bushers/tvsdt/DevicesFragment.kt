package com.bushers.tvsdt

import android.app.AlertDialog
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.*
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.ListFragment
import com.bushers.tvsdt.CustomProber.customProber
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.*

class DevicesFragment : ListFragment() {
    internal inner class ListItem(var device: UsbDevice, var port: Int, var driver: UsbSerialDriver?)

    private val listItems = ArrayList<ListItem>()
    private var listAdapter: ArrayAdapter<ListItem>? = null
    private var baudRate = 115200
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        listAdapter = object :
                ArrayAdapter<DevicesFragment.ListItem>(activity!!, 0, listItems) {
            override fun getView(position: Int, view: View?, parent: ViewGroup): View {
                var vw = view
                val item = listItems[position]
                if (vw == null) vw = activity!!.layoutInflater.inflate(R.layout.device_list_item, parent, false)
                val text1 = vw!!.findViewById<TextView>(R.id.text1)
                val text2 = vw.findViewById<TextView>(R.id.text2)
                if (item.driver == null) text1.text = "<no driver>" else if (item.driver!!.ports.size == 1) text1.text = item.driver!!.javaClass.simpleName.replace("SerialDriver", "") else text1.text = item.driver!!.javaClass.simpleName.replace("SerialDriver", "") + ", Port " + item.port
                text2.text = String.format(Locale.US, "Vendor %04X, Product %04X", item.device.vendorId, item.device.productId)
                return vw
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        setListAdapter(null)
        val header = activity!!.layoutInflater.inflate(R.layout.device_list_header, null, false)
        listView.addHeaderView(header, null, false)
        setEmptyText(getString(R.string.no_devices_found))
        (listView.emptyView as TextView).textSize = 18f
        setListAdapter(listAdapter)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_devices, menu)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        return if (id == R.id.refresh) {
            refresh()
            true
        } else if (id == R.id.baud_rate) {
            val baudRates = resources.getStringArray(R.array.baud_rates)
            val pos = listOf(*baudRates).indexOf(baudRate.toString())
            val builder = AlertDialog.Builder(activity)
            builder.setTitle(getString(R.string.baud_rate))
            builder.setSingleChoiceItems(baudRates, pos) { dialog, itm ->
                baudRate = Integer.valueOf(baudRates[itm])
                dialog.dismiss()
            }
            builder.create().show()
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    fun refresh() {
        val usbManager = activity!!.getSystemService(Context.USB_SERVICE) as UsbManager
        val usbDefaultProber = UsbSerialProber.getDefaultProber()
        val usbCustomProber = customProber
        listItems.clear()
        for (device in usbManager.deviceList.values) {
            var driver = usbDefaultProber.probeDevice(device)
            if (driver == null) {
                driver = usbCustomProber.probeDevice(device)
            }
            if (driver != null) {
                for (port in driver.ports.indices) listItems.add(ListItem(device, port, driver))
            } else {
                listItems.add(ListItem(device, 0, null))
            }
        }
        listAdapter!!.notifyDataSetChanged()
    }

    override fun onListItemClick(l: ListView, v: View, position: Int, id: Long) {
        val item = listItems[position - 1]
        if (item.driver == null) {
            Toast.makeText(activity, "no driver", Toast.LENGTH_SHORT).show()
        } else {
            val args = Bundle()
            args.putInt("device", item.device.deviceId)
            args.putInt("port", item.port)
            args.putInt("baud", baudRate)
            val fragment: Fragment = TerminalFragment()
            fragment.arguments = args
            fragmentManager!!.beginTransaction().replace(R.id.fragment, fragment, "terminal").addToBackStack(null).commit()
        }
    }
}