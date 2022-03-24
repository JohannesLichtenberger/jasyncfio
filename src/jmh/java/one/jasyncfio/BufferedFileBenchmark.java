package one.jasyncfio;

import org.openjdk.jmh.annotations.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Warmup(iterations = 1)
@Measurement(iterations = 2)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class BufferedFileBenchmark {

    @State(Scope.Benchmark)
    public static class Data {
        private static final int sizeBytes = 512;
        private static final int jasyncfioIterations = 128;

        public ByteBuffer[] readBuffers = new ByteBuffer[jasyncfioIterations];
        public ByteBuffer[] writeBuffers = new ByteBuffer[jasyncfioIterations];
        public EventExecutorGroup eventExecutorGroup = EventExecutorGroup.
                builder()
                .entries(jasyncfioIterations)
                .build();
        public CompletableFuture<Integer>[] futures = new CompletableFuture[jasyncfioIterations];

        Path tmpDir;
        Path readTestFile;
        Path writeTestFile;

        {
            try {
                tmpDir = Files.createTempDirectory("tmp-dir-");
                readTestFile = Files.createFile(tmpDir.resolve("read-test-file"));
                writeTestFile = Files.createFile(tmpDir.resolve("write-test-file"));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }


        @Setup
        public void setup() throws IOException {
            Files.write(readTestFile, generateContent(sizeBytes), StandardOpenOption.WRITE);
            Random random = new Random();
            byte[] bytes = new byte[sizeBytes];
            for (int i = 0; i < jasyncfioIterations; i++) {
                readBuffers[i] = ByteBuffer.allocateDirect(sizeBytes);
                ByteBuffer writeBuffer = ByteBuffer.allocateDirect(sizeBytes);
                random.nextBytes(bytes);
                writeBuffer.put(bytes);
                writeBuffers[i] = writeBuffer;
            }
        }

        @TearDown
        public void tearDown() throws IOException {
            Files.delete(readTestFile);
            Files.delete(writeTestFile);
            Files.delete(tmpDir);
        }

        private static byte[] generateContent(int sizeBytes) {
            byte[] content = new byte[sizeBytes];
            new Random().nextBytes(content);
            return content;
        }
    }

    @Benchmark
    @OperationsPerInvocation(Data.jasyncfioIterations)
    @Fork(1)
    public Integer jasyncfioRead(Data data) throws Exception {
        BufferedFile readTestFile = data.eventExecutorGroup.openBufferedFile(data.readTestFile.toString()).join();
        for (int i = 0; i < Data.jasyncfioIterations; i++) {
            data.futures[i] = readTestFile.read(0, data.readBuffers[i]);
        }
        CompletableFuture.allOf(data.futures).get();
        return readTestFile.close().get();
    }

    @Benchmark
    @OperationsPerInvocation(Data.jasyncfioIterations)
    @Fork(1)
    public int jasyncfioWrite(Data data) throws Exception {
        BufferedFile writeTestFile = data.eventExecutorGroup.createBufferedFile(data.writeTestFile.toString()).get();
        for (int i = 0; i < Data.jasyncfioIterations; i++) {
            data.futures[i] = writeTestFile.write(-1, data.writeBuffers[i]);
        }
        CompletableFuture.allOf(data.futures).get();
        return writeTestFile.close().get();
    }

    @Benchmark
    @Fork(1)
    public int nioRead(Data data) throws Exception {
        try (FileChannel readTestFileChannel = FileChannel.open(data.readTestFile, StandardOpenOption.READ)) {
            int read = readTestFileChannel.read(data.readBuffers[0]);
            data.readBuffers[0].flip();
            return read;
        }
    }

    @Benchmark
    @Fork(1)
    public int nioWrite(Data data) throws Exception {
        try (FileChannel writeTestFileChannel = FileChannel.open(data.writeTestFile, StandardOpenOption.WRITE)) {
            int written = writeTestFileChannel.write(data.writeBuffers[0]);
            data.writeBuffers[0].flip();
            return written;
        }
    }
}