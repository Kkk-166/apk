package com.voicestudio.phone

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 悬浮窗变声的状态机：录音 -> 转换 -> 自动保存（P1）。
 * P2 起在转换完成后接内置 WAV 注入，P3 再接无障碍自动发送。
 */
object SendOrchestrator {
    enum class State { IDLE, RECORDING, CONVERTING, INJECTING, READY, SENDING, DONE, FAILED }

    @Volatile var state = State.IDLE; private set
    @Volatile var message = ""; private set
    @Volatile var recordSeconds = 0; private set
    @Volatile var convertedPath: String? = null; private set
    @Volatile var convertSeconds = 0.0; private set
    @Volatile var publicName: String? = null; private set
    @Volatile var injectDuration = 0.0; private set

    private const val TARGET_PACKAGE = "com.tencent.mobileqq"

    @Volatile private var cancelled = false

    private var recorder: Recorder? = null
    private var startedAt = 0L

    @Synchronized fun startRecording(context: Context) {
        if (state == State.RECORDING || state == State.CONVERTING) return
        cancelled = false
        val app = context.applicationContext
        val r = Recorder(app)
        recorder = r
        state = State.RECORDING
        message = ""
        recordSeconds = 0
        convertedPath = null
        startedAt = SystemClock.elapsedRealtime()
        r.start()
        thread(name = "float-timer") {
            while (state == State.RECORDING) {
                recordSeconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
                Thread.sleep(200)
            }
        }
    }

    @Synchronized fun stopRecording(context: Context) {
        if (state != State.RECORDING) return
        val r = recorder ?: return
        recorder = null
        val x = r.stop()
        val seconds = x.size.toFloat() / Rvc.SR
        if (seconds < 0.3f) { fail("录音太短"); return }
        cancelled = false
        state = State.CONVERTING
        message = "转换中…（点一下可停止）"
        val app = context.applicationContext
        thread(name = "float-convert") { convert(app, x) }
    }

    /** 转换中取消：立即回到空闲；后台转换跑完也会丢弃结果。 */
    fun cancel() {
        cancelled = true
        if (state == State.CONVERTING || state == State.INJECTING) {
            state = State.IDLE
            message = ""
            convertedPath = null
        }
    }

    fun reset() {
        if (state == State.READY || state == State.FAILED || state == State.INJECTING ||
            state == State.SENDING || state == State.DONE) {
            runCatching { ShizukuMicController.stop() }
            state = State.IDLE
            message = ""
            convertedPath = null
            recordSeconds = 0
            convertSeconds = 0.0
            publicName = null
            injectDuration = 0.0
        }
    }

    /** 启动注入并等待 READY；失败明确上报，不误报成功。 */
    private fun startInjection(context: Context, path: String) {
        try {
            ShizukuMicController.startWav(context, TARGET_PACKAGE, path, 60)
            var waited = 0
            while (waited < 20_000 && !ShizukuMicController.shellLog.contains("READY")) {
                if (!ShizukuMicController.active) break
                Thread.sleep(100)
                waited += 100
            }
            if (!ShizukuMicController.active) {
                fail(ShizukuMicController.error ?: "注入未启动")
                return
            }
            if (!ShizukuMicController.shellLog.contains("READY")) {
                fail("注入未就绪：" + ShizukuMicController.status)
                return
            }
            state = State.READY
            message = "注入中 %.1f s，去 QQ 按住说话".format(injectDuration)
        } catch (t: Throwable) {
            android.util.Log.e("SendOrchestrator", "inject failed", t)
            fail("注入失败：" + (t.message ?: t.toString()))
        }
    }

    /** 用户确认后：无障碍长按 QQ 语音键，时长 = 注入 WAV 时长。 */
    fun sendToQq(context: Context) {
        if (state != State.READY) return
        val service = QQAccessibilityService.getInstance()
        if (service == null) {
            fail("无障碍服务未开启：请在系统设置里启用 VoiceStudio 的无障碍")
            return
        }
        state = State.SENDING
        message = "发送中…"
        thread(name = "float-send") {
            try {
                val point = service.findVoiceButton()
                if (point == null) {
                    fail("找不到 QQ 语音按钮：请先打开 QQ 聊天并切到按住说话")
                    return@thread
                }
                val ms = (injectDuration * 1000.0).toLong().coerceIn(1000L, 60_000L)
                val ok = service.longPress(point[0], point[1], ms)
                if (!ok) {
                    fail("长按失败：无障碍手势被拒绝")
                    return@thread
                }
                // 等手势结束（长按时长 + 缓冲）
                Thread.sleep(ms + 500)
                state = State.DONE
                message = "已发送 %.1f s".format(injectDuration)
            } catch (t: Throwable) {
                android.util.Log.e("SendOrchestrator", "send failed", t)
                fail("发送失败：" + (t.message ?: t.toString()))
            }
        }
    }

