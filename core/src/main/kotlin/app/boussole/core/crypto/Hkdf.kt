package app.boussole.core.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF-SHA256 (RFC 5869). */
object Hkdf {
    private const val ALGORITHM = "HmacSHA256"
    private const val HASH_BYTES = 32

    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_BYTES) { "Longueur HKDF invalide" }
        val prk = hmac(if (salt.isEmpty()) ByteArray(HASH_BYTES) else salt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            val chunk = minOf(previous.size, length - offset)
            previous.copyInto(out, offset, 0, chunk)
            offset += chunk
            counter++
        }
        prk.fill(0)
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        return mac.doFinal(data)
    }
}
