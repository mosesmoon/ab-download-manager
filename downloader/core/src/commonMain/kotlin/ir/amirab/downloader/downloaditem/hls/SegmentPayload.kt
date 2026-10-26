package ir.amirab.downloader.downloaditem.hls

import java.io.File
import java.io.InputStream

/**
 * Finds where the real media data of an HLS segment starts.
 *
 * Some hosts disguise their segments as images (`seg-1.jpeg`, `seg-1.png`, ...) by putting a small
 * fake image header in front of the actual MPEG-TS / fMP4 data so that CDNs and filters treat them
 * as pictures. Players such as hls.js / ffmpeg scan for the TS sync byte and play them anyway, but a
 * plain concatenation would leave those image headers in the middle of the output file.
 *
 * Detection:
 * - MPEG-TS: `0x47` sync byte repeating every 188 bytes.
 * - fMP4 / CMAF: an ISO-BMFF box header (`size` + known 4cc such as `ftyp`, `moof`, `styp`).
 * If the data already starts with one of those (the normal case) or nothing is recognized,
 * the offset is 0 and the file is copied unchanged.
 */
object SegmentPayload {
    private const val TS_PACKET_SIZE = 188
    private const val TS_SYNC: Byte = 0x47

    /** how far into a segment we look for the payload */
    private const val MAX_PROBE = 64 * 1024

    /** packets that must line up when the payload does not start at offset 0 (avoids random matches) */
    private const val TS_PACKETS_TO_CONFIRM = 5

    private val ISO_BOX_TYPES = setOf(
        "ftyp", "styp", "moof", "moov", "sidx", "emsg", "prft",
    )

    fun findPayloadOffset(file: File): Long {
        if (!file.isFile) return 0
        val buffer = ByteArray(MAX_PROBE + TS_PACKET_SIZE * TS_PACKETS_TO_CONFIRM)
        val read = file.inputStream().use { readUpTo(it, buffer) }
        return findPayloadOffset(buffer, read).toLong()
    }

    fun findPayloadOffset(data: ByteArray, length: Int = data.size): Int {
        if (length <= 0) return 0
        if (isTsAt(data, length, 0, strict = false) || isIsoBoxAt(data, length, 0)) return 0
        val limit = minOf(length, MAX_PROBE)
        for (offset in 1 until limit) {
            if (isTsAt(data, length, offset, strict = true) || isIsoBoxAt(data, length, offset)) {
                return offset
            }
        }
        return 0
    }

    private fun isTsAt(data: ByteArray, length: Int, offset: Int, strict: Boolean): Boolean {
        if (data[offset] != TS_SYNC) return false
        val available = (length - offset + TS_PACKET_SIZE - 1) / TS_PACKET_SIZE
        val needed = if (strict) TS_PACKETS_TO_CONFIRM else 3
        val toCheck = minOf(needed, available)
        // a tiny segment that starts with the sync byte is fine, but a payload found after
        // a disguise header must be confirmed by several aligned packets
        if (strict && toCheck < 2) return false
        for (i in 1 until toCheck) {
            if (data[offset + i * TS_PACKET_SIZE] != TS_SYNC) return false
        }
        return true
    }

    private fun isIsoBoxAt(data: ByteArray, length: Int, offset: Int): Boolean {
        if (offset + 8 > length) return false
        val size = ((data[offset].toLong() and 0xff) shl 24) or
                ((data[offset + 1].toLong() and 0xff) shl 16) or
                ((data[offset + 2].toLong() and 0xff) shl 8) or
                (data[offset + 3].toLong() and 0xff)
        // 0 = box extends to end of file, 1 = 64-bit size follows; otherwise it must cover its own header
        if (size in 2L..7L) return false
        val type = String(data, offset + 4, 4, Charsets.ISO_8859_1)
        return type in ISO_BOX_TYPES
    }

    private fun readUpTo(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n <= 0) break
            total += n
        }
        return total
    }
}
