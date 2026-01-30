# NioKvStore — High-Performance Distributed Key-Value Store

**NioKvStore** is a lightweight, distributed, in-memory key-value database built from scratch in **Java**.  
Inspired by Redis, it uses **Non-blocking I/O (Java NIO)** and a **single-threaded event loop** to handle high concurrency efficiently.

It supports **Master–Slave replication**, **Append-Only File (AOF) persistence**, and achieves **100,000+ Requests Per Second (RPS)** on standard hardware.

---

## Key Features

- **Non-Blocking I/O Architecture**
    - Built on `java.nio.channels.Selector` (Reactor Pattern)
    - Handles thousands of concurrent connections without thread-per-client overhead

- **High Performance**
    - Benchmarked at **101,399 RPS**
    - Optimized via buffered I/O batching and pipelined network reads

- **Distributed Replication**
    - Supports **Master–Slave** topology
    - Writes on the master are asynchronously propagated to slaves

- **Persistence (AOF)**
    - Append-Only File logging (`magma.aof`)
    - Configurable fsync strategies for durability vs performance trade-offs

- **TTL & Expiration**
    - Supports temporary keys via `EXPIRE`
    - Hybrid expiration strategy:
        - Lazy expiration (on access)
        - Active expiration (probabilistic background sampling)

- **Redis-Compatible Protocol**
    - Simplified RESP-like text protocol
    - Compatible with `telnet` / `netcat` clients

---

## Architecture

The server operates on a **single-threaded event loop**:

1. **Selector**
    - Monitors socket channels for:
        - `OP_ACCEPT` — new connections
        - `OP_READ` — incoming data

2. **Command Processor**
    - Parses raw byte streams into commands (`SET`, `GET`, etc.)

3. **In-Memory Data Store**
    - Backed by `ConcurrentHashMap`
    - O(1) average-time access

4. **Persistence Layer**
    - Writes commands to `magma.aof`
    - Buffered in memory (64 KB chunks)
    - Flushed asynchronously to minimize disk I/O overhead

---

## Performance Benchmarks

**Environment:** Fedora Linux, Ryzen 5 7530U, OpenJDK 21

| Metric             | Result              |
|--------------------|---------------------|
| **Concurrency**    | 50 Threads          |
| **Total Requests**| 500,000             |
| **Time Taken**    | 4.93 seconds        |
| **Throughput**    | **101,399 req/sec** |

*Optimized using buffered output streams and batched system calls to reduce kernel context switching.*

---

## Getting Started

### Prerequisites

- Java Development Kit (JDK) 17 or higher
- Maven or IntelliJ IDEA (optional)

---

### Installation

```bash
git clone https://github.com/BAT4K/NioKvStore.git
cd NioKvStore
javac -d out src/*.java
```

---

## Usage

### 1. Start the Master Server

```bash
java -cp out KvServer -port 6379
```

---

### 2. Start a Slave (Replica)

```bash
java -cp out KvServer -port 6380 -slaveof localhost 6379
```

---

### 3. Connect via Client

#### Write to Master

```bash
nc localhost 6379
SET mykey hello_world
+OK
EXPIRE mykey 10
:1
```

#### Read from Slave

```bash
nc localhost 6380
GET mykey
$11
hello_world
```

---

## Supported Commands

| Command | Description | Example |
|--------|------------|---------|
| `SET key value` | Stores a key-value pair | `SET name "John Doe"` |
| `GET key` | Retrieves a value | `GET name` |
| `EXPIRE key seconds` | Sets TTL on a key | `EXPIRE name 60` |
| `PING` | Health check | `PING` |

---

## Future Improvements

- **RDB Snapshots** — Faster startup via point-in-time binary dumps
- **Multithreading** — Worker pool for command execution
- **Cluster Mode** — Hash-slot based sharding across masters

---

##  Author

**Hans James**

---

## License

This project is released under the **MIT License**.
