package com.voicestudio.phone

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/** M1b: fixed 3 s input, 10 cold + 10 warm conversions, 9-stage timing, CSV. */
class M1BenchActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var runButton: Button
    private lateinit var dumpButton: Button
    private lateinit var modelSpinner: Spinner
    private lateinit var decoderSpinner: Spinner
    private lateinit var threadsSpinner: Spinner
    private var benchUri: Uri? = null
    private var benchLabel: String = "内置 m1_fixture.wav（3.000 s）"

    companion object { private const val PICK_BENCH = 1001 }

    private val modelsDir by lazy { File(getExternalFilesDir(null), "models") }
    private val commonDir by lazy { File(getExternalFilesDir(null), "common") }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        root.addView(TextView(this).apply { text = "M1b 性能基线"; textSize = 22f })
        root.addView(TextView(this).apply {
            text = "固定 3 秒输入，10 冷 + 10 热；结果写入 files/m1_report.csv。\n" +
                "冷启动=每次重建 Rvc；热启动=一次加载后复用。"
            textSize = 12f; setPadding(0, 12, 0, 12)
        })
        root.addView(Button(this).apply {
            text = "选择测试音频（默认内置 3 秒）"
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"),
                    PICK_BENCH)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        modelSpinner = Spinner(this); decoderSpinner = Spinner(this); threadsSpinner = Spinner(this)
        root.addView(label("模型")); root.addView(modelSpinner)
        root.addView(label("解码器")); root.addView(decoderSpinner)
        root.addView(label("线程")); root.addView(threadsSpinner)
        runButton = Button(this).apply { text = "开始测试" }
        root.addView(runButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dumpButton = Button(this).apply { text = "导出音色对比中间特征（CPU）" }
        root.addView(dumpButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        status = TextView(this).apply { textSize = 12f; setPadding(0, 16, 0, 0) }
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })

        val models = modelsDir.listFiles { f -> f.isDirectory && File(f, "model.enc.onnx").isFile }?.map { it.name }?.sorted() ?: emptyList()
        modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
        decoderSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("CPU", "GPU (ncnn)"))
        threadsSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("2", "4", "6", "8"))
        threadsSpinner.setSelection(3)
        runButton.setOnClickListener { run() }
        dumpButton.setOnClickListener { dumpFeatures() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_BENCH || resultCode != RESULT_OK) return
        val uri = data?.data
        if (uri == null) { status.text = "未选择文件"; return }
        val persistent = try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (_: Throwable) {
            false  // 持久授权失败不影响本轮：SAF 的临时读权限在 Activity 生命周期内有效
        }
        benchUri = uri
        benchLabel = uri.toString() + if (persistent) "" else "（仅本轮有效）"
        status.text = "测试音频：$benchLabel"
    }

    private fun label(s: String) = TextView(this).apply { text = s; textSize = 13f; setPadding(0, 10, 0, 4) }

    private fun dumpFeatures() {
        val models = modelsDir.listFiles { f -> f.isDirectory && File(f, "model.enc.onnx").isFile }?.map { it.name }?.sorted() ?: emptyList()
        if (models.isEmpty()) { status.text = "没有模型"; return }
        val modelName = models[modelSpinner.selectedItemPosition.coerceIn(0, models.size - 1)]
        val dir = File(modelsDir, modelName)
        val dumpDir = File(getExternalFilesDir(null), "diag").apply { deleteRecursively(); mkdirs() }
        runButton.isEnabled = false; dumpButton.isEnabled = false
        // 与正式链路一致：pitch 12 / formant 0.4 / 4 线程 / allowFallback = true
        val settings = Rvc.Settings(pitch = 12.0, formant = 0.4, rmsMixRate = 1.0)
        status.text = "导出中间特征：$modelName / CPU / pitch=12 formant=0.4 / allowFallback"
        thread {
            val fixture = File(cacheDir, "m1_fixture.wav")
            try {
                assets.open("m1_fixture.wav").use { i -> fixture.outputStream().use { o -> i.copyTo(o) } }
                val source = benchUri?.let { runCatching { AudioIo.decode(this, it) }.getOrNull() }
                    ?: AudioIo.decode(this, Uri.fromFile(fixture))
                val engine = Rvc(commonDir, dir, 4, "cpu", "model.dec.onnx", "cpu", allowFallback = true, dumpDir = dumpDir)
                val x = source
                val y = engine.convert(x, settings)
                // 实验：1.86-1.97s、3.77-3.88s 强制 f0=0（10ms 帧）
                engine.close()
                val zero = IntArray(12) { 186 + it } + IntArray(12) { 377 + it }
                val engine2 = Rvc(commonDir, dir, 4, "cpu", "model.dec.onnx", "cpu", allowFallback = true, forceZeroF0Frames = zero)
                val y2 = engine2.convert(x, settings)
                AudioIo.writeWav(File(dumpDir, "android_output_f0zero.wav"), y2)
                engine2.close()
                AudioIo.writeWav(File(dumpDir, "android_output.wav"), y)
                engine.close()
                ui.post { status.text = "DONE: ${dumpDir.absolutePath}\n${dumpDir.listFiles()?.joinToString { it.name }}" }
            } catch (e: Throwable) {
                ui.post { status.text = "失败：${e.message ?: e}" }
            } finally {
                ui.post { runButton.isEnabled = true; dumpButton.isEnabled = true }
            }
        }
    }

    private fun tempC(): Double {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
    }

    private fun run() {
        val models = modelsDir.listFiles { f -> f.isDirectory && File(f, "model.enc.onnx").isFile }?.map { it.name }?.sorted() ?: emptyList()
        if (models.isEmpty()) { status.text = "没有模型"; return }
        val modelName = models[modelSpinner.selectedItemPosition.coerceIn(0, models.size - 1)]
        val useGpu = decoderSpinner.selectedItemPosition == 1
        val threads = listOf(2, 4, 6, 8)[threadsSpinner.selectedItemPosition]
        val dir = File(modelsDir, modelName)
        val hasGpu = File(dir, "model.dec.ncnn.param").isFile && File(dir, "model.dec.ncnn.bin").isFile
        val ep = if (useGpu && hasGpu) "ncnn_gpu" else "cpu"
        val dec = "model.dec.onnx"
        runButton.isEnabled = false
        status.text = "准备 $modelName / $ep / $threads 线程"
        thread {
            val fixture = File(cacheDir, "m1_fixture.wav")
            try {
                // 固定本轮输入：开始后不再读 benchUri，避免测试中途变化
                val source: Uri = benchUri ?: run {
                    assets.open("m1_fixture.wav").use { i -> fixture.outputStream().use { o -> i.copyTo(o) } }
                    Uri.fromFile(fixture)
                }
                val inputLabel = if (benchUri != null) benchLabel else "内置 m1_fixture.wav"
                // 预检：外部 WAV 解码失败就直接终止，不写半截 CSV
                val probe = try {
                    AudioIo.decode(this, source)
                } catch (t: Throwable) {
                    android.util.Log.e("VoiceStudio", "bench input decode failed: $inputLabel", t)
                    ui.post { status.text = "解码失败：${t.message ?: t}\n输入：$inputLabel" }
                    return@thread
                }
                val inputSeconds = probe.size.toFloat() / Rvc.SR
                android.util.Log.i("VoiceStudio", String.format(Locale.US,
                    "bench input: %s, %.3f s, %d samples", inputLabel, inputSeconds, probe.size))
                ui.post { status.text = String.format(Locale.US, "准备 %s / %s / %d 线程 · 输入 %.3f s", modelName, ep, threads, inputSeconds) }
                val csv = File(getExternalFilesDir(null), "m1_report.csv")
                if (!csv.exists()) csv.writeText("phase,run,model,decoder,threads,load_ms,decode_ms,hubert_ms,rmvpe_ms,index_ms,enc_ms,dec_ms,post_ms,write_ms,total_ms,decoder_device,fallback,temp_c\n")
                fun record(phase: String, runNo: Int, engine: Rvc, loadMs: Double, decodeMs: Double, writeMs: Double) {
                    val t = engine.timing
                    val total = t.sum()
                    val line = String.format(Locale.US, "%s,%d,%s,%s,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%s,%s,%.1f\n",
                        phase, runNo, modelName, ep, threads, loadMs, decodeMs, t[2]*1000, t[3]*1000, t[4]*1000,
                        t[5]*1000, t[6]*1000, t[7]*1000, writeMs, total*1000,
                        engine.decoderDevice, (engine.fallbackNotice ?: "").replace(',', ';'), tempC())
                    csv.appendText(line)
                    ui.post { status.text = "已完成 $phase #$runNo：total ${"%.2f".format(total)} s，temp ${"%.1f".format(tempC())}°C" }
                }
                // cold: rebuild engine every run
                for (i in 1..10) {
                    ui.post { status.text = "冷启动 $i/10" }
                    val t0 = System.nanoTime()
                    val engine = Rvc(commonDir, dir, threads, ep, dec, "cpu", allowFallback = false)
                    val load = (System.nanoTime() - t0) / 1e9
                    val td0 = System.nanoTime()
                    val x = AudioIo.decode(this, source)
                    val decode = (System.nanoTime() - td0) / 1e9
                    engine.timing.fill(0.0); engine.timing[0] = load; engine.timing[1] = decode
                    val y = engine.convert(x, Rvc.Settings())
                    val tw = System.nanoTime()
                    AudioIo.writeWav(File(cacheDir, "m1_out.wav"), y)
                    val write = (System.nanoTime() - tw) / 1e9
                    engine.timing[8] = write
                    record("cold", i, engine, load * 1000, decode * 1000, write * 1000)
                    engine.close()
                }
                // warm: one engine, 10 runs
                val engine = Rvc(commonDir, dir, threads, ep, dec, "cpu", allowFallback = false)
                for (i in 1..10) {
                    ui.post { status.text = "热启动 $i/10" }
                    val td0 = System.nanoTime()
                    val x = AudioIo.decode(this, source)
                    val decode = (System.nanoTime() - td0) / 1e9
                    engine.timing.fill(0.0); engine.timing[0] = 0.0; engine.timing[1] = decode
                    val y = engine.convert(x, Rvc.Settings())
                    val tw = System.nanoTime()
                    AudioIo.writeWav(File(cacheDir, "m1_out.wav"), y)
                    val write = (System.nanoTime() - tw) / 1e9
                    engine.timing[8] = write
                    record("warm", i, engine, 0.0, decode * 1000, write * 1000)
                }
                engine.close()
                ui.post { status.text = "完成。CSV：${csv.absolutePath}" }
            } catch (e: Throwable) {
                ui.post { status.text = "失败：${e.message ?: e}" }
            } finally {
                ui.post { runButton.isEnabled = true }
            }
        }
    }
}
