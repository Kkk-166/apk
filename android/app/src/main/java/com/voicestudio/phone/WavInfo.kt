package com.voicestudio.phone

import java.io.File
import java.io.RandomAccessFile

/** App 侧读取 WAV 时长（按 RIFF chunk 遍历，不假设 44 字节头）。 */
object WavInfo {
    fun durationSeconds(file: File): Double? = try {
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
            if (fourCC() != "RIFF") return null
            val riffSize = u32()
            val riffEnd = 8L + riffSize
            if (riffEnd > total) return null
            if (fourCC() != "WAVE") return null
            var channels = 0
            var rate = 0
            var dataSize = 0L
            while (raf.filePointer + 8 <= riffEnd) {
                val id = fourCC()
                val len = u32()
                val body = raf.filePointer
                val next = body + len + (len and 1L)
                if (next > riffEnd) return null
                if (id == "fmt " && len >= 16) {
                    u16()
                    channels = u16()
                    rate = u32().toInt()
                } else if (id == "data") {
                    dataSize = len
                    if (dataSize <= 0L) return null
                    if (channels <= 0 || rate <= 0) return null
                    return dataSize.toDouble() / (rate.toLong() * channels * 2L)
                }
                raf.seek(next)
            }
            null
        }
    } catch (t: Throwable) {
        null
    }
}
