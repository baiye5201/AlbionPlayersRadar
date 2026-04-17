package com.albionplayersradar.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.albionplayersradar.data.Player
import com.albionplayersradar.data.ThreatLevel
import kotlin.math.min
import kotlin.math.sqrt

class PlayerRendererView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var localX = 0f
    private var localY = 0f
    private var players = listOf<Player>()
    private var pvpType = "safe"
    private var radarScale = 50f
    private var maxDistance = 100f

    private val bgPaint = Paint().apply { color = Color.parseColor("#1a1a2e"); style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.parseColor("#333366") }
    private val localPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 24f; textAlign = Paint.Align.CENTER }
    private val alertPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f; color = Color.RED }

    private val playerPaints = mapOf(
        ThreatLevel.PASSIVE to Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00FF88"); style = Paint.Style.FILL },
        ThreatLevel.FACTION to Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFA500"); style = Paint.Style.FILL },
        ThreatLevel.HOSTILE to Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.FILL }
    )

    private var hasHostile = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        for (ring in listOf(25f, 50f, 75f)) {
            val r = ring * radarScale
            if (r < min(cx, cy)) canvas.drawCircle(cx, cy, r, ringPaint)
        }

        canvas.drawCircle(cx, cy, 8f, localPaint)

        for (player in players) {
            val dx = (player.posX - localX) * radarScale
            val dy = (player.posY - localY) * radarScale
            val px = cx + dx
            val py = cy + dy

            if (px < 0 || px > width || py < 0 || py > height) continue

            val dist = sqrt(dx * dx + dy * dy)
            if (dist > maxDistance * radarScale) continue

            val paint = playerPaints[player.threatLevel] ?: playerPaints[ThreatLevel.PASSIVE]!!
            canvas.drawCircle(px, py, if (player.isHostile) 12f else 8f, paint)

            if (dist < 60f) {
                textPaint.textSize = 20f
                val label = if (player.guildName != null) "${player.name.take(6)} [${player.guildName.take(4)}]" else player.name.take(8)
                canvas.drawText(label, px, py - 10f, textPaint)
            }
        }

        if (hasHostile && pvpType == "black") {
            alertPaint.style = Paint.Style.STROKE
            alertPaint.strokeWidth = 16f
            canvas.drawRect(8f, 8f, width - 8f, height - 8f, alertPaint)
        }
    }

    fun updateData(lx: Float, ly: Float, list: List<Player>, zoneId: String) {
        localX = lx
        localY = ly
        players = list
        hasHostile = list.any { it.isHostile }
        invalidate()
    }

    fun setPvPType(type: String) { pvpType = type; invalidate() }
}
