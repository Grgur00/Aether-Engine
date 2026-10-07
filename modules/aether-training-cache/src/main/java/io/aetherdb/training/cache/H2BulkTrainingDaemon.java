package io.aetherdb.training.cache;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/** Experiment bootstrap: frozen bulk publication, then normal serving in one JVM. */
public final class H2BulkTrainingDaemon {
    private H2BulkTrainingDaemon() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: H2BulkTrainingDaemon EMPTY_STORE");
        Path directory = Path.of(args[0]);
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            System.out.println(server.getLocalPort());
            try (var socket = server.accept(); var input = new DataInputStream(socket.getInputStream())) {
                socket.setSoTimeout(120_000);
                var output = new java.io.PrintWriter(socket.getOutputStream(), true);
                var committed = stage(directory, input, output);
                // The offline loader has released its directory lock before normal serving.
                try (var daemon = new TrainingCacheDaemon(directory, 0, 1L << 40,
                        TrainingCacheDurability.DURABLE)) {
                    committed.put("servicePort", daemon.port());
                    committed.put("servicePid", ProcessHandle.current().pid());
                    output.println(DiagnosticJson.encode(committed));
                    if (output.checkError()) throw new IOException("commit acknowledgement failed");
                    Thread.currentThread().join();
                }
            }
        }
    }

    private static java.util.Map<String, Object> stage(Path directory, DataInputStream input,
            java.io.PrintWriter output) throws IOException {
        try (var writer = new BulkArtifactWriter(directory, 32L * 1024 * 1024)) {
            while (true) {
                int length = input.readInt();
                if (length == 0) return new LinkedHashMap<>(writer.finish());
                if (length < 6 || length > 64 * 1024 * 1024)
                    throw new IOException("invalid bulk frame size");
                byte[] frame = new byte[length];
                input.readFully(frame);
                var entries = TrainingCacheProtocol.decodeBulkEntries(ByteBuffer.wrap(frame));
                writer.addAll(entries);
                output.println("STAGED " + entries.size());
                if (output.checkError()) throw new IOException("bootstrap acknowledgement failed");
            }
        }
    }
}