    /** 写一份到 Music/VoiceStudio，用户可见；失败不影响注入。 */
    private fun savePublic(context: Context, y: FloatArray): String? {
        val name = "float-" + SimpleDateFormat("MMdd_HHmm", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/VoiceStudio")
        }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { AudioIo.writeWav(it, y) }
        return "$name.wav"
    }

    private fun convert(context: Context, x: FloatArray) {
        try {
            val base = context.getExternalFilesDir(null) ?: throw IllegalStateException("没有外部存储目录")
            val commonDir = File(base, "common")
            val modelsDir = File(base, "models")
            val dir = pickModel(context, modelsDir) ?: throw IllegalStateException("没有可用模型")
            val started = System.currentTimeMillis()
            val engine = Rvc(commonDir, dir, 4, "cpu", "model.dec.onnx", "cpu", allowFallback = true)
            val y = try {
                engine.convert(x, settingsOf(dir))
            } finally {
                engine.close()
            }
            convertSeconds = (System.currentTimeMillis() - started) / 1000.0
            if (cancelled) return
            // 1) 公共可见副本（Music/VoiceStudio）
            publicName = runCatching { savePublic(context, y) }.getOrNull()
            // 2) 注入副本：尾部补 300 ms 静音，吸收录音检测延迟
            val pad = (Rvc.SR * 0.3).toInt()
            val inject = FloatArray(y.size + pad)
            System.arraycopy(y, 0, inject, 0, y.size)
            val outDir = File(base, "float").apply { mkdirs() }
            val out = File(outDir, "inject-" + System.currentTimeMillis() + ".wav")
            AudioIo.writeWav(out, inject)
            convertedPath = out.absolutePath
            injectDuration = WavInfo.durationSeconds(out) ?: 0.0
            if (cancelled) return
            // 3) 启动内置 WAV 注入（P2 不自动按住，用户手动切 QQ）
            state = State.INJECTING
            message = "注入准备中…"
            thread(name = "float-inject") { startInjection(context, out.absolutePath) }
        } catch (t: Throwable) {
            android.util.Log.e("SendOrchestrator", "convert failed", t)
            fail("转换失败：" + (t.message ?: t.toString()))
        }
    }

    private fun pickModel(context: Context, modelsDir: File): File? {
        val prefs = context.getSharedPreferences("phone", Context.MODE_PRIVATE)
        val saved = prefs.getString("model", null)
        val dirs = modelsDir.listFiles { f -> f.isDirectory && File(f, "model.enc.onnx").isFile } ?: return null
        return dirs.firstOrNull { it.name == saved } ?: dirs.firstOrNull()
    }

    private fun settingsOf(dir: File): Rvc.Settings {
        val j = File(dir, "settings.json").let {
            if (it.isFile) runCatching { JSONObject(it.readText()) }.getOrDefault(JSONObject()) else JSONObject()
        }
        val d = Rvc.Settings()
        fun num(k: String, def: Double) = if (j.has(k)) j.optDouble(k, def) else def
        return Rvc.Settings(
            pitch = num("pitch", d.pitch), formant = num("formant", d.formant),
            lowRatio = num("low_pitch_ratio", d.lowRatio), lowKnee = num("low_pitch_knee", d.lowKnee),
            highRatio = num("high_pitch_ratio", d.highRatio), highKnee = num("high_pitch_knee", d.highKnee),
            unvoicedKeep = num("unvoiced_keep", 1.0) != 0.0,
            expand = num("intonation_expand", 0.0) != 0.0, expandRatio = num("intonation_ratio", d.expandRatio),
            rmsMixRate = num("rms_mix_rate", d.rmsMixRate))
    }

    private fun fail(reason: String) {
        state = State.FAILED
        message = reason
    }
}
