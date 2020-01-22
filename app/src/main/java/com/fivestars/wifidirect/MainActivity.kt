package com.fivestars.wifidirect

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), WifiP2pManager.ChannelListener,
    DeviceListFragment.DeviceActionListener {

    val PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION = 1001

    var manager: WifiP2pManager? = null
    var isWifiP2pEnabled = false
    var retryChannel = false

    val intentFilter = IntentFilter()
    var channel: WifiP2pManager.Channel? = null
    var receiver: BroadcastReceiver? = null

    /**
     * @param isWifiP2pEnabled the isWifiP2pEnabled to set
     */
    fun setIsWifiP2pEnabled(isWifiP2pEnabled: Boolean) {
        this.isWifiP2pEnabled = isWifiP2pEnabled
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION -> if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Log.e(Companion.TAG, "Fine location permission is not granted!")
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        // add necessary intent values to be matched.
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
        channel = manager!!.initialize(this, mainLooper, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION
            )
            // After this point you wait for callback in
// onRequestPermissionsResult(int, String[], int[]) overridden method
        }
    }

    /** register the BroadcastReceiver with the intent values to be matched  */
    override fun onResume() {
        super.onResume()
        receiver = WiFiDirectBroadcastReceiver(manager, channel, this)
        registerReceiver(receiver, intentFilter)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }

    /**
     * Remove all peers and clear all fields. This is called on
     * BroadcastReceiver receiving a state change event.
     */
    fun resetData() {
        val fragmentList = fragmentManager
            .findFragmentById(R.id.frag_list) as DeviceListFragment
        val fragmentDetails = fragmentManager
            .findFragmentById(R.id.frag_detail) as DeviceDetailFragment
        fragmentList?.clearPeers()
        fragmentDetails?.resetViews()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        val inflater: MenuInflater = menuInflater
        inflater.inflate(R.menu.action_items, menu)
        return true
    }

    /*
     * (non-Javadoc)
     * @see android.app.Activity#onOptionsItemSelected(android.view.MenuItem)
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.atn_direct_enable -> {
                if (manager != null && channel != null) { // Since this is the system wireless settings activity, it's
                    // not going to send us a result. We will be notified by
                    // WiFiDeviceBroadcastReceiver instead.
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                } else {
                    Log.e(Companion.TAG, "channel or manager is null")
                }
                true
            }
            R.id.atn_direct_discover -> {
                if (!isWifiP2pEnabled) {
                    Toast.makeText(
                        this@MainActivity, R.string.p2p_off_warning,
                        Toast.LENGTH_SHORT
                    ).show()
                    return true
                }
                val fragment = getFragmentManager()
                    .findFragmentById(R.id.frag_list) as DeviceListFragment
                fragment.onInitiateDiscovery()
                manager!!.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Toast.makeText(
                            this@MainActivity, "Discovery Initiated",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    override fun onFailure(reasonCode: Int) {
                        Toast.makeText(
                            this@MainActivity, "Discovery Failed : $reasonCode",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                })
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun showDetails(device: WifiP2pDevice?) {
        val fragment = getFragmentManager()
            .findFragmentById(R.id.frag_detail) as DeviceDetailFragment
        fragment.showDetails(device)
    }

    override fun connect(config: WifiP2pConfig?) {
        manager!!.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { // WiFiDirectBroadcastReceiver will notify us. Ignore for now.
            }

            override fun onFailure(reason: Int) {
                Toast.makeText(
                    this@MainActivity, "Connect failed. Retry.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }

    override fun disconnect() {
        val fragment = getFragmentManager()
            .findFragmentById(R.id.frag_detail) as DeviceDetailFragment
        fragment.resetViews()
        manager!!.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onFailure(reasonCode: Int) {
                Log.d(Companion.TAG, "Disconnect failed. Reason :$reasonCode")
            }

            override fun onSuccess() {
                fragment.view!!.visibility = View.GONE
            }
        })
    }

    override fun onChannelDisconnected() { // we will try once more
        if (manager != null && !retryChannel) {
            Toast.makeText(this, "Channel lost. Trying again", Toast.LENGTH_LONG).show()
            resetData()
            retryChannel = true
            manager!!.initialize(this, mainLooper, this)
        } else {
            Toast.makeText(
                this,
                "Severe! Channel is probably lost premanently. Try Disable/Re-Enable P2P.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun cancelDisconnect() { /*
         * A cancel abort request by user. Disconnect i.e. removeGroup if
         * already connected. Else, request WifiP2pManager to abort the ongoing
         * request
         */
        if (manager != null) {
            val fragment = fragmentManager
                .findFragmentById(R.id.frag_list) as DeviceListFragment
            if (fragment.device == null
                || fragment.device.status == WifiP2pDevice.CONNECTED
            ) {
                disconnect()
            } else if (fragment.device.status == WifiP2pDevice.AVAILABLE
                || fragment.device.status == WifiP2pDevice.INVITED
            ) {
                manager!!.cancelConnect(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Toast.makeText(
                            this@MainActivity, "Aborting connection",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    override fun onFailure(reasonCode: Int) {
                        Toast.makeText(
                            this@MainActivity,
                            "Connect abort request failed. Reason Code: $reasonCode",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                })
            }
        }
    }

    companion object {
        public const val TAG = "wifidirectdemo"
    }
}