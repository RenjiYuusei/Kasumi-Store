import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.util.Random;

public class BenchmarkFileCopy {
    public static void main(String[] args) throws Exception {
        File srcFile = new File("test_src.bin");
        File destFile1 = new File("test_dest1.bin");
        File destFile2 = new File("test_dest2.bin");

        // Create a 50MB file
        byte[] buffer = new byte[1024 * 1024];
        Random rnd = new Random();
        try (FileOutputStream fos = new FileOutputStream(srcFile)) {
            for (int i = 0; i < 50; i++) {
                rnd.nextBytes(buffer);
                fos.write(buffer);
            }
        }

        // Benchmark 1: InputStream.read / OutputStream.write (similar to Kotlin's copyTo)
        long startTime1 = System.nanoTime();
        try (FileInputStream fis = new FileInputStream(srcFile);
             FileOutputStream fos = new FileOutputStream(destFile1)) {
            byte[] buf = new byte[8 * 1024]; // Default buffer size in Kotlin copyTo
            int bytesRead;
            while ((bytesRead = fis.read(buf)) != -1) {
                fos.write(buf, 0, bytesRead);
            }
        }
        long endTime1 = System.nanoTime();
        long duration1 = (endTime1 - startTime1) / 1000000; // ms

        // Benchmark 2: FileChannel.transferTo
        long startTime2 = System.nanoTime();
        try (FileChannel srcChannel = new FileInputStream(srcFile).getChannel();
             FileChannel destChannel = new FileOutputStream(destFile2).getChannel()) {
            srcChannel.transferTo(0, srcChannel.size(), destChannel);
        }
        long endTime2 = System.nanoTime();
        long duration2 = (endTime2 - startTime2) / 1000000; // ms

        System.out.println("InputStream copy (ms): " + duration1);
        System.out.println("FileChannel transferTo (ms): " + duration2);

        srcFile.delete();
        destFile1.delete();
        destFile2.delete();
    }
}
