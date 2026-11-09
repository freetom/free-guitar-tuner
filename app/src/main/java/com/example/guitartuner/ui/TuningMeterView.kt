package com.example.guitartuner.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/**
 * A simple analog-style tuning meter.
 * - Center needle = in tune (within ±5 cents green zone)
 * - Left = flat (blue), Right = sharp (red)
 * - Range: ±50 cents displayed
 */
class TuningMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var cents: Float = 0f
    private var idle: Boolean = true

    // Animated display cents (smoothed)
    // The ViewModel already smooths the pitch reading; this is just for visual
    // needle animation — slow enough to be readable, fast enough to feel live.
    private var displayCents: Float = 0f
    private val smoothingFactor = 0.15f  // lower = slower/smoother needle

    private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E1E2E")
    }
    private val paintTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#333355")
    }
    private val paintGreen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#4CAF50")
    }
    private val paintFlat = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#2196F3")
    }
    private val paintSharp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#F44336")
    }
    private val paintNeedle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val paintTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555577")
        strokeWidth = 2f
    }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAAACC")
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private val rectF = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Background
        canvas.drawRect(0f, 0f, w, h, paintBg)

        val cx = w / 2f
        val cy = h * 0.85f
        val radius = minOf(w, h) * 0.72f

        rectF.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Arc spans 180° (from 180° to 360°, i.e. lower semicircle)
        val startAngle = 180f
        val sweepAngle = 180f

        // Draw background track
        canvas.drawArc(rectF, startAngle, sweepAngle, false, paintTrack)

        if (!idle) {
            // Smooth animation
            displayCents += (cents - displayCents) * smoothingFactor

            // Draw active arc from center to current position
            val centerAngle = startAngle + sweepAngle / 2f  // 270° = bottom
            val maxCents = 50f
            val normalized = (displayCents / maxCents).coerceIn(-1f, 1f)
            val sweepToCurrent = (normalized * (sweepAngle / 2f))

            val activePaint = when {
                abs(displayCents) <= 5f -> paintGreen
                displayCents < 0f -> paintFlat
                else -> paintSharp
            }

            if (sweepToCurrent < 0f) {
                canvas.drawArc(rectF, centerAngle + sweepToCurrent, -sweepToCurrent, false, activePaint)
            } else {
                canvas.drawArc(rectF, centerAngle, sweepToCurrent, false, activePaint)
            }

            // Needle
            val needleAngle = Math.toRadians((centerAngle + sweepToCurrent).toDouble())
            val nx = (cx + radius * Math.cos(needleAngle)).toFloat()
            val ny = (cy + radius * Math.sin(needleAngle)).toFloat()
            paintNeedle.color = activePaint.color
            canvas.drawLine(cx, cy, nx, ny, paintNeedle)

            // Center dot
            paintNeedle.color = Color.WHITE
            canvas.drawCircle(cx, cy, 10f, paintNeedle)

            // Keep animating if not settled
            if (abs(displayCents - cents) > 0.5f) invalidate()
        } else {
            displayCents = 0f
            // Just draw center dot
            canvas.drawCircle(cx, cy, 10f, paintNeedle)
        }

        // Tick marks at ±10, ±20, ±30, ±40, ±50
        val maxCents = 50f
        for (tick in listOf(-50, -40, -30, -20, -10, 0, 10, 20, 30, 40, 50)) {
            val normalized = tick.toFloat() / maxCents
            val angleRad = Math.toRadians((270f + normalized * 90f).toDouble())
            val innerR = radius - 25f
            val outerR = radius + 5f
            val tx1 = (cx + innerR * Math.cos(angleRad)).toFloat()
            val ty1 = (cy + innerR * Math.sin(angleRad)).toFloat()
            val tx2 = (cx + outerR * Math.cos(angleRad)).toFloat()
            val ty2 = (cy + outerR * Math.sin(angleRad)).toFloat()
            canvas.drawLine(tx1, ty1, tx2, ty2, paintTick)
        }

        // Labels
        canvas.drawText("♭ Flat", cx - radius * 0.7f, cy - radius * 0.1f, paintLabel)
        canvas.drawText("Sharp ♯", cx + radius * 0.7f, cy - radius * 0.1f, paintLabel)
    }

    fun setCents(c: Float) {
        cents = c.coerceIn(-50f, 50f)
        invalidate()
    }

    fun setIdle(isIdle: Boolean) {
        idle = isIdle
        if (isIdle) displayCents = 0f
        invalidate()
    }
}
