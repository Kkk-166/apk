package com.voicestudio.phone

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * 悬浮窗变声的状态机：录音 -> 转换 -> 自动保存（P1）。
 * P2 起在转换完成后接内置 WAV 注入，P3 再接无障碍自动发送。
 */
object SendOrchestrator {
    enum class State { IDLE, RECORDING, CONVERTING, READY, FAILED }

    @Volatile var state = State.IDLE; private set
    @Volatile var message = ""; private set
    @Volatile var recordSeconds = 0; private set
    @Volatile var convertedPath: String? = null; private set
    @Volatile var convertSeconds = 0.0; private set

    private var recorder: Recorder? = null
    private var startedAt = 0L

    @Synchronized fun startRecording(context: Context) {
        if (state == State.RECORDING || state == State.CONVERTING) return
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
        state = State.CONVERTING
        message = "转换中…"
        val app = context.applicationContext
        thread(name = "float-convert") { convert(app, x) }
    }

    fun reset() {
        if (state == State.READY || state == State.FAILED) {
            state = State.IDLE
            message = ""
            convertedPath = null
            recordSeconds = 0
            convertSeconds = 0.0
        }
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
            val outDir = File(base, "float").apply { mkdirs() }
            val out = File(outDir, "float-" + System.currentTimeMillis() + ".wav")
            AudioIo.writeWav(out, y)
            convertedPath = out.absolutePath
            state = State.READY
            message = "转换完成 %.1f s".format(convertSeconds)
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
