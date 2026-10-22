# Task List Manager — Java RMI

A shared task list with a Java Swing desktop client and a Java Remote Method Invocation (RMI) server. Multiple clients can add, remove, clear, and refresh the same list. The project uses only the Java standard library.

## Requirements

- A **JDK 11 or newer**, with both `java` and `javac` on `PATH`.
- A graphical desktop for the Swing client. The server can run without a display.
- PowerShell on Windows, or a POSIX shell on Linux/macOS, for the optional build scripts.

Check your installation with `java -version` and `javac -version`. Run the commands below from the repository root. Use the same compiled application version on the client and server.

## Run locally

### Windows / PowerShell

Build once:

```powershell
.\build.ps1
```

Start the server in one terminal:

```powershell
java -cp out TaskServer
```

Keep that terminal open. Start the client in a second terminal:

```powershell
java -cp out TaskClient
```

If PowerShell policy prevents running the build script, compile directly, then use the same server and client commands:

```powershell
New-Item -ItemType Directory -Force out | Out-Null
javac --release 11 -Xlint:all -Werror -d out *.java
```

### Linux / macOS

Build and start the server:

```sh
sh ./build.sh
java -cp out TaskServer
```

Start the client from the repository root in a second terminal:

```sh
java -cp out TaskClient
```

Alternatively, compile directly:

```sh
mkdir -p out
javac --release 11 -Xlint:all -Werror -d out ./*.java
```

The server creates its own RMI registry and exports its service. You do **not** need to run `rmiregistry` separately or generate stubs with `rmic`; Java creates the remote stubs dynamically. See the [Java RMI server specification](https://docs.oracle.com/en/java/javase/11/docs/specs/rmi/server.html).

## Use the application

1. Start the server, then open one or more clients.
2. Choose **Add Task**, enter a description, and confirm.
3. Select a task and choose **Remove Task** to remove one matching entry.
4. Choose **Delete All** and confirm to clear the shared list.
5. Choose **Refresh** to fetch other clients' changes or reconnect after a connection failure.

Each successful mutation fetches the complete list again. Other clients update when they refresh or perform their own operation; there is no automatic push or polling.

Task descriptions have leading and trailing whitespace removed. Empty descriptions, `null` values, and normalized descriptions longer than 1,000 UTF-16 code units are rejected by the server. The list holds at most 10,000 tasks. Duplicate descriptions are allowed; removal deletes the first matching entry, so duplicates have no separate identity.

Closing a client leaves the server's list intact. **Stopping or restarting the server loses every task**: storage is in memory, with no file or database persistence. Stop the server with `Ctrl+C` in its terminal.

If an operation reports a connection error, use **Refresh** and inspect the list before trying again. A failed remote call can have reached the server even if its response did not reach the client. Mutations are not automatically retried.

## Configuration and trusted LAN use

```text
TaskServer [bind-address [registry-port [service-port]]]
TaskClient [host [registry-port]]
```

| Setting | Default |
| --- | --- |
| Server bind address / client host | `127.0.0.1` |
| Registry TCP port | `1099` |
| Task service TCP port | `1100` |
| Registry binding name | `TaskManager` |

For command-line help:

```sh
java -cp out TaskServer --help
java -cp out TaskClient --help
```

The default server accepts connections on the local loopback interface. To share a list on a trusted LAN, supply the server computer's actual LAN address. For example, if that computer has address `192.168.1.20`:

```sh
# On the server computer:
java -cp out TaskServer 192.168.1.20

# On each client computer, after compiling the same sources:
java -cp out TaskClient 192.168.1.20
```

Custom ports work similarly:

```sh
java -cp out TaskServer 192.168.1.20 2099 2100
java -cp out TaskClient 192.168.1.20 2099
```

Both server ports must be reachable from clients. The client first contacts the registry, then obtains the service's address and port from its remote stub. The server binds both listeners to the specified resolved interface and advertises that address. It rejects wildcard addresses such as `0.0.0.0` and `::`; choose an address assigned to the server computer. Server ports must be distinct integers from `1` to `65535`; the client's registry port has the same range.

The application has **no authentication or TLS**. Every connected client can read and modify the entire list. Keep it on the local machine or a trusted private network; do not expose its ports to the public internet.

Custom RMI socket factories control interface binding and apply a 3-second connection timeout and a 5-second socket read timeout. DNS resolution and multi-step operations can take longer than an individual timeout. See Oracle's [custom RMI socket factory guide](https://docs.oracle.com/javase/8/docs/technotes/guides/rmi/socketfactory/index.html).

## How it works

```text
Swing client
    | registry lookup (TCP 1099 by default)
    v
RMI registry: "TaskManager"
    | returns service stub
    v
TaskManager service (TCP 1100 by default)
    |
    v
Shared in-memory task list
```

| File / directory | Purpose |
| --- | --- |
| `TaskManager.java` | Remote contract for adding, removing, reading, and clearing tasks. |
| `TaskManagerImpl.java` | Validates requests and protects shared state; reads return independent snapshots. |
| `TaskServer.java` | Parses configuration, starts the registry and service, and handles startup/shutdown. |
| `TaskTransport.java` | Shared connection settings, argument validation, and RMI socket factories. |
| `TaskClient.java` | Swing interface; performs remote work in the background and updates the UI on Swing's event thread. |
| `TaskClientConnection.java` | Client connection and remote-operation logic, separate from the Swing interface. |
| `build.ps1`, `build.sh` | Compile with Java 11 compatibility and strict compiler warnings; optionally run tests. |
| `tests/` | Dependency-free regression and integration checks. |
| `out/` | Generated class files; excluded from version control. |

RMI calls can arrive concurrently. The service synchronizes task operations and returns a copy of the list so callers cannot mutate its internal state. Individual remote methods are atomic; a mutation followed by a separate refresh can also show concurrent changes from another client.

## Build and verify

Run the dependency-free test suite:

```powershell
# Windows / PowerShell
.\build.ps1 -Test
```

```sh
# Linux / macOS
sh ./build.sh --test
```

Both scripts compile with `--release 11 -Xlint:all -Werror`. Test mode also compiles `tests/*.java` and runs `TaskManagerImplTest`, `TaskIntegrationTest`, `TaskClientTest`, and `TaskTransportTest`. These checks cover service behavior, validation, concurrent access, RMI communication, client behavior, and stalled connection timeouts. Integration checks use local sockets; run them in an environment that permits loopback networking. A successful run exits with status `0`; a failed compile or check exits with a nonzero status.

For a manual check, open two clients against one server, add a task in the first, refresh the second, remove it, and refresh the first. Close and reopen a client to verify that tasks remain while the server runs. Stop and restart the server to verify that the new list is empty, then use **Refresh** to reconnect an existing client.

## Audit changes

- Protected the shared list against concurrent RMI calls and returned defensive snapshots.
- Added server-side validation, whitespace normalization, and task/description size limits.
- Moved network operations off Swing's event thread and made displayed data refresh from the server.
- Added reconnect through **Refresh**, visible connection failures, and confirmation before clearing the list.
- Made registry and service ports predictable, restricted the default listeners to loopback, and added explicit address/port validation.
- Added socket timeouts, startup failure handling, cleanup, and command-line help.
- Added repeatable builds and regression tests without external dependencies.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| `java` or `javac` is not recognized | Install a JDK and add its `bin` directory to `PATH`. A runtime-only installation does not include `javac`. |
| Compilation rejects `--release 11` | Check `javac -version`; use JDK 11 or newer. |
| Could not find or load main class | Compile from the repository root, then use `java -cp out TaskServer` or `java -cp out TaskClient` from that directory. |
| Address already in use / port in use | Stop an existing server or choose two unused ports. Do not start a separate `rmiregistry`. Pass the chosen registry port to the client. |
| Cannot assign requested address | The bind address must belong to the server computer. Use `127.0.0.1` locally or its actual LAN address. |
| Connection refused / connection failure | Start the server first; check its terminal output and the client's host/registry port. After a restart, choose **Refresh**. |
| Registry lookup works but task operations time out | Check reachability and firewall rules for **both** the registry and service ports. Verify that the server advertises an address reachable from the client. |
| Another client's changes are missing | Choose **Refresh**; client lists do not update automatically. |
| Headless environment / no display | Run the server there and start the Swing client on a machine with a graphical desktop. |
| Tasks disappeared after restarting the server | This is expected: tasks live only in the running server's memory. |
