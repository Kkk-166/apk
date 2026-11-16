package com.voicestudio.phone

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Process
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs as Shizuku's shell UID. Only launches our bundled, fixed microphone entry point. */
class VirtualMicUserService(private val context: Context) : IVirtualMicService.Stub() {
    private val appUid = context.applicationInfo.uid
    private val lines = ArrayDeque<String>()
    @Volatile private var child: java.lang.Process? = null
    @Volatile private var logReader: Thread? = null
    @Volatile private var port = 0
    private var token = ""
    private var owner: IBinder? = null
    private val died = IBinder.DeathRecipient { stopChild() }

    private fun checkCaller() {
        check(Binder.getCallingUid() == appUid) { "Only Voice Studio may control this service" }
    }

    override fun startMic(targetPackage: String, seconds: Int, otherProfile: Boolean, allApps: Boolean, cellular: Boolean, owner: IBinder): String {
        checkCaller()
        check(Process.myUid() == 2000) { "Start Shizuku with wireless debugging (shell UID 2000)" }
        require(targetPackage.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")))
        require(targetPackage != context.packageName && (seconds == 0 || seconds in 5..300))
        require(!allApps || (!otherProfile && seconds == 0))
        require(!cellular || (!allApps && !otherProfile && seconds == 0))
        synchronized(this) {
            check(child?.isAlive != true) { "Microphone session already running" }
            port = 0
            synchronized(lines) { lines.clear() }
            token = "virtual-mic-${UUID.randomUUID()}"
            this.owner = owner
            owner.linkToDeath(died, 0)
            val command = if (cellular) mutableListOf("/system/bin/app_process", "/", "dev.virtualmic.CellularMain", token)
                else mutableListOf("/system/bin/app_process", "/", "dev.virtualmic.Main",
                "--package", targetPackage, "--mic", "--seconds", seconds.toString(), "--bridge", token,
                "--watch-parent")
            if (otherProfile) command += "--other-profile"
            if (allApps) command += "--all-apps"
            val builder = ProcessBuilder(command).redirectErrorStream(true)
            builder.environment()["CLASSPATH"] = context.applicationInfo.sourceDir
            try {
                val process = builder.start()
                child = process
                logReader = thread(name = "virtual-mic-shell-log") {
                    process.inputStream.bufferedReader().useLines { stream ->
                        stream.forEach { line ->
                            synchronized(lines) { lines.addLast(line); while (lines.size > 700) lines.removeFirst() }
                            Regex("BRIDGE_LISTENING port=(\\d+)").find(line)?.let { port = it.groupValues[1].toInt() }
                        }
                    }
                }
            } catch (e: Throwable) {
                owner.unlinkToDeath(died, 0); this.owner = null
                throw e
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (port == 0 && child?.isAlive == true && System.nanoTime() < deadline) Thread.sleep(50)
        if (port == 0) {
            val detail = synchronized(lines) {
                lines.lastOrNull { "FAILED stage=" in it }?.substringAfter("message=")
                    ?: "Microphone setup failed. Check Shizuku and the destination app."
            }
            stopChild()
            return JSONObject().put("error", detail).toString()
        }
        return snapshot()
    }

    /** WAV 文件注入：复用 dev.virtualmic.Main --wav，不走实时 socket。 */
    override fun startWav(targetPackage: String, wavPath: String, seconds: Int, owner: IBinder): String {
        checkCaller()
        check(Process.myUid() == 2000) { "Start Shizuku with wireless debugging (shell UID 2000)" }
        require(targetPackage.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")))
        require(seconds in 5..300)
        val problem = validateWav(wavPath)
        if (problem != null) {
            return JSONObject().put("error", "WAV_INVALID: " + problem).toString()
        }
        synchronized(this) {
            check(child?.isAlive != true) { "Microphone session already running" }
            port = 0
            synchronized(lines) { lines.clear() }
            token = "virtual-mic-wav-" + UUID.randomUUID()
            this.owner = owner
            owner.linkToDeath(died, 0)
            val command = mutableListOf(
                "/system/bin/app_process", "/", "dev.virtualmic.Main",
                "--package", targetPackage, "--wav", wavPath,
                "--seconds", seconds.toString(), "--watch-parent"
            )
            val builder = ProcessBuilder(command).redirectErrorStream(true)
            builder.environment()["CLASSPATH"] = context.applicationInfo.sourceDir
            try {
                val process = builder.start()
                child = process
                logReader = thread(name = "virtual-mic-wav-log") {
                    process.inputStream.bufferedReader().useLines { stream ->
                        stream.forEach { line ->
                            synchronized(lines) { lines.addLast(line); while (lines.size > 700) lines.removeFirst() }
                        }
                    }
                }
            } catch (e: Throwable) {
                owner.unlinkToDeath(died, 0); this.owner = null
                throw e
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (child?.isAlive == true && System.nanoTime() < deadline) {
                val ready = synchronized(lines) { lines.any { it.contains("READY") } }
                if (ready) break
                Thread.sleep(50)
            }
            return snapshot()
        }
    }

    /** 按 shell 身份（本进程 uid=2000）实际打开文件，按 RIFF chunk 校验，不假设 44 字节头。 */
    private fun validateWav(path: String): String? {
        if (!Regex("^/[A-Za-z0-9_./-]+$").matches(path)) return "bad path"
        val file = File(path)
        if (!file.isFile) return "not found"
        if (!file.canRead()) return "not readable"
        if (file.length() < 44L || file.length() > 100L * 1024 * 1024) return "bad size"
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val total = raf.length()
                fun fourCC(): String {
                    val b = ByteArray(4); raf.readFully(b); return String(b, Charsets.US_ASCII)
                }
                fun u16(): Int {
                    val b = ByteArray(2); raf.readFully(b)
                    return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
                }
                fun u32(): Long {
                    val b = ByteArray(4); raf.readFully(b)
                    return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
                        ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
                }
                if (fourCC() != "RIFF") return "not RIFF"
                val riffSize = u32()
                val riffEnd = 8L + riffSize
                if (riffEnd > total) return "truncated RIFF"
                if (fourCC() != "WAVE") return "not WAVE"
                var fmtOk = false
                var channels = 0
                var rate = 0
                var align = 0
                var dataStart = -1L
                var dataSize = 0L
                while (raf.filePointer + 8 <= riffEnd) {
                    val id = fourCC()
                    val len = u32()
                    val body = raf.filePointer
                    val next = body + len + (len and 1L)
                    if (next > riffEnd) return "truncated chunk " + id
                    if (id == "fmt ") {
                        if (fmtOk || len < 16) return "bad fmt"
                        val encoding = u16()
                        channels = u16()
                        rate = u32().toInt()
                        u32()
                        align = u16()
                        val bits = u16()
                        if (encoding != 1 || bits != 16 || (channels != 1 && channels != 2) ||
                            rate < 8000 || rate > 48000 || align != channels * 2) {
                            return "unsupported fmt (PCM16 mono/stereo 8-48 kHz)"
                        }
                        fmtOk = true
                    } else if (id == "data") {
                        if (dataStart >= 0) return "multiple data chunks"
                        dataStart = body
                        dataSize = len
                        if (body + len > total) return "data beyond EOF"
                        if (len == 0L) return "empty data"
                        if (channels > 0 && len % (channels * 2L) != 0L) return "unaligned data"
                    }
                    raf.seek(next)
                }
                if (!fmtOk || dataStart < 0) return "missing fmt/data"
                if (channels <= 0) return "bad channels"
                val bytesPerSecond = rate.toLong() * channels * 2L
                val seconds = dataSize.toDouble() / bytesPerSecond
                if (seconds < 0.5 || seconds > 300.0) {
                    return "bad duration " + String.format(java.util.Locale.US, "%.2f", seconds) + " s"
                }
                null
            }
        } catch (t: Throwable) {
            "read failed: " + t.message
        }
    }

    override fun snapshot(): String {
        checkCaller()
        return JSONObject().put("uid", Process.myUid()).put("running", child?.isAlive == true)
            .put("exitCode", child?.let { runCatching { it.exitValue() }.getOrNull() })
            .put("port", port).put("token", token)
            .put("log", synchronized(lines) { lines.joinToString("\n") }).toString()
    }

    override fun stopMic(explicitStop: Boolean) { checkCaller(); stopChild(explicitStop) }

    @Synchronized private fun stopChild(explicitStop: Boolean = false) {
        val process = child
        // EOF means failure. Only an explicit user action may restore voice during a call.
        if (explicitStop) runCatching { process?.outputStream?.write(2); process?.outputStream?.flush() }
        runCatching { process?.outputStream?.close() }
        if (process != null && !process.waitFor(4, TimeUnit.SECONDS)) {
            process.destroy()
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        logReader?.join(1000)
        owner?.let { runCatching { it.unlinkToDeath(died, 0) } }
        owner = null
    }

    override fun destroy() { stopChild(); kotlin.system.exitProcess(0) }
}
