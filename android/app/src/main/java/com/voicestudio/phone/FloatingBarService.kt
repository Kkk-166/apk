package com.voicestudio.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 长条形悬浮窗：按住录音、松开转换（P1）。
 * P2 起在转换完成后接 WAV 注入，P3 再接无障碍发送。
 */
class FloatingBarService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var bar: View
    private lateinit var label: TextView
    private lateinit var hint: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var recording = false

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "悬浮窗变声", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("悬浮窗变声")
            .setContentText("长按录音，松开转换")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        showBar()
        ui.post(refresh)
    }

    private fun showBar() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(0xEE101018.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0x66A78BFA)
            }
        }
        label = TextView(this).apply {
            text = "按住说话"
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        hint = TextView(this).apply {
            text = "松开后自动转换"
            setTextColor(0xFFB9A6E8.toInt())
            textSize = 11f
        }
        root.addView(label)
        root.addView(hint)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(110)
        }
        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!recording && SendOrchestrator.state == SendOrchestrator.State.IDLE) {
                        recording = true
                        SendOrchestrator.startRecording(this@FloatingBarService)
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (recording) {
                        recording = false
                        SendOrchestrator.stopRecording(this@FloatingBarService)
                        true
                    } else false
                }
                else -> false
            }
        }
        bar = root
        wm.addView(bar, params)
    }

    private val refresh = object : Runnable {
        override fun run() {
            val s = SendOrchestrator
            label.text = when (s.state) {
                SendOrchestrator.State.IDLE -> "按住说话"
                SendOrchestrator.State.RECORDING -> "● 录音中 " + s.recordSeconds + " s"
                SendOrchestrator.State.CONVERTING -> "转换中…"
                SendOrchestrator.State.READY -> "转换完成"
                SendOrchestrator.State.FAILED -> "失败"
            }
            hint.text = when (s.state) {
                SendOrchestrator.State.IDLE -> "松开后自动转换"
                SendOrchestrator.State.RECORDING -> "松开开始转换"
                SendOrchestrator.State.CONVERTING -> "请稍候"
                SendOrchestrator.State.READY -> s.convertedPath?.substringAfterLast('/') ?: "已保存"
                SendOrchestrator.State.FAILED -> s.message
            }
            ui.postDelayed(this, 250)
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        runCatching { if (bar.isAttachedToWindow) wm.removeView(bar) }
        SendOrchestrator.reset()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL = "floating_bar"
        private const val NOTIFICATION_ID = 77
    }
}
