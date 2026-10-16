package com.sara.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object SaraImage {
    private var bmp: Bitmap? = null

    @Synchronized
    fun get(ctx: Context): Bitmap {
        bmp?.let { return it }
        val opts = BitmapFactory.Options().apply { inSampleSize = 2; inScaled = false }
        val decoded = BitmapFactory.decodeResource(ctx.resources, R.drawable.sara_avatar, opts)
        bmp = decoded
        return decoded
    }
}

/**
 * Avatar da Sara com animações por estado:
 *  - IDLE: respira devagar
 *  - LISTENING: ondas rosa que acompanham o volume da sua voz
 *  - THINKING: arcos girando (bolha) / pontinhos pulando (tela principal)
 *  - SPEAKING: brilho pulsando e barras de áudio (tela principal)
 * circle = true desenha o rosto recortado em círculo (bolha flutuante).
 */
class SaraAvatarView(context: Context, private val circle: Boolean) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val bmp = SaraImage.get(context)
    private val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = this@SaraAvatarView.shader }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val rect = RectF()
    private val tmp = RectF()
    private val clipPath = Path()
    private val shaderMatrix = Matrix()
    private var corner = 0f
    private var cx = 0f
    private var cy = 0f
    private var smooth = 0f

    private val idleColor = Color.rgb(76, 58, 138)
    private val lilac = Color.rgb(167, 139, 250)
    private val pink = Color.rgb(244, 114, 182)
    private val blue = Color.rgb(96, 165, 250)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        if (circle) {
            val r = min(w, h) / 2f * 0.80f
            rect.set(cx - r, cy - r, cx + r, cy + r)
        } else {
            val inset = dp(14f)
            rect.set(inset, inset, w - inset, h - inset)
            corner = dp(26f)
        }
        clipPath.reset()
        if (circle) clipPath.addOval(rect, Path.Direction.CW) else clipPath.addRoundRect(rect, corner, corner, Path.Direction.CW)

        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val src = if (circle) RectF(bw * 0.12f, 0f, bw * 0.72f, bh * 0.60f) else RectF(0f, 0f, bw, bh)
        val scale = max(rect.width() / src.width(), rect.height() / src.height())
        shaderMatrix.reset()
        shaderMatrix.postTranslate(-src.centerX(), -src.centerY())
        shaderMatrix.postScale(scale, scale)
        shaderMatrix.postTranslate(rect.centerX(), rect.centerY())
        shader.setLocalMatrix(shaderMatrix)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        invalidate()
    }

    private fun drawShape(c: Canvas, p: Paint, grow: Float) {
        tmp.set(rect.left - grow, rect.top - grow, rect.right + grow, rect.bottom + grow)
        if (circle) c.drawOval(tmp, p) else c.drawRoundRect(tmp, corner + grow, corner + grow, p)
    }

    private fun stroke(color: Int, alpha: Int, width: Float) {
        strokePaint.color = color
        strokePaint.alpha = alpha.coerceIn(0, 255)
        strokePaint.strokeWidth = width
    }

    override fun onDraw(c: Canvas) {
        val mode = SaraState.mode
        val t = (SystemClock.uptimeMillis() % 1_000_000L) / 1000f
        smooth += (SaraState.level - smooth) * 0.25f
        val maxGrow = if (circle) dp(7f) else dp(11f)
        var scale = 1f

        when (mode) {
            SaraMode.IDLE -> {
                val b = 0.5f + 0.5f * sin(t * 1.8f)
                scale = 1f + 0.010f * b
                stroke(lilac, (90 + 90 * b).toInt(), dp(2f))
                drawShape(c, strokePaint, dp(3f))
            }
            SaraMode.LISTENING -> {
                scale = 1f + 0.035f * smooth
                for (i in 0..1) {
                    val p = (t * 0.9f + i * 0.5f) % 1f
                    val grow = maxGrow * (0.3f + 0.7f * p) * (0.75f + 0.25f * smooth)
                    stroke(pink, ((1f - p) * 210).toInt(), dp(2.5f))
                    drawShape(c, strokePaint, grow)
                }
                stroke(pink, 230, dp(2.5f))
                drawShape(c, strokePaint, dp(3f))
            }
            SaraMode.THINKING -> {
                val pulse = 0.5f + 0.5f * sin(t * 3f)
                stroke(lilac, (70 + 60 * pulse).toInt(), dp(2f))
                drawShape(c, strokePaint, dp(3f))
                if (circle) {
                    tmp.set(rect.left - dp(4f), rect.top - dp(4f), rect.right + dp(4f), rect.bottom + dp(4f))
                    val a = (t * 220f) % 360f
                    stroke(blue, 255, dp(3.5f))
                    c.drawArc(tmp, a, 90f, false, strokePaint)
                    stroke(lilac, 255, dp(3.5f))
                    c.drawArc(tmp, a + 180f, 90f, false, strokePaint)
                }
            }
            SaraMode.SPEAKING -> {
                val pulse = 0.5f + 0.5f * sin(t * 9f)
                scale = 1f + 0.018f * pulse
                for (k in 3 downTo 1) {
                    stroke(if (k % 2 == 0) blue else lilac, ((60f / k) * (0.5f + pulse * 0.9f)).toInt(), dp(2f) + k * dp(2.0f))
                    drawShape(c, strokePaint, dp(2f))
                }
                stroke(lilac, 240, dp(2.5f))
                drawShape(c, strokePaint, dp(2f))
            }
        }

        c.save()
        c.scale(scale, scale, cx, cy)
        drawShape(c, imagePaint, 0f)
        c.restore()
        stroke(lilac, 170, dp(1.5f))
        drawShape(c, strokePaint, 0f)

        if (!circle && mode != SaraMode.IDLE) drawOverlay(c, mode, t)

        if (mode == SaraMode.IDLE && smooth < 0.01f) postInvalidateDelayed(66) else postInvalidateOnAnimation()
    }

    /** Degradê + barras de áudio (ouvindo/falando) ou pontinhos (pensando) na parte de baixo da foto. */
    private fun drawOverlay(c: Canvas, mode: SaraMode, t: Float) {
        c.save()
        c.clipPath(clipPath)
        val top = rect.bottom - rect.height() * 0.38f
        fillPaint.shader = LinearGradient(0f, top, 0f, rect.bottom, Color.argb(0, 8, 8, 23), Color.argb(190, 8, 8, 23), Shader.TileMode.CLAMP)
        c.drawRect(rect.left, top, rect.right, rect.bottom, fillPaint)
        fillPaint.shader = null
        c.restore()

        val base = rect.bottom - dp(20f)
        val count = 7
        val barW = dp(5f)
        val gap = dp(6f)
        val total = count * barW + (count - 1) * gap
        var x = cx - total / 2f
        for (i in 0 until count) {
            when (mode) {
                SaraMode.SPEAKING, SaraMode.LISTENING -> {
                    val wave = 0.5f + 0.5f * sin(t * (if (mode == SaraMode.SPEAKING) 11f else 13f) + i * 1.1f)
                    val amp = if (mode == SaraMode.SPEAKING) wave else (0.12f + 0.88f * smooth) * wave
                    val h = dp(6f) + dp(24f) * amp
                    fillPaint.color = if (mode == SaraMode.SPEAKING) lilac else pink
                    tmp.set(x, base - h, x + barW, base)
                    c.drawRoundRect(tmp, barW / 2f, barW / 2f, fillPaint)
                }
                else -> {
                    if (i in 2..4) {
                        val bounce = max(0f, sin(t * 6f - (i - 2) * 0.8f))
                        fillPaint.color = Color.WHITE
                        fillPaint.alpha = 220
                        c.drawCircle(x + barW / 2f, base - dp(6f) - dp(8f) * bounce, dp(4f), fillPaint)
                    }
                }
            }
            x += barW + gap
        }
    }
}
