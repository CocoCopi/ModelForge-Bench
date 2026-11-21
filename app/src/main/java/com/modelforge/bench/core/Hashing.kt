package com.modelforge.bench.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Hashing {
    fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun short(hash: String, characters: Int = 12): String =
        if (hash.length <= characters) hash else hash.take(characters)
}
