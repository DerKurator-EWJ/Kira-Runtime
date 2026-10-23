package de.equinox.v05

/**
 * Minimal BLAKE2b implementation used so Android produces the same 63-bit IDs as
 * Equinox Language v0.3 (Python hashlib.blake2b, digest_size=8, person=...).
 * It intentionally supports the unkeyed mode used by Equinox only.
 */
object EquinoxId64 {
    private val IV = longArrayOf(
        0x6A09E667F3BCC908UL.toLong(), 0xBB67AE8584CAA73BUL.toLong(),
        0x3C6EF372FE94F82BUL.toLong(), 0xA54FF53A5F1D36F1UL.toLong(),
        0x510E527FADE682D1UL.toLong(), 0x9B05688C2B3E6C1FUL.toLong(),
        0x1F83D9ABFB41BD6BUL.toLong(), 0x5BE0CD19137E2179UL.toLong()
    )

    private val SIGMA = arrayOf(
        intArrayOf(0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15),
        intArrayOf(14,10,4,8,9,15,13,6,1,12,0,2,11,7,5,3),
        intArrayOf(11,8,12,0,5,2,15,13,10,14,3,6,7,1,9,4),
        intArrayOf(7,9,3,1,13,12,11,14,2,6,5,10,4,0,15,8),
        intArrayOf(9,0,5,7,2,4,10,15,14,1,11,12,6,8,3,13),
        intArrayOf(2,12,6,10,0,11,8,3,4,13,7,5,15,14,1,9),
        intArrayOf(12,5,1,15,14,13,4,10,0,7,6,3,9,2,8,11),
        intArrayOf(13,11,7,14,12,1,3,9,5,0,15,4,8,6,2,10),
        intArrayOf(6,15,14,9,11,3,0,8,12,2,13,7,1,4,10,5),
        intArrayOf(10,2,8,4,7,6,1,5,15,11,9,14,3,12,13,0),
        intArrayOf(0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15),
        intArrayOf(14,10,4,8,9,15,13,6,1,12,0,2,11,7,5,3)
    )

    private fun rotr64(x: Long, n: Int): Long = (x ushr n) or (x shl (64 - n))

    private fun readLongLE(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xffL) shl (8 * i))
        return v
    }

    private fun writeLongBE(v: Long, out: ByteArray, off: Int) {
        for (i in 0 until 8) out[off + i] = ((v ushr (56 - 8 * i)) and 0xffL).toByte()
    }

    private fun writeLongLE(v: Long, out: ByteArray, off: Int) {
        for (i in 0 until 8) out[off + i] = ((v ushr (8 * i)) and 0xffL).toByte()
    }

    private fun g(v: LongArray, a: Int, b: Int, c: Int, d: Int, x: Long, y: Long) {
        v[a] = v[a] + v[b] + x
        v[d] = rotr64(v[d] xor v[a], 32)
        v[c] += v[d]
        v[b] = rotr64(v[b] xor v[c], 24)
        v[a] = v[a] + v[b] + y
        v[d] = rotr64(v[d] xor v[a], 16)
        v[c] += v[d]
        v[b] = rotr64(v[b] xor v[c], 63)
    }

    private fun compress(h: LongArray, block: ByteArray, t0: Long, t1: Long, last: Boolean) {
        val m = LongArray(16) { readLongLE(block, it * 8) }
        val v = LongArray(16)
        for (i in 0 until 8) v[i] = h[i]
        for (i in 0 until 8) v[i + 8] = IV[i]
        v[12] = v[12] xor t0
        v[13] = v[13] xor t1
        if (last) v[14] = v[14].inv()
        for (r in 0 until 12) {
            val s = SIGMA[r]
            g(v,0,4,8,12,m[s[0]],m[s[1]])
            g(v,1,5,9,13,m[s[2]],m[s[3]])
            g(v,2,6,10,14,m[s[4]],m[s[5]])
            g(v,3,7,11,15,m[s[6]],m[s[7]])
            g(v,0,5,10,15,m[s[8]],m[s[9]])
            g(v,1,6,11,12,m[s[10]],m[s[11]])
            g(v,2,7,8,13,m[s[12]],m[s[13]])
            g(v,3,4,9,14,m[s[14]],m[s[15]])
        }
        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    fun blake2b(data: ByteArray, digestSize: Int = 8, person: ByteArray = ByteArray(0)): ByteArray {
        require(digestSize in 1..64)
        require(person.size <= 16)
        val param = ByteArray(64)
        param[0] = digestSize.toByte()
        param[1] = 0 // key length
        param[2] = 1 // fanout
        param[3] = 1 // depth
        person.copyInto(param, destinationOffset = 48)
        val h = LongArray(8) { IV[it] xor readLongLE(param, it * 8) }

        var t0 = 0L
        var t1 = 0L
        var offset = 0
        while (data.size - offset > 128) {
            val block = data.copyOfRange(offset, offset + 128)
            val old = t0
            t0 += 128L
            if (java.lang.Long.compareUnsigned(t0, old) < 0) t1++
            compress(h, block, t0, t1, false)
            offset += 128
        }
        val remaining = data.size - offset
        val lastBlock = ByteArray(128)
        if (remaining > 0) data.copyInto(lastBlock, 0, offset, data.size)
        val old = t0
        t0 += remaining.toLong()
        if (java.lang.Long.compareUnsigned(t0, old) < 0) t1++
        compress(h, lastBlock, t0, t1, true)

        val full = ByteArray(64)
        for (i in 0 until 8) writeLongLE(h[i], full, i * 8)
        return full.copyOf(digestSize)
    }

    fun stableId(namespace: String, symbol: String): Long {
        val payload = (namespace + "\u0000" + symbol).toByteArray(Charsets.UTF_8)
        val digest = blake2b(payload, 8, "EQXNUM01".toByteArray(Charsets.US_ASCII))
        var v = 0L
        for (b in digest) v = (v shl 8) or (b.toLong() and 0xffL)
        v = v and Long.MAX_VALUE
        return if (v == 0L) 1L else v
    }

    fun personalized63(person: String, raw: String): Long {
        val digest = blake2b(raw.toByteArray(Charsets.UTF_8), 8, person.toByteArray(Charsets.US_ASCII))
        var v = 0L
        for (b in digest) v = (v shl 8) or (b.toLong() and 0xffL)
        v = v and Long.MAX_VALUE
        return if (v == 0L) 1L else v
    }
}
