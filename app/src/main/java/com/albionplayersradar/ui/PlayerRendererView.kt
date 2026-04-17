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

    private val bgPaint = Paint().apply { color = Color.parseColor("#1a1a2e"); style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#333366"); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val localPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 20f; textAlign = Paint.Align.CENTER }
    private val hpBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY }
    private val hpFgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN }

    private val passivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00FF88"); style = Paint.Style.FILL }
    private val factionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFA500"); style = Paint.Style.FILL }
    private val hostilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF0000"); style = Paint.Style.FILL }

    private val playerMap = mutableMapOf<Long, Player>()
    private var localX = 0f
    private var localY = 0f
    private var radarScale = 50f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        for (ring in listOf(25f, 50f, 75f)) {
            val radius = ring * radarScale
            if (radius < min(cx, cy)) canvas.drawCircle(cx, cy, radius, ringPaint)
        }

        canvas.drawCircle(cx, cy, 8f, localPaint)

        for (player in playerMap.values) {
            val dx = (player.posX - localX) * radarScale
            val dy = (player.posY - localY) * radarScale
            val px = cx + dx
            val py = cy + dy

            if (px < -50 || px > width + 50 || py < -50 || py > height + 50) continue

            val paint = when {
                player.isHostile -> hostilePaint
                player.isFactionPlayer -> factionPaint
                else -> passivePaint
            }

            val dotSize = if (player.isMounted) 12f else 8f
            canvas.drawCircle(px, py, dotSize, paint)

            val dist = sqrt((player.posX - localX).let { it * it } + (player.posY - localY).let { it * it })
            if (dist < 50f) {
                val label = if (player.guildName.isNullOrEmpty()) player.name else "${player.name} [${player.guildName}]"
                canvas.drawText(label.take(10), px, py + 24f, textPaint)
            }

            if (player.maxHealth > 0) {
                val bw = 40f; val bh = 4f; val hp = player.currentHealth.toFloat() / player.maxHealth
                canvas.drawRect(px - bw / 2, py - 16f, px - bw / 2 + bw, py - 16f + bh, hpBgPaint)
                canvas.drawRect(px - bw / 2, py - 16f, px - bw / 2 + bw * hp, py - 16f + bh, hpFgPaint)
            }
        }
    }

    fun updatePlayer(player: Player) { playerMap[player.id] = player; invalidate() }
    fun removePlayer(id: Long) { playerMap.remove(id); invalidate() }
    fun updateLocal(x: Float, y: Float) { localX = x; localY = y; invalidate() }
    fun setScale(s: Float) { radarScale = s; invalidate() }
}
