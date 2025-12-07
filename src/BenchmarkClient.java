import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class BenchmarkClient {

    private static final String HOST = "localhost";
    private static final int PORT = 6379;

    // Configuration
    private static final int THREAD_COUNT = 50;
    private static final int REQUESTS_PER_THREAD = 10_000;
    private static final int TOTAL_REQUESTS = THREAD_COUNT * REQUESTS_PER_THREAD;

    public static void main(String[] args) {
        System.out.println("Starting Benchmark...");
        System.out.println("Threads: " + THREAD_COUNT);
        System.out.println("Requests per thread: " + REQUESTS_PER_THREAD);
        System.out.println("Total Requests: " + TOTAL_REQUESTS);
        System.out.println("------------------------------------------------");

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    runWorker(successCount);
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(); // Wait for all threads to finish
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        long endTime = System.currentTimeMillis();
        long durationMillis = endTime - startTime;
        double durationSeconds = durationMillis / 1000.0;
        double rps = TOTAL_REQUESTS / durationSeconds;

        executor.shutdown();

        System.out.println("------------------------------------------------");
        System.out.println("Benchmark Finished!");
        System.out.println("Time taken: " + durationSeconds + " seconds");
        System.out.println("Successful Ops: " + successCount.get());
        System.out.println("Errors: " + errorCount.get());
        System.out.println("Requests Per Second (RPS): " + String.format("%.2f", rps));
        System.out.println("------------------------------------------------");
    }

    private static void runWorker(AtomicInteger successCounter) throws Exception {
        // Create ONE connection per thread (Persistent Connection)
        try (Socket socket = new Socket(HOST, PORT);
             OutputStream out = socket.getOutputStream();
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            Random random = new Random();

            for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
                // 50/50 chance of SET or GET
                boolean isSet = random.nextBoolean();
                String key = "key_" + random.nextInt(1000); // Random key space

                String command;
                if (isSet) {
                    String value = "value_" + random.nextInt(1000);
                    command = "SET " + key + " " + value + "\r\n";
                } else {
                    command = "GET " + key + "\r\n";
                }

                // Send Request
                out.write(command.getBytes(StandardCharsets.UTF_8));
                out.flush();

                // Read Response
                // We must read the response to keep the protocol in sync
                String line = in.readLine();
                if (line == null) break;

                // If it's a GET request and returns a bulk string ($len), we need to read the data lines too
                if (line.startsWith("$")) {
                    int len = Integer.parseInt(line.substring(1));
                    if (len != -1) {
                        in.readLine(); // Read the value line
                    }
                    // The protocol adds an extra \r\n after value, but BufferedReader.readLine consumes the \n
                    // Check your KvServer implementation: does it send `result + "\r\n"`?
                    // If your server sends "$len\r\nvalue\r\n", readLine() gets "$len", next readLine() gets "value".
                }

                successCounter.incrementAndGet();
            }
        }
    }
}