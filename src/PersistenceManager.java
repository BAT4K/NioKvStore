import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PersistenceManager {

    private static final String AOF_FILE = "magma.aof";
    private BufferedOutputStream writer;
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor();

    public PersistenceManager() {
        try {
            FileOutputStream fos = new FileOutputStream(AOF_FILE, true);
            // Increased buffer size to 64KB (default is 8KB) to hold more data in memory before auto-flush
            this.writer = new BufferedOutputStream(fos, 65536);

            // BACKGROUND FLUSH: Force flush every 1 second
            flusher.scheduleAtFixedRate(this::flushDisk, 1, 1, TimeUnit.SECONDS);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void append(String command) {
        try {
            writer.write(command.getBytes(StandardCharsets.UTF_8));
            writer.write('\n');
            // REMOVED: writer.flush();
            // We now rely on the background thread or the buffer filling up.
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Called by background thread
    private void flushDisk() {
        try {
            if (writer != null) writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void restore(Map<String, KvEntry> dataStore) {
        // ... (Keep existing restore code exactly as is) ...
        if (!Files.exists(Paths.get(AOF_FILE))) return;

        System.out.println("Restoring data...");
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(AOF_FILE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 3 && "SET".equalsIgnoreCase(parts[0])) {
                    String key = parts[1];
                    StringBuilder valueBuilder = new StringBuilder();
                    for (int i = 2; i < parts.length; i++) {
                        valueBuilder.append(parts[i]).append(" ");
                    }
                    dataStore.put(key, new KvEntry(valueBuilder.toString().trim()));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void close() {
        try {
            flusher.shutdown(); // Stop the background thread
            if (writer != null) {
                writer.flush(); // One last flush before closing
                writer.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}