import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.rmi.RemoteException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Persistence checks use isolated temporary directories and never the application's data file. */
public final class TaskStoreTest {
    private TaskStoreTest() { }

    public static void main(String[] args) throws Exception {
        testRoundTrip();
        testPersistentServiceRestart();
        testCorruptFiles();
        testLockingAndExportFailure();
        testFailedSaveLeavesMemoryUnchanged();
        System.out.println("TaskStoreTest: all checks passed.");
    }

    private static void testRoundTrip() throws Exception {
        try (TemporaryDirectory directory = new TemporaryDirectory()) {
            Path file = directory.root.resolve("nested/tasks.tsv");
            String text = "Caf\u00e9 \uD83D\uDCD6\twith tab\nand newline";
            List<Task> expected = Arrays.asList(
                    new Task(UUID.randomUUID().toString(), text, true, LocalDate.of(2030, 2, 28)),
                    new Task(UUID.randomUUID().toString(), text, false, null));
            try (TaskStore store = new TaskStore(file)) {
                check(store.load().isEmpty(), "A missing data file should represent an empty list.");
                store.save(expected);
                check(store.load().equals(expected), "All task fields and Unicode must round-trip.");
                byte[] original = Files.readAllBytes(file);
                expect(IllegalArgumentException.class, () -> store.save(Arrays.asList(expected.get(0), expected.get(0))));
                expect(IllegalArgumentException.class, () -> store.save(Arrays.asList((Task) null)));
                expect(IllegalArgumentException.class, () -> store.save(null));
                check(Arrays.equals(original, Files.readAllBytes(file)), "Rejected saves must leave the file unchanged.");
            }
            TaskStore reopened = new TaskStore(file);
            try {
                check(reopened.load().equals(expected), "A newly opened store should restore all task values and order.");
                reopened.save(Collections.emptyList());
                check(reopened.load().isEmpty(), "An empty list should persist normally.");
            } finally {
                reopened.close();
                reopened.close();
            }
            expect(IOException.class, reopened::load);
            expect(IOException.class, () -> reopened.save(expected));
            try (TaskStore finalStore = new TaskStore(file)) {
                check(finalStore.load().isEmpty(), "Clearing must survive another restart.");
            }
        }
    }

    private static void testPersistentServiceRestart() throws Exception {
        try (TemporaryDirectory directory = new TemporaryDirectory()) {
            Path file = directory.root.resolve("tasks.tsv");
            ArrayList<Task> expected;
            TaskManagerImpl service = persistentService(file);
            try {
                service.addTask("First", LocalDate.of(2031, 4, 5));
                service.addTask("First", null);
                String id = service.getAllTasks().get(0).getId();
                service.setTaskCompleted(id, true);
                service.updateTask(id, "Edited \u00e9\tline\nsecond line", LocalDate.of(2032, 6, 7));
                expected = service.getAllTasks();
            } finally { service.close(); }
            service = persistentService(file);
            try {
                check(service.getAllTasks().equals(expected), "Server restart must retain IDs, edits, completion, dates, and order.");
                service.removeTask(expected.get(1).getId());
                expected.remove(1);
            } finally { service.close(); }
            service = persistentService(file);
            try {
                check(service.getAllTasks().equals(expected), "UUID-targeted removal must persist.");
                service.clearTasks();
            } finally { service.close(); }
            service = persistentService(file);
            try { check(service.getAllTasks().isEmpty(), "Clear must survive a service restart."); }
            finally { service.close(); }
        }
    }

