package com.umbra.hooks.core

import java.io.File
import java.security.MessageDigest

/** Full content hash, with a single streaming buffer and allocation-bounded hex encoding. */
internal object ApkDigest {
    private const val HEX = "0123456789abcdef"

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val bytes = digest.digest()
        val result = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 255
            result[i * 2] = HEX[value ushr 4]
            result[i * 2 + 1] = HEX[value and 15]
        }
        return String(result)
    }
}
