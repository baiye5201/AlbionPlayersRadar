package com.albionplayersradar.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.albionplayersradar.R
import com.albionplayersradar.data.Player
import com.albionplayersradar.vpn.AlbionVpnService

class MainActivity : AppCompatActivity() {

    private var vpnService: AlbionVpnService? = null
    private var vpnBound = false

    private lateinit var tvStatus: TextView
    private lateinit var btnVpn: Button
    private lateinit var tvZone: TextView
    private lateinit var tvCount: TextView
    private lateinit var radarView: PlayerRendererView
    private lateinit var tvLog: TextView

    private val players = mutableListOf<Player>()
    private var localX = 0f
    private var localY = 0f
    private var currentZone = ""

    private val vpnConnection = object : ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: IBinder?) {
            val b = binder as AlbionVpnService.LocalBinder
            vpnService = b.getService()
            vpnBound = true
            setupVpnCallbacks()
            updateStatus("Radar active")
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            vpnService = null
            vpnBound = false
        }
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpn()
        } else {
            Toast.makeText(this, "VPN permission required", Toast.LENGTH_SHORT).show()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PLAYER_JOINED -> {
                    val id = intent.getLongExtra("id", -1)
                    val name = intent.getStringExtra("name") ?: return
                    val guild = intent.getStringExtra("guild") ?: ""
                    val posX = intent.getFloatExtra("posX", 0f)
                    val posY = intent.getFloatExtra("posY", 0f)
                    val faction = intent.getIntExtra("faction", 0)
                    addLog("Player joined: $name [$guild]")
                }
                ACTION_PLAYER_LEFT -> {
                    val id = intent.getLongExtra("id", -1)
                    addLog("Player left: $id")
                }
                ACTION_PLAYER_MOVE -> {
                    val id = intent.getLongExtra("id", -1)
                    val posX = intent.getFloatExtra("posX", 0f)
                    val posY = intent.getFloatExtra("posY", 0f)
                    // Update player position
                }
                ACTION_ZONE -> {
                    currentZone = intent.getStringExtra("zoneId") ?: ""
                    tvZone.text = "Zone: $currentZone"
                    players.clear()
                    addLog("Zone: $currentZone")
                }
                ACTION_LOCAL_MOVE -> {
                    localX = intent.getFloatExtra("posX", 0f)
                    localY = intent.getFloatExtra("posY", 0f)
                    radarView.updateData(localX, localY, players, currentZone)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tv_status)
        btnVpn = findViewById(R.id.btn_vpn)
        tvZone = findViewById(R.id.tv_zone)
        tvCount = findViewById(R.id.tv_count)
        radarView = findViewById(R.id.radar_view)
        tvLog = findViewById(R.id.tv_log)

        btnVpn.setOnClickListener { toggleVpn() }

        val filter = IntentFilter().apply {
            addAction(ACTION_PLAYER_JOINED)
            addAction(ACTION_PLAYER_LEFT)
            addAction(ACTION_PLAYER_MOVE)
            addAction(ACTION_ZONE)
            addAction(ACTION_LOCAL_MOVE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun toggleVpn() {
        if (vpnService != null) {
            stopVpn()
        } else {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnPermission.launch(intent)
            } else {
                startVpn()
            }
        }
    }

    private fun startVpn() {
        val intent = Intent(this, AlbionVpnService::class.java)
        startForegroundService(intent)
        bindService(intent, vpnConnection, Context.BIND_AUTO_CREATE)
        updateStatus("Starting...")
    }

    private fun stopVpn() {
        if (vpnBound) {
            unbindService(vpnConnection)
            vpnBound = false
        }
        stopService(Intent(this, AlbionVpnService::class.java))
        vpnService = null
        updateStatus("Tap START to activate")
    }

    private fun setupVpnCallbacks() {
        AlbionVpnService.onUpdate = { msg ->
            runOnUiThread {
                when {
                    msg.startsWith("JOIN:") -> {
                        val parts = msg.split("|")
                        if (parts.size >= 4) {
                            val name = parts[1]
                            val guild = parts[2]
                            addLog("→ $name [$guild]")
                        }
                    }
                    msg.startsWith("ZONE:") -> {
                        val zone = msg.substringAfter("ZONE:")
                        currentZone = zone
                        tvZone.text = "Zone: $zone"
                        players.clear()
                    }
                    msg.startsWith("LOCAL:") -> {
                        val coords = msg.substringAfter("LOCAL:").split("|")
                        if (coords.size >= 2) {
                            localX = coords[0].toFloatOrNull() ?: 0f
                            localY = coords[1].toFloatOrNull() ?: 0f
                            radarView.updateData(localX, localY, players, currentZone)
                        }
                    }
                    msg.startsWith("MOVE:") -> {
                        val parts = msg.substringAfter("MOVE:").split("|")
                        if (parts.size >= 3) {
                            val id = parts[0].toLongOrNull() ?: return@runOnUiThread
                            val posX = parts[1].toFloatOrNull() ?: 0f
                            val posY = parts[2].toFloatOrNull() ?: 0f
                            val p = players.find { it.id == id }
                            if (p != null) {
                                tvCount.text = "Players: ${players.size}"
                                radarView.updateData(localX, localY, players, currentZone)
                            }
                        }
                    }
                    msg.startsWith("LEAVE:") -> {
                        val id = msg.substringAfter("LEAVE:").toLongOrNull()
                        if (id != null) {
                            players.removeAll { it.id == id }
                            tvCount.text = "Players: ${players.size}"
                        }
                    }
                }
            }
        }
    }

    private fun updateStatus(text: String) {
        tvStatus.text = text
        btnVpn.text = if (vpnService != null) "STOP RADAR" else "START RADAR"
    }

    private fun addLog(msg: String) {
        tvLog.text = "$msg\n${tvLog.text}".lines().take(20).joinToString("\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (vpnBound) {
            unbindService(vpnConnection)
            vpnBound = false
        }
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {}
    }

    companion object {
        const val ACTION_PLAYER_JOINED = "com.albionplayersradar.ACTION_JOIN"
        const val ACTION_PLAYER_LEFT = "com.albionplayersradar.ACTION_LEAVE"
        const val ACTION_PLAYER_MOVE = "com.albionplayersradar.ACTION_MOVE"
        const val ACTION_ZONE = "com.albionplayersradar.ACTION_ZONE"
        const val ACTION_LOCAL_MOVE = "com.albionplayersradar.ACTION_LOCAL"

        fun broadcastPlayerJoined(id: Long, name: String, guild: String, posX: Float, posY: Float, faction: Int) {
            // Stub — not needed since we're using direct callback
        }
        fun broadcastPlayerLeave(id: Long) {}
        fun broadcastPlayerMove(id: Long, posX: Float, posY: Float) {}
        fun broadcastZone(zoneId: String) {}
    }
}
