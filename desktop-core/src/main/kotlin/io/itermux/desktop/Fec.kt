package io.itermux.desktop

/**
 * Reed-Solomon erasure code over GF(2^8) (polynomial 0x11d) with a Cauchy matrix, the same as the
 * plugin's `fec.rs`: k data shards and m parity shards, any k of them rebuild the data.
 */
object Fec {
    const val MAX_SHARDS = 255
    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11d
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }

    fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
    fun inv(a: Int): Int { require(a != 0); return exp[255 - log[a]] }

    /** C[j][i] = 1 / (x_j + y_i) with y_i = i, x_j = k + j. */
    private fun cauchy(k: Int, j: Int, i: Int) = inv((k + j) xor i)

    fun encode(data: List<ByteArray>, m: Int): List<ByteArray> {
        val k = data.size
        require(k > 0 && k + m <= MAX_SHARDS)
        val len = data[0].size
        return List(m) { j ->
            val p = ByteArray(len)
            data.forEachIndexed { i, d ->
                val c = cauchy(k, j, i)
                for (n in 0 until len) p[n] = (p[n].toInt() xor mul(c, d[n].toInt() and 0xFF)).toByte()
            }
            p
        }
    }

    /** The k data shards from any k of [shards] (data first, then parity; null = lost), or null with fewer. */
    fun decode(shards: List<ByteArray?>, k: Int): List<ByteArray>? {
        val present = shards.indices.filter { shards[it] != null }.take(k)
        if (present.size < k) return null
        if ((0 until k).all { shards[it] != null }) return (0 until k).map { shards[it]!! }
        val len = shards[present[0]]!!.size
        val a = Array(k) { r -> val s = present[r]; IntArray(k) { i -> if (s < k) (if (s == i) 1 else 0) else cauchy(k, s - k, i) } }
        val b = Array(k) { r -> IntArray(k) { c -> if (r == c) 1 else 0 } }
        for (col in 0 until k) {
            val pivot = (col until k).firstOrNull { a[it][col] != 0 } ?: return null
            a[col] = a[pivot].also { a[pivot] = a[col] }
            b[col] = b[pivot].also { b[pivot] = b[col] }
            val f = inv(a[col][col])
            for (c in 0 until k) { a[col][c] = mul(a[col][c], f); b[col][c] = mul(b[col][c], f) }
            for (r in 0 until k) if (r != col && a[r][col] != 0) {
                val g = a[r][col]
                for (c in 0 until k) { a[r][c] = a[r][c] xor mul(g, a[col][c]); b[r][c] = b[r][c] xor mul(g, b[col][c]) }
            }
        }
        return List(k) { i ->
            val out = ByteArray(len)
            present.forEachIndexed { r, s ->
                val c = b[i][r]
                if (c != 0) { val src = shards[s]!!; for (n in 0 until len) out[n] = (out[n].toInt() xor mul(c, src[n].toInt() and 0xFF)).toByte() }
            }
            out
        }
    }
}
