package com.breakyuna.noveltranslator.nativecore.formats

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Small in-memory ZIP fixtures used by the binary adapter tests. */
internal object FormatArchiveFixtures {
    fun zip(entries: List<Pair<String, ByteArray>>, stored: Set<String> = emptySet()): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                if (name in stored || name.endsWith('/')) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                if (bytes.isNotEmpty()) zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = readAll(zip)
                zip.closeEntry()
            }
        }
        return result
    }

    fun entryNames(bytes: ByteArray): List<String> {
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names.add(entry.name)
                readAll(zip)
                zip.closeEntry()
            }
        }
        return names
    }

    fun entryMethods(bytes: ByteArray): List<Pair<String, Int>> {
        val methods = ArrayList<Pair<String, Int>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                methods.add(entry.name to entry.method)
                readAll(zip)
                zip.closeEntry()
            }
        }
        return methods
    }

    fun text(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    private fun readAll(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
