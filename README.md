# Task List Manager — Java RMI

A small Java Swing task manager that uses Java RMI to share tasks between clients and a server. It supports editing, completion, optional due dates, automatic refresh, and local file storage. The project uses only the Java standard library.

## Requirements

- **JDK 11 or newer**, with `java` and `javac` on `PATH`.
- A graphical desktop for the client; the server can run headlessly.
- PowerShell on Windows, or a shell on Linux/macOS, for the build scripts.

Check your installation with `java -version` and `javac -version`.

## Run

Run these commands from the project folder. After updating the code, stop older clients and servers, rebuild, and restart them together.

Build on Windows:

```powershell
.\build.ps1
```

Or on Linux/macOS:

```sh
sh ./build.sh
```

Start the server in one terminal:

```sh
java -cp out TaskServer
```

Keep it running and start the client in another terminal:

```sh
java -cp out TaskClient
```

You can open additional clients with the same command. The server starts its own RMI registry; no separate `rmiregistry` or stub generation is needed. Press **Ctrl+C** in the server terminal to stop it.

If PowerShell policy blocks the build script, compile directly:

```powershell
New-Item -ItemType Directory -Force out | Out-Null
javac --release 11 -Xlint:all -Werror -d out src/*.java
```

## Usage

- **Add:** enter a description and an optional due date (`YYYY-MM-DD`), then press **Enter** or **Add Task**.
- **Complete:** check **Done** to cross out a task; uncheck it to reopen it.
- **Edit:** select a task and choose **Edit Task**. Clear its date field to remove the deadline.
- **Remove:** choose **Remove Task** for the selected task, or **Delete All** and confirm to clear the list.
- **Refresh:** clients attempt to refresh every five seconds while connected and idle. Use **Refresh** to update immediately or reconnect after a failure.

Automatic refresh skips active requests, dialogs, and checkbox editing; updates can take longer than five seconds. After a connection error, refresh and inspect the list before repeating a change, because the server may already have applied it.

Unfinished tasks are **Overdue** when their due date is before today on the client computer. Due dates never delete tasks. Each task has its own ID, so duplicate descriptions remain independent. Descriptions are trimmed and limited to 1,000 UTF-16 code units; the list holds up to 10,000 tasks.

## How it works and storage

The client looks up `TaskManager` in the RMI registry, then calls the service directly. Server operations are synchronized, and the Swing client performs network requests in the background.

Java source files are in `src/`, regression tests are in `tests/`, and the build scripts and documentation stay in the project root.

| Component | Files |
| --- | --- |
| Task data and remote API | `Task.java`, `TaskManager.java` |
| Server and persistence | `TaskServer.java`, `TaskManagerImpl.java`, `TaskStore.java` |
| Desktop client | `TaskClient.java`, `TaskClientConnection.java`, `TaskTableModel.java` |
| Connection settings and sockets | `TaskTransport.java` |

The server saves tasks, completion states, and due dates to **`data/tasks.tsv`**. They survive client closure and server restarts. Restart from the same folder or pass an explicit file path to reopen the same list. The server prints the file path at startup.

Changes are saved before updating memory, and a file lock prevents concurrent servers from writing the same file. Invalid or unreadable data stops startup instead of resetting the list. Generated files in `data/` and compiled classes in `out/` are excluded from Git.

## Configuration

```text
TaskServer [bind-address [registry-port [service-port [data-file]]]]
TaskClient [host [registry-port]]
```

Defaults: **`127.0.0.1`**, registry port **`1099`**, service port **`1100`**, and **`data/tasks.tsv`**. Ports must be in `1..65535`, and the two server ports must differ.

For example, use different ports and a separate list:

```sh
java -cp out TaskServer 127.0.0.1 2099 2100 data/personal-tasks.tsv
java -cp out TaskClient 127.0.0.1 2099
```

Run the server and client in separate terminals. Use `java -cp out TaskServer --help` or `java -cp out TaskClient --help` for usage.

The server accepts local connections by default. For trusted LAN use, pass the server's actual LAN address to both programs and allow both ports through the firewall. There is no authentication or TLS; keep access limited to your local machine or trusted network.

If startup fails, check the server's message, address, ports, and data-file permissions. Stop any other server using the same ports or data file. If the client cannot connect, start the server first and check its host/registry port, then choose **Refresh**.

## Tests

```powershell
# Windows
.\build.ps1 -Test
```

```sh
# Linux/macOS
sh ./build.sh --test
```

Both scripts compile with Java 11 compatibility and strict warnings (`--release 11 -Xlint:all -Werror`). The six test suites cover task validation, persistence, concurrency, RMI communication, client behavior, table editing, and timeouts. Tests run headlessly, use temporary storage, and require loopback networking.

For a desktop check, open two clients, add/edit/complete a task, and wait for automatic refresh. Restart the server, reconnect with **Refresh**, and confirm the saved tasks return.

## License

Copyright (c) 2026 Adam Zouari.

Licensed under the [PolyForm Strict License 1.0.0](LICENSE), the same license used by [EduMind](https://github.com/Adam-Zouari/EduMind/blob/main/LICENSE). See [LICENSE](LICENSE) for the full terms.