    private static void testCorruptFiles() throws Exception {
        try (TemporaryDirectory directory = new TemporaryDirectory()) {
            String id = UUID.randomUUID().toString();
            String goodRow = row(id, "false", "", encoded("Original"));
            String[] contents = {
                "", "WRONG_HEADER\n", TaskStore.HEADER + "\nmissing columns\n",
                TaskStore.HEADER + "\n" + row(id, "TRUE", "", encoded("Task")),
                TaskStore.HEADER + "\n" + row(id, "false", "2030-02-30", encoded("Task")),
                TaskStore.HEADER + "\n" + row("not-a-uuid", "false", "", encoded("Task")),
                TaskStore.HEADER + "\n" + row(id, "false", "", "%%%"),
                TaskStore.HEADER + "\n" + row(id, "false", "", "wA=="),
                TaskStore.HEADER + "\n" + row(id, "false", "", encoded("  ")),
                TaskStore.HEADER + "\n" + row(id, "false", "", encoded(" padded ")),
                TaskStore.HEADER + "\n" + row(id, "false", "", encoded("x".repeat(Task.MAX_DESCRIPTION_LENGTH + 1))),
                TaskStore.HEADER + "\n" + goodRow + goodRow
            };
            for (int index = 0; index < contents.length; index++) {
                Path file = directory.root.resolve("corrupt-" + index + ".tsv");
                byte[] original = contents[index].getBytes(StandardCharsets.UTF_8);
                Files.write(file, original);
                expect(IOException.class, () -> {
                    TaskManagerImpl unexpected = persistentService(file);
                    unexpected.close();
                });
                check(Arrays.equals(original, Files.readAllBytes(file)), "Corrupt storage must never be overwritten.");
                try (TaskStore unlocked = new TaskStore(file)) {
                    expect(IOException.class, unlocked::load);
                }
            }
            Path badUtf8 = directory.root.resolve("malformed-utf8.tsv");
            Files.write(badUtf8, new byte[] {(byte) 0xC0, (byte) 0x80});
            try (TaskStore store = new TaskStore(badUtf8)) { expect(IOException.class, store::load); }

            Path oversized = directory.root.resolve("oversized.tsv");
            try (FileChannel channel = FileChannel.open(oversized, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                channel.position(64L * 1024 * 1024);
                channel.write(ByteBuffer.wrap(new byte[] {1}));
            }
            try (TaskStore store = new TaskStore(oversized)) { expect(IOException.class, store::load); }

            Path tooMany = directory.root.resolve("too-many.tsv");
            StringBuilder data = new StringBuilder(TaskStore.HEADER).append('\n');
            for (int index = 0; index <= TaskManagerImpl.MAX_TASKS; index++) {
                data.append(row(UUID.randomUUID().toString(), "false", "", encoded("Task")));
            }
            Files.write(tooMany, data.toString().getBytes(StandardCharsets.UTF_8));
            try (TaskStore store = new TaskStore(tooMany)) { expect(IOException.class, store::load); }
        }
    }

    private static void testLockingAndExportFailure() throws Exception {
        try (TemporaryDirectory directory = new TemporaryDirectory()) {
            Path file = directory.root.resolve("tasks.tsv");
            try (TaskStore first = new TaskStore(file)) {
                check(first.load().isEmpty(), "The first store should own the file lock.");
                expect(IOException.class, () -> {
                    try (TaskStore unexpected = new TaskStore(file)) { unexpected.load(); }
                });
                expect(IOException.class, () -> {
                    TaskManagerImpl unexpected = persistentService(file);
                    unexpected.close();
                });
            }
            try (ServerSocket occupied = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
                expect(IOException.class, () -> {
                    TaskManagerImpl unexpected = new TaskManagerImpl(occupied.getLocalPort(),
                            TaskTransport.CLIENT_SOCKET_FACTORY,
                            new TaskTransport.BoundServerSocketFactory("127.0.0.1"), file);
                    unexpected.close();
                });
            }
            TaskManagerImpl recovered = persistentService(file);
            try {
                recovered.addTask("Recovered after export failure", null);
                check(recovered.getAllTasks().size() == 1, "Failed export must release its storage lock.");
            } finally { recovered.close(); }
        }
    }

    private static void testFailedSaveLeavesMemoryUnchanged() throws Exception {
        try (TemporaryDirectory directory = new TemporaryDirectory()) {
            Path file = directory.root.resolve("tasks.tsv");
            Path backup = directory.root.resolve("original.tsv");
            TaskManagerImpl service = persistentService(file);
            try {
                service.addTask("Original", LocalDate.of(2031, 2, 3));
                ArrayList<Task> original = service.getAllTasks();
                String id = original.get(0).getId();
                byte[] originalBytes = Files.readAllBytes(file);
                Files.move(file, backup);
                Files.createDirectory(file);
                Path blocker = file.resolve("blocker");
                Files.write(blocker, new byte[] {1});
                try {
                    CheckedAction[] writes = {
                        () -> service.addTask("Must not commit", null),
                        () -> service.updateTask(id, "Must not commit", null),
                        () -> service.setTaskCompleted(id, true),
                        () -> service.removeTask(id), service::clearTasks
                    };
                    for (CheckedAction write : writes) {
                        expect(RemoteException.class, write);
                        check(service.getAllTasks().equals(original), "Failed persistence must leave every in-memory field unchanged.");
                        check(Arrays.equals(originalBytes, Files.readAllBytes(backup)), "Failed saves must not alter the original data.");
                    }
                    try (Stream<Path> entries = Files.list(directory.root)) {
                        check(entries.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                                "Failed saves should remove their temporary files.");
                    }
                } finally {
                    Files.delete(blocker);
                    Files.delete(file);
                    Files.move(backup, file);
                }
                service.addTask("After recovery", null);
                check(service.getAllTasks().size() == 2, "Persistence should recover after the file path is restored.");
            } finally { service.close(); }
            TaskManagerImpl reopened = persistentService(file);
            try { check(reopened.getAllTasks().size() == 2, "Recovered state should persist normally."); }
            finally { reopened.close(); }
        }
    }

    private static TaskManagerImpl persistentService(Path file) throws IOException {
        return new TaskManagerImpl(0, TaskTransport.CLIENT_SOCKET_FACTORY,
                new TaskTransport.BoundServerSocketFactory("127.0.0.1"), file);
    }

    private static String row(String id, String completed, String due, String description) {
        return id + "\t" + completed + "\t" + due + "\t" + description + "\n";
    }

    private static String encoded(String description) {
        return Base64.getEncoder().encodeToString(description.getBytes(StandardCharsets.UTF_8));
    }

    private static void expect(Class<? extends Throwable> expected, CheckedAction action) throws Exception {
        try { action.run(); }
        catch (Throwable failure) {
            if (expected.isInstance(failure)) { return; }
            throw new AssertionError("Expected " + expected.getSimpleName() + ", got " + failure, failure);
        }
        throw new AssertionError("Expected " + expected.getSimpleName() + " to be thrown.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }

    @FunctionalInterface
    private interface CheckedAction { void run() throws Exception; }

    private static final class TemporaryDirectory implements AutoCloseable {
        private final Path root = Files.createTempDirectory("task-store-test-").toAbsolutePath().normalize();

        private TemporaryDirectory() throws IOException { }

        @Override
        public void close() throws IOException {
            Path allowed = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
            if (root.equals(allowed) || !root.startsWith(allowed)) {
                throw new IOException("Refusing to remove a path outside the test temporary directory.");
            }
            List<Path> paths;
            try (Stream<Path> entries = Files.walk(root)) {
                paths = entries.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            }
            for (Path path : paths) { Files.deleteIfExists(path); }
        }
    }
}
