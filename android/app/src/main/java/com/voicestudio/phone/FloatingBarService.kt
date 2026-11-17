package com.voicestudio.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 长条形悬浮窗：顶部把手可拖动，主体按住录音、松开转换（P1）。
 * P2 起在转换完成后接 WAV 注入，P3 再接无障碍发送。
 */
class FloatingBarService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var bar: View
    private lateinit var label: TextView
    private lateinit var hint: TextView
    private lateinit var hold: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var recording = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragBeganX = 0
    private var dragBeganY = 0

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "悬浮窗变声", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("悬浮窗变声")
            .setContentText("长按录音，松开转换；在 App 设置里可关闭")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        showBar()
        ui.post(refresh)
    }

    private fun showBar() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(0xEE101018.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0x66A78BFA)
            }
        }
        // 拖动把手
        val handle = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0x55A78BFA)
                cornerRadius = dp(3).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8)
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
        hold = TextView(this).apply {
            text = "按住录音"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            background = GradientDrawable().apply {
                setColor(0xCC7C5CD6.toInt())
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        root.addView(handle)
        root.addView(label)
        root.addView(hint)
        root.addView(hold)
        params = WindowManager.LayoutParams(
            dp(220),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(110)
        }
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    dragBeganX = params.x
                    dragBeganY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = dragBeganX + (event.rawX - dragStartX).toInt()
                    params.y = dragBeganY + (event.rawY - dragStartY).toInt()
                    runCatching { wm.updateViewLayout(bar, params) }
                    true
                }
                else -> true
            }
        }
        hold.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val st = SendOrchestrator.state
                    if (st != SendOrchestrator.State.RECORDING && st != SendOrchestrator.State.CONVERTING) {
                        // FAILED / READY 状态下再按一次即可重新开始，不会卡死
                        SendOrchestrator.reset()
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
                SendOrchestrator.State.INJECTING -> "注入准备中…"
                SendOrchestrator.State.READY -> "注入中"
                SendOrchestrator.State.FAILED -> "失败"
            }
            hint.text = when (s.state) {
                SendOrchestrator.State.IDLE -> "松开后自动转换"
                SendOrchestrator.State.RECORDING -> "松开开始转换"
                SendOrchestrator.State.CONVERTING -> "请稍候"
                SendOrchestrator.State.INJECTING -> "正在连接注入服务"
                SendOrchestrator.State.READY -> "去 QQ 按住说话，再按一次可重录"
                SendOrchestrator.State.FAILED -> s.message + "，再按一次重试"
            }
            hold.text = when (s.state) {
                SendOrchestrator.State.RECORDING -> "松开发送录音"
                SendOrchestrator.State.CONVERTING -> "转换中…"
                SendOrchestrator.State.INJECTING -> "注入准备中…"
                SendOrchestrator.State.READY -> "注入中，去 QQ 按住"
                else -> "按住录音"
            }
            ui.postDelayed(this, 250)
        }
    }

    override fun onDestroy() {
        running = false
        ui.removeCallbacksAndMessages(null)
        runCatching { if (bar.isAttachedToWindow) wm.removeView(bar) }
        SendOrchestrator.reset()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    companion object {
        @Volatile var running = false
        private const val CHANNEL = "floating_bar"
        private const val NOTIFICATION_ID = 77
    }
}
