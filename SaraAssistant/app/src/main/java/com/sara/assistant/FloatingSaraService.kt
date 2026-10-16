package com.sara.assistant

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs

/** Bolha flutuante com o rosto da Sara: arrastável, anima conforme ela escuta/pensa/fala. */
class FloatingSaraService : Service() {
    companion object { const val ACTION_STOP = "com.sara.assistant.STOP" }

    private var windowManager: WindowManager? = null
    private var bubble: SaraAvatarView? = null
    private val channelId = "sara_floating"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(9, buildNotification())
        SaraState.bubbleOn = true
        if (Settings.canDrawOverlays(this)) showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FloatingSaraService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Sara está ativada")
            .setContentText("Toque para abrir. A bolha só ouve quando você pede.")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Desativar", stop)
            .setOngoing(true)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun showBubble() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val dm = resources.displayMetrics
        val size = dp(76)
        val margin = dp(4)
        val prefs = getSharedPreferences("sara", Context.MODE_PRIVATE)
        val view = SaraAvatarView(this, true)
        view.contentDescription = "Abrir Sara"

        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = prefs.getInt("bx", dm.widthPixels - size - margin).coerceIn(0, maxOf(0, dm.widthPixels - size))
        lp.y = prefs.getInt("by", dm.heightPixels / 3).coerceIn(0, maxOf(0, dm.heightPixels - size))

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        lp.x = (startX + dx).toInt().coerceIn(0, maxOf(0, dm.widthPixels - size))
                        lp.y = (startY + dy).toInt().coerceIn(0, maxOf(0, dm.heightPixels - size))
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        val open = Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra("listen", true)
                        startActivity(open)
                    } else {
                        val target = if (lp.x + size / 2 < dm.widthPixels / 2) margin else dm.widthPixels - size - margin
                        ValueAnimator.ofInt(lp.x, target).apply {
                            duration = 180
                            addUpdateListener {
                                lp.x = it.animatedValue as Int
                                try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                            }
                            start()
                        }
                        prefs.edit().putInt("bx", target).putInt("by", lp.y).apply()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(view, lp)
            bubble = view
        } catch (_: Exception) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Sara flutuante", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        try { bubble?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        bubble = null
        windowManager = null
        SaraState.bubbleOn = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
