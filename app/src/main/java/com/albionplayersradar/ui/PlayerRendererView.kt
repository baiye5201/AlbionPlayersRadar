package com.albionplayersradar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.albionplayersradar.data.Player
import kotlin.math.sqrt

class PlayerRendererView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var players = listOf<Player>()
    private var localX = 0f
    private var localY = 0f
    private var currentZone = ""
    var scale = 3.5f
        set(value) { field = value; invalidate() }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#CC1a1a2e")
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    private val localPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val passivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF88")
        style = Paint.Style.FILL
    }
    private val factionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFA500")
        style = Paint.Style.FILL
    }
    private val hostilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3333")
        style = Paint.Style.FILL
    }
    private val hostileStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF0000")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
    }
    private val healthBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66000000")
        style = Paint.Style.FILL
    }
    private val healthFgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radarR = minOf(cx, cy) * 0.95f

        // Background circle
        canvas.drawCircle(cx, cy, radarR, bgPaint)

        // Range rings at 30, 60, 90 world units
        for (r in listOf(30f, 60f, 90f)) {
            val screenR = r * scale
            if (screenR < radarR) canvas.drawCircle(cx, cy, screenR, ringPaint)
        }

        // Players
        for (p in players) {
            val dx = (p.posX - localX) * scale
            val dy = -(p.posY - localY) * scale  // Y flipped
            val px = cx + dx
            val py = cy + dy

            val dist = sqrt(dx * dx + dy * dy)
            if (dist > radarR + 10) continue

            val dotPaint = when {
                p.isHostile -> hostilePaint
                p.isFactionPlayer -> factionPaint
                else -> passivePaint
            }
            val dotR = if (p.isHostile) 14f else 10f

            canvas.drawCircle(px, py, dotR, dotPaint)
            if (p.isHostile) canvas.drawCircle(px, py, dotR, hostileStrokePaint)

            // Health bar
            if (p.maxHealth > 0) {
                val barW = dotR * 2.5f
                val barH = 4f
                val barL = px - barW / 2
                val barT = py + dotR + 3f
                canvas.drawRect(barL, barT, barL + barW, barT + barH, healthBgPaint)
                val hpFraction = p.healthPercent.coerceIn(0f, 1f)
                healthFgPaint.color = when {
                    hpFraction > 0.6f -> Color.parseColor("#00FF88")
                    hpFraction > 0.3f -> Color.parseColor("#FFD700")
                    else -> Color.parseColor("#FF4444")
                }
                canvas.drawRect(barL, barT, barL + barW * hpFraction, barT + barH, healthFgPaint)
            }

            // Name label (only when close enough)
            if (dist < 80f * scale) {
                val label = buildString {
                    if (!p.guildName.isNullOrEmpty()) append("[${p.guildName.take(6)}] ")
                    append(p.name.take(12))
                    if (p.isMounted) append(" 🐴")
                }
                namePaint.color = when {
                    p.isHostile -> Color.parseColor("#FF8080")
                    p.isFactionPlayer -> Color.parseColor("#FFD080")
                    else -> Color.parseColor("#80FF80")
                }
                canvas.drawText(label, px, py - dotR - 6f, namePaint)
            }
        }

        // Crosshair for local player
        val ch = 12f
        canvas.drawLine(cx - ch, cy, cx + ch, cy, crosshairPaint)
        canvas.drawLine(cx, cy - ch, cx, cy + ch, crosshairPaint)
        canvas.drawCircle(cx, cy, 4f, localPaint)
    }

    fun updateData(locX: Float, locY: Float, playerList: List<Player>, zone: String) {
        localX = locX
        localY = locY
        players = playerList
        currentZone = zone
        invalidate()
    }
}
