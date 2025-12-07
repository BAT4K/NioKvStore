import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class KvServer {

    private static final Map<String, KvEntry> dataStore = new ConcurrentHashMap<>();
    private static PersistenceManager persistenceManager;

    // --- Distributed Features ---
    private static final List<SocketChannel> replicas = new CopyOnWriteArrayList<>();
    private static boolean isSlave = false;
    private static int port = 6379;

    public static void main(String[] args) {
        // Parse Arguments: -port 6379 -slaveof localhost 6379
        String masterHost = null;
        int masterPort = -1;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-port") && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            }
            if (args[i].equals("-slaveof") && i + 2 < args.length) {
                isSlave = true;
                masterHost = args[++i];
                masterPort = Integer.parseInt(args[++i]);
            }
        }

        // Initialize Persistence (Only Master needs AOF usually, but Slaves can too for backup)
        // For this demo, both use it, but Slave's AOF might duplicate Master's if not careful.
        // We will stick to standard behavior: Slaves write to disk too.
        persistenceManager = new PersistenceManager();
        persistenceManager.restore(dataStore);

        startActiveExpirationJob();

        try {
            Selector selector = Selector.open();

            // 1. Open Server Socket (Listening for Clients)
            ServerSocketChannel serverSocket = ServerSocketChannel.open();
            serverSocket.bind(new InetSocketAddress(port));
            serverSocket.configureBlocking(false);
            serverSocket.register(selector, SelectionKey.OP_ACCEPT);

            System.out.println("Server started on port " + port + (isSlave ? " [SLAVE]" : " [MASTER]"));

            // 2. If Slave, connect to Master immediately
            if (isSlave) {
                connectToMaster(masterHost, masterPort, selector);
            }

            // 3. Main Event Loop
            while (true) {
                selector.select();
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iter = selectedKeys.iterator();

                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();

                    if (!key.isValid()) continue;

                    if (key.isAcceptable()) {
                        handleAccept(serverSocket, selector);
                    } else if (key.isReadable()) {
                        handleRead(key);
                    } else if (key.isConnectable()) {
                        handleConnect(key);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            persistenceManager.close();
        }
    }

    // --- Slave Logic: Connect to Master ---
    private static void connectToMaster(String host, int port, Selector selector) throws IOException {
        SocketChannel masterChannel = SocketChannel.open();
        masterChannel.configureBlocking(false);
        masterChannel.connect(new InetSocketAddress(host, port));
        // Register for CONNECT event (to finish handshake)
        masterChannel.register(selector, SelectionKey.OP_CONNECT);
        System.out.println("Connecting to Master at " + host + ":" + port + "...");
    }

    private static void handleConnect(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        if (channel.isConnectionPending()) {
            channel.finishConnect();
        }
        // Once connected, we send a handshake so Master knows we are a replica
        channel.write(ByteBuffer.wrap("REPLCONF listening-port\r\n".getBytes(StandardCharsets.UTF_8)));

        // Switch to Read mode to listen for updates from Master
        channel.register(key.selector(), SelectionKey.OP_READ);
        System.out.println("Connected to Master! Ready to sync.");
    }

    // --- Standard Server Logic ---

    private static void handleAccept(ServerSocketChannel serverSocket, Selector selector) throws IOException {
        SocketChannel client = serverSocket.accept();
        client.configureBlocking(false);
        client.register(selector, SelectionKey.OP_READ);
    }

    private static void handleRead(SelectionKey key) {
        SocketChannel client = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        try {
            int bytesRead = client.read(buffer);
            if (bytesRead == -1) {
                client.close();
                replicas.remove(client); // If it was a replica, remove it
                return;
            }
            buffer.flip();
            String rawInput = StandardCharsets.UTF_8.decode(buffer).toString();
            String[] lines = rawInput.split("\r\n|\n");

            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                String response = processCommand(line.trim(), client);
                // Only write response if it's not null (Masters don't always reply to Slaves for every update)
                if (response != null && client.isOpen()) {
                    client.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));
                }
            }
        } catch (IOException e) {
            try { client.close(); } catch (IOException ex) { }
            replicas.remove(client);
        }
    }

    private static String processCommand(String input, SocketChannel sender) {
        String[] parts = input.split("\\s+");
        if (parts.length == 0) return null;
        String cmd = parts[0].toUpperCase();
        long now = System.currentTimeMillis();

        switch (cmd) {
            case "SET":
                if (parts.length < 3) return "-ERR args\r\n";
                String key = parts[1];
                StringBuilder valBuilder = new StringBuilder();
                for (int i = 2; i < parts.length; i++) {
                    valBuilder.append(parts[i]).append(" ");
                }
                String value = valBuilder.toString().trim();

                dataStore.put(key, new KvEntry(value));
                persistenceManager.append("SET " + key + " " + value);

                // PROPAGATION: If I am Master, send this command to all Replicas
                if (!isSlave) {
                    propagateToReplicas("SET " + key + " " + value + "\r\n");
                    return "+OK\r\n";
                }
                return null; // Slaves don't reply "+OK" to the Master, they just do the work silently.

            case "GET":
                if (parts.length < 2) return "-ERR args\r\n";
                KvEntry entry = dataStore.get(parts[1]);
                if (entry != null) {
                    if (entry.expiryAt != -1 && now > entry.expiryAt) {
                        dataStore.remove(parts[1]);
                        return "$-1\r\n";
                    }
                    return "$" + entry.value.length() + "\r\n" + entry.value + "\r\n";
                }
                return "$-1\r\n";

            case "EXPIRE":
                if (parts.length < 3) return "-ERR args\r\n";
                try {
                    long seconds = Long.parseLong(parts[2]);
                    KvEntry expEntry = dataStore.get(parts[1]);
                    if (expEntry != null) {
                        expEntry.expiryAt = now + (seconds * 1000);
                        if (!isSlave) {
                            propagateToReplicas("EXPIRE " + parts[1] + " " + seconds + "\r\n");
                            return ":1\r\n";
                        }
                    }
                    return isSlave ? null : ":0\r\n";
                } catch (NumberFormatException e) {
                    return "-ERR int\r\n";
                }

                // Internal Command: Handshake from a Slave
            case "REPLCONF":
                if (!isSlave) {
                    replicas.add(sender);
                    System.out.println("New Replica Registered: " + sender.socket().getRemoteSocketAddress());
                    return "+OK\r\n";
                }
                return "+OK\r\n";

            case "PING":
                return "+PONG\r\n";

            default:
                return "-ERR unknown\r\n";
        }
    }

    private static void propagateToReplicas(String command) {
        for (SocketChannel replica : replicas) {
            try {
                if (replica.isConnected()) {
                    replica.write(ByteBuffer.wrap(command.getBytes(StandardCharsets.UTF_8)));
                }
            } catch (IOException e) {
                // If write fails, assume replica is dead
                replicas.remove(replica);
            }
        }
    }

    // Background Cleaner (Same as before)
    private static void startActiveExpirationJob() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (dataStore.isEmpty()) return;
                int sampleSize = 20;
                Iterator<Map.Entry<String, KvEntry>> iterator = dataStore.entrySet().iterator();
                long now = System.currentTimeMillis();
                while (iterator.hasNext() && sampleSize > 0) {
                    Map.Entry<String, KvEntry> entry = iterator.next();
                    KvEntry val = entry.getValue();
                    if (val.expiryAt != -1 && now > val.expiryAt) {
                        iterator.remove();
                    }
                    sampleSize--;
                }
            } catch (Exception e) {}
        }, 1, 1, TimeUnit.SECONDS);
    }
}