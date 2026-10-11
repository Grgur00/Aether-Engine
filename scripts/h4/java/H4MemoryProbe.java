import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** A short-lived diagnostic JVM, not the Aether service or a benchmark. */
class H4MemoryProbe {
    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 21 || args.length != 1) {
            throw new IllegalArgumentException("H4 memory probe requires Java 21 and one receipt path");
        }
        Path receipt = Path.of(args[0]);
        Path temporary = receipt.resolveSibling(receipt.getFileName() + ".tmp");
        String json = "{\"pid\":" + ProcessHandle.current().pid()
                + ",\"java_major\":" + Runtime.version().feature()
                + ",\"heap_max_bytes\":" + Runtime.getRuntime().maxMemory() + "}";
        Files.writeString(temporary, json);
        Files.move(temporary, receipt, StandardCopyOption.ATOMIC_MOVE);
        System.in.read();
    }
}
