package com.albionplayersradar.ui

import android.app.Activity
import android.app.VpnService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.albionplayersradar.R
import com.albionplayersradar.data.Player
import com.albionplayersradar.data.ZoneInfo
import com.albionplayersradar.data.ZonesDatabase
import com.albionplayersradar.vpn.AlbionVpnService

class MainActivity : AppCompatActivity(), AlbionVpnService.PlayerDisplayCallback {

    private val VPN_REQUEST_CODE = 100

    private var vpnService: AlbionVpnService? = null
    private var isVpnBound = false

    private lateinit var radarView: PlayerRendererView
    private lateinit var statusText: TextView
    private lateinit var zoneText: TextView
    private lateinit var playerCountText: TextView
    private lateinit var vpnButton: View

    private var localX = 0f
    private var localY = 0f
    private var currentZoneId = ""
    private val players = mutableListOf<Player>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? AlbionVpnService.LocalBinder
            vpnService = binder?.getService()
            vpnService?.setPlayerCallback(this@MainActivity)
            updateStatus("Connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        radarView = findViewById(R.id.radarView)
        statusText = findViewById(R.id.statusText)
        zoneText = findViewById(R.id.zoneText)
        playerCountText = findViewById(R.id.playerCountText)
        vpnButton = findViewById(R.id.vpnButton)

        vpnButton.setOnClickListener { toggleVpn() }

        updateStatus("Tap button to start")
    }

    private fun toggleVpn() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            startActivityForResult(prepareIntent, VPN_REQUEST_CODE)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, AlbionVpnService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        updateStatus("Starting VPN...")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            updateStatus("VPN permission denied")
            Toast.makeText(this, "VPN permission required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        if (isVpnBound) {
            unbindService(serviceConnection)
            isVpnBound = false
        }
        super.onDestroy()
    }

    // AlbionVpnService.PlayerDisplayCallback implementation

    override fun onPlayerSpawned(player: Player) {
        runOnUiThread {
            if (players.none { it.id == player.id }) {
                players.add(player)
                updatePlayerCount()
            }
        }
    }

    override fun onPlayerLeft(id: Long) {
        runOnUiThread {
            players.removeAll { it.id == id }
            updatePlayerCount()
        }
    }

    override fun onPlayerMoved(id: Long, x: Float, y: Float) {
        runOnUiThread {
            players.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { index ->
                players[index] = players[index].copy(posX = x, posY = y)
            }
            radarView.updateData(localX, localY, players, currentZoneId)
        }
    }

    override fun onPlayerHealthUpdate(id: Long, health: Int, maxHealth: Int) {
        runOnUiThread {
            players.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { index ->
                players[index] = players[index].copy(currentHealth = health, maxHealth = maxHealth)
            }
        }
    }

    override fun onZoneChanged(zoneId: String, zone: ZoneInfo) {
        runOnUiThread {
            currentZoneId = zoneId
            players.clear()
            zoneText.text = "${zone.name} (${zone.pvpType.uppercase()})"
            updatePlayerCount()
            radarView.setPvPType(zone.pvpType)
            updateStatus("Monitoring")
        }
    }

    override fun onLocalPlayerMoved(x: Float, y: Float, zoneId: String) {
        runOnUiThread {
            localX = x
            localY = y
            radarView.updateData(localX, localY, players, currentZoneId)
        }
    }

    override fun onAlert(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatus(msg: String) {
        runOnUiThread { statusText.text = msg }
    }

    private fun updatePlayerCount() {
        val hostile = players.count { it.isHostile }
        playerCountText.text = "Players: ${players.size} | Hostile: $hostile"
    }
}
