package com.kasumi.tool

import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets

object TarUtil {

    /**
     * Streams a list of files into the output stream in POSIX tar format.
     * @param files List of files to stream.
     * @param out Output stream to write to.
     * @param nameMapper Optional function to map file to entry name. Defaults to file.name.
     */
    fun streamFiles(files: List<File>, out: OutputStream, nameMapper: (File) -> String = { it.name }) {
        for (f in files) {
            if (f.isFile && f.exists()) {
                writeTarEntry(f, nameMapper(f), out)
            }
        }
        // Write two empty blocks (1024 bytes) to mark end of archive
        out.write(ByteArray(1024))
        out.flush()
    }

    private fun writeTarEntry(file: File, name: String, out: OutputStream) {
        val size = file.length()
        // Header is 512 bytes
        val header = ByteArray(512)

        // Name (offset 0, length 100)
        writeString(header, 0, 100, name)

        // Mode (100, 8) - 0000644\0 (rw-r--r--)
        writeString(header, 100, 8, "0000644\u0000")

        // UID (108, 8)
        writeString(header, 108, 8, "0000000\u0000")

        // GID (116, 8)
        writeString(header, 116, 8, "0000000\u0000")

        // Size (124, 12) - octal string
        val sizeOctal = String.format("%011o", size) + "\u0000"
        writeString(header, 124, 12, sizeOctal)

        // Mtime (136, 12)
        val mtime = System.currentTimeMillis() / 1000
        val mtimeOctal = String.format("%011o", mtime) + "\u0000"
        writeString(header, 136, 12, mtimeOctal)

        // Checksum (148, 8) - calculated later as if spaces
        // Typeflag (156, 1) - '0' for normal file
        header[156] = '0'.code.toByte()

        // Magic (257, 6) - ustar
        writeString(header, 257, 6, "ustar\u0000")
        // Version (263, 2) - 00
        writeString(header, 263, 2, "00")

        // Calculate checksum
        for (i in 0 until 8) header[148 + i] = ' '.code.toByte()
        var sum = 0L
        for (b in header) sum += (b.toInt() and 0xFF)

        val sumOctal = String.format("%06o", sum) + "\u0000 "
        writeString(header, 148, 8, sumOctal)

        out.write(header)

        // Write file content
        file.inputStream().use { input ->
            input.copyTo(out)
        }

        // Padding to 512-byte boundary
        val padding = (512 - (size % 512)) % 512
        if (padding > 0) {
            out.write(ByteArray(padding.toInt()))
        }
    }

    private fun writeString(buffer: ByteArray, offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray(StandardCharsets.US_ASCII)
        for (i in 0 until length) {
            if (i < bytes.size) {
                buffer[offset + i] = bytes[i]
            } else {
                buffer[offset + i] = 0
            }
        }
    }
}
