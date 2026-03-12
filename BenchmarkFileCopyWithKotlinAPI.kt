import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.random.Random

fun main() {
    val srcFile = File("test_src.bin")
    val destFile1 = File("test_dest1.bin")
    val destFile2 = File("test_dest2.bin")

    // Create a 50MB file
    val buffer = ByteArray(1024 * 1024)
    FileOutputStream(srcFile).use { fos ->
        for (i in 0 until 50) {
            Random.nextBytes(buffer)
            fos.write(buffer)
        }
    }

    // Benchmark 1: inputStream.copyTo (what's used now)
    val startTime1 = System.nanoTime()
    srcFile.inputStream().use { input ->
        FileOutputStream(destFile1).use { output ->
            input.copyTo(output)
        }
    }
    val endTime1 = System.nanoTime()
    val duration1 = (endTime1 - startTime1) / 1000000

    // Benchmark 2: FileChannel.transferTo
    val startTime2 = System.nanoTime()
    FileInputStream(srcFile).channel.use { src ->
        FileOutputStream(destFile2).channel.use { dest ->
            src.transferTo(0, src.size(), dest)
        }
    }
    val endTime2 = System.nanoTime()
    val duration2 = (endTime2 - startTime2) / 1000000

    println("InputStream copyTo (ms): $duration1")
    println("FileChannel transferTo (ms): $duration2")

    srcFile.delete()
    destFile1.delete()
    destFile2.delete()
}
