import io.aetherdb.training.cache.TrainingCacheDaemon;
import io.aetherdb.training.cache.TrainingCacheDurability;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import jdk.jfr.FlightRecorder;
import jdk.jfr.RecordingState;
import jdk.jfr.Recording;
import jdk.jfr.Configuration;

/** Harness lifecycle only; the storage and service implementations are unchanged. */
class H4DaemonLifecycle {
    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 21 || (args.length != 2 && args.length != 3)) {
            throw new IllegalArgumentException("Java 21, store path and capacity required");
        }
        long heap = Runtime.getRuntime().maxMemory();
        boolean recording = FlightRecorder.isInitialized() && FlightRecorder.getFlightRecorder()
                .getRecordings().stream().anyMatch(item -> item.getState() == RecordingState.RUNNING);
        if (heap > 512L * 1024 * 1024 || recording) {
            throw new IllegalArgumentException("H4 requires bounded 512 MiB heap and no active JFR recording");
        }
        // Explicit diagnostic mode only; primary retrieval never supplies this argument.
        Recording diagnostic = null;
        if (args.length == 3) {
            Path destination = Path.of(args[2]).resolve("daemon-" + ProcessHandle.current().pid() + ".jfr");
            diagnostic = new Recording(Configuration.getConfiguration("profile"));
            diagnostic.setDestination(destination);
            diagnostic.setDumpOnExit(true);
            diagnostic.setMaxSize(128L * 1024 * 1024);
            diagnostic.start();
        }
        Path store = Path.of(args[0]);
        String heapSamples = System.getProperty("h4.heap.samples");
        var stopSampling = new java.util.concurrent.atomic.AtomicBoolean();
        Thread sampler = null;
        if (heapSamples != null) {
            Path samples = Path.of(heapSamples).resolve("heap-" + ProcessHandle.current().pid() + ".csv");
            sampler = new Thread(() -> {
                try (var writer = Files.newBufferedWriter(samples)) {
                    writer.write("monotonic_ns,heap_used_bytes,heap_committed_bytes,heap_max_bytes\n");
                    while (!stopSampling.get()) {
                        var usage = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                        writer.write(System.nanoTime() + "," + usage.getUsed() + "," + usage.getCommitted() + "," + usage.getMax() + "\n");
                        writer.flush();
                        Thread.sleep(100);
                    }
                } catch (Exception failure) {
                    failure.printStackTrace(System.err);
                }
            }, "h4-heap-sampler");
            sampler.setDaemon(true);
            sampler.start();
        }
        Path receipt = store.resolveSibling(store.getFileName() + ".runtime.json");
        Path staged = receipt.resolveSibling(receipt.getFileName() + ".partial");
        Files.writeString(staged, "{\"pid\":" + ProcessHandle.current().pid()
                + ",\"java_major\":21,\"heap_max_bytes\":" + heap
                + ",\"active_jfr_recording\":" + (diagnostic != null) + "}\n");
        Files.move(staged, receipt, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        try (var daemon = new TrainingCacheDaemon(store, 0, Long.parseLong(args[1]),
                TrainingCacheDurability.DURABLE)) {
            System.out.println(daemon.port());
            System.out.flush();
            System.in.read();
        } finally {
            stopSampling.set(true);
            if (sampler != null) sampler.join(1000);
            if (diagnostic != null) {
                diagnostic.stop();
                diagnostic.close();
            }
        }
    }
}
