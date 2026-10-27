package ir.amirab.downloader.downloaditem.hls

import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Standard HLS AES-128 segment encryption (RFC 8216 §4.3.2.4, `#EXT-X-KEY:METHOD=AES-128`).
 *
 * Whole segments are encrypted with AES-128-CBC + PKCS7 padding. The 16-byte key is fetched from the
 * key URI; the IV is either given by the `IV` attribute or is the segment's media sequence number.
 *
 * This is *not* DRM: SAMPLE-AES / SAMPLE-AES-CTR and non-"identity" KEYFORMATs (FairPlay, Widevine,
 * PlayReady, ...) are deliberately unsupported and rejected while parsing the playlist.
 */
object HLSSegmentCrypto {
    const val KEY_SIZE = 16

    /** IV used when the playlist doesn't specify one: the media sequence number as a 128-bit big-endian int. */
    fun ivForSequence(mediaSequence: Long): String {
        return "%032x".format(mediaSequence)
    }

    /** Normalizes an `IV=0x...` attribute to 32 lowercase hex chars. */
    fun normalizeIv(iv: String): String {
        val hex = iv.trim().removePrefix("0x").removePrefix("0X").lowercase()
        require(hex.isNotEmpty() && hex.length <= 32 && hex.all { it in '0'..'9' || it in 'a'..'f' }) {
            "invalid HLS IV: $iv"
        }
        return hex.padStart(32, '0')
    }

    fun decrypt(encrypted: ByteArray, key: ByteArray, ivHex: String): ByteArray {
        require(key.size == KEY_SIZE) { "AES-128 key must be $KEY_SIZE bytes but was ${key.size}" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(hexToBytes(ivHex)))
        return cipher.doFinal(encrypted)
    }

    fun decrypt(file: File, key: ByteArray, ivHex: String): ByteArray {
        return decrypt(file.readBytes(), key, ivHex)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val h = normalizeIv(hex)
        return ByteArray(16) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
