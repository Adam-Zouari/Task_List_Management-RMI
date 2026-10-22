import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Real RMI and server lifecycle checks, without a test framework or fixed ports. */
public final class TaskIntegrationTest {
    private static final String HOST = "127.0.0.1";
    private static final String FAILURE_PROBE = "--expect-startup-failure";

    private TaskIntegrationTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 4 && FAILURE_PROBE.equals(args[0])) {
            probeStartupFailure(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Paths.get(args[3]));
            return;
        }
        testInvalidConfiguration();
        testTwoClientsAndShutdown();
        testRegistryPortConflict();
        testServicePortConflict();
        testLockedDataFile();
        testCorruptDataFile();
        testServerCommandLine();
        System.out.println("TaskIntegrationTest: all checks passed.");
    }

    private static void testInvalidConfiguration() throws Exception {
        try (TestData data = new TestData()) {
            for (int port : new int[] {-1, 0, 65536}) {
                expect(IllegalArgumentException.class, () -> TaskTransport.validatePort(port));
                expect(IllegalArgumentException.class, () -> TaskTransport.connect(HOST, port));
                expect(IllegalArgumentException.class, () -> TaskServer.start(HOST, port, 1100, data.file));
                expect(IllegalArgumentException.class, () -> TaskServer.start(HOST, 1099, port, data.file));
            }
            for (String value : new String[] {null, "", "abc", "1.5", "65536", "-1", "0"}) {
                expect(IllegalArgumentException.class, () -> TaskTransport.parsePort(value));
            }
            check(TaskTransport.parsePort("65535") == 65535, "Maximum legal port should be accepted.");
            check(TaskTransport.parsePort("1") == 1, "Minimum legal port should be accepted.");
            expect(IllegalArgumentException.class, () -> TaskServer.start(HOST, 1099, 1099, data.file));
            for (String host : new String[] {null, "", " \t"}) {
                expect(IllegalArgumentException.class, () -> TaskServer.start(host, 1099, 1100, data.file));
                expect(IllegalArgumentException.class, () -> TaskTransport.connect(host, 1099));
            }
            expect(IllegalArgumentException.class, () -> TaskServer.start("0.0.0.0", 1099, 1100, data.file));
            expect(IllegalArgumentException.class, () -> TaskServer.start("224.0.0.1", 1099, 1100, data.file));
            check(HOST.equals(TaskTransport.validateHost("  " + HOST + "  ")),
                    "Host validation should strip surrounding whitespace.");
        }
    }

    private static void testTwoClientsAndShutdown() throws Exception {
        try (TestData data = new TestData(); PortReservation ports = new PortReservation()) {
            ports.releaseRegistry();
            ports.releaseService();
            TaskServer server = TaskServer.start(HOST, ports.registryPort, ports.servicePort, data.file);
            TaskManager first = null;
            ArrayList<Task> saved = null;
            Registry registry = registryAt(ports.registryPort);
            try {
                check(Arrays.asList(registry.list()).contains(TaskTransport.BINDING_NAME),
                        "The registry should expose the TaskManager binding.");
                first = TaskTransport.connect(HOST, ports.registryPort);
                TaskManager second = TaskTransport.connect("  " + HOST + "  ", ports.registryPort);
                check(!(first instanceof TaskManagerImpl), "Calls must use a remote stub.");
                LocalDate originalDueDate = LocalDate.of(2030, 1, 15);
                first.addTask("  Shared task\t", originalDueDate);
                Task original = second.getAllTasks().get(0);
                checkTask(original, original.getId(), "Shared task", false, originalDueDate);
                ArrayList<Task> snapshot = first.getAllTasks();
                snapshot.clear();
                check(second.getAllTasks().size() == 1,
                        "Mutating a deserialized snapshot must not alter server state.");
                second.addTask("Other task", null);
                first.addTask("Shared task", originalDueDate);
                ArrayList<Task> duplicates = first.getAllTasks();
                Task other = duplicates.get(1);
                Task duplicate = duplicates.get(2);
                check(!original.getId().equals(duplicate.getId()),
                        "Duplicate descriptions must receive distinct stable identifiers.");
                second.removeTask(original.getId());
                ArrayList<Task> remaining = first.getAllTasks();
                check(remaining.size() == 2 && remaining.get(0).getId().equals(other.getId())
                                && remaining.get(1).getId().equals(duplicate.getId()),
                        "Removing a task by ID must preserve the other duplicate and insertion order.");
                LocalDate editedDueDate = LocalDate.of(2031, 7, 23);
                first.updateTask(duplicate.getId(), "  Edited task\t", editedDueDate);
                second.setTaskCompleted(duplicate.getId(), true);
                checkTask(first.getAllTasks().get(1), duplicate.getId(), "Edited task", true, editedDueDate);
                checkTask(duplicate, duplicate.getId(), "Shared task", false, originalDueDate);
                first.updateTask(duplicate.getId(), "Edited task", null);
                checkTask(second.getAllTasks().get(1), duplicate.getId(), "Edited task", true, null);
                second.setTaskCompleted(duplicate.getId(), false);
                checkTask(first.getAllTasks().get(1), duplicate.getId(), "Edited task", false, null);
                TaskManager connected = first;
                expect(IllegalArgumentException.class, () -> connected.addTask(null, null));
                expect(IllegalArgumentException.class, () -> connected.addTask(" \t", originalDueDate));
                expect(IllegalArgumentException.class, () -> connected.removeTask("\n"));
                expect(IllegalArgumentException.class,
                        () -> connected.addTask("x".repeat(1001), null));
                expect(IllegalArgumentException.class, () -> connected.addTask("Unpaired \uD800 text", null));
                expect(IllegalArgumentException.class,
                        () -> connected.updateTask(duplicate.getId(), " \t", editedDueDate));
                expect(IllegalArgumentException.class, () -> connected.setTaskCompleted(null, true));
                check(second.getAllTasks().size() == 2,
                        "Rejected remote operations must leave the list unchanged.");
                checkTask(second.getAllTasks().get(1), duplicate.getId(), "Edited task", false, null);
                first.clearTasks();
                check(second.getAllTasks().isEmpty(), "A remote clear should be visible to every client.");
                testConcurrentRemoteClients(first, second);
                first.clearTasks();
                first.addTask("Persists across restart\t\u00e9\nsecond line", editedDueDate);
                Task persistent = first.getAllTasks().get(0);
                second.setTaskCompleted(persistent.getId(), true);
                second.addTask("No due date", null);
                saved = first.getAllTasks();
            } finally {
                server.close();
                server.close();
            }
            TaskManager closedService = first;
            check(closedService != null, "The service should have connected before shutdown.");
            expect(RemoteException.class, closedService::getAllTasks);
            expect(RemoteException.class, registry::list);
            check(saved != null && saved.size() == 2, "Tasks should have been saved before shutdown.");
            TaskServer restarted = TaskServer.start(HOST, ports.registryPort, ports.servicePort, data.file);
            try {
                TaskManager restored = TaskTransport.connect(HOST, ports.registryPort);
                checkSameTasks(restored.getAllTasks(), saved);
                restored.clearTasks();
            } finally {
                restarted.close();
            }
            try (TaskServer cleared = TaskServer.start(HOST, ports.registryPort, ports.servicePort, data.file)) {
                check(cleared != null && TaskTransport.connect(HOST, ports.registryPort).getAllTasks().isEmpty(),
                        "Clearing tasks must persist across server restarts.");
            }
            Path freshFile = data.directory.resolve("fresh.tsv");
            try (TaskServer fresh = TaskServer.start(HOST, ports.registryPort, ports.servicePort, freshFile)) {
                check(fresh != null && TaskTransport.connect(HOST, ports.registryPort).getAllTasks().isEmpty(),
                        "Starting with a different data file must create an empty service.");
            }
        }
    }

    private static void testConcurrentRemoteClients(TaskManager first, TaskManager second) throws Exception {
        int writerCount = 4;
        int tasksPerWriter = 30;
        ExecutorService workers = Executors.newFixedThreadPool(writerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int writer = 0; writer < writerCount; writer++) {
                final int writerId = writer;
                final TaskManager client = writer % 2 == 0 ? first : second;
                results.add(workers.submit(() -> {
                    start.await();
                    for (int index = 0; index < tasksPerWriter; index++) {
                        client.addTask("Remote " + writerId + ":" + index, null);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
            ArrayList<Task> tasks = second.getAllTasks();
            HashSet<String> identifiers = new HashSet<>();
            HashSet<String> descriptions = new HashSet<>();
            for (Task task : tasks) {
                identifiers.add(task.getId());
                descriptions.add(task.getDescription());
                check(!task.isCompleted() && task.getDueDate() == null,
                        "New remote tasks should be pending without a due date.");
            }
            check(tasks.size() == writerCount * tasksPerWriter
                            && identifiers.size() == tasks.size() && descriptions.size() == tasks.size(),
                    "Concurrent remote calls must retain every distinct task.");
        } finally {
            start.countDown();
            workers.shutdownNow();
            check(workers.awaitTermination(10, TimeUnit.SECONDS), "RMI test workers should terminate.");
        }
    }

    private static void testRegistryPortConflict() throws Exception {
        try (TestData data = new TestData(); PortReservation ports = new PortReservation()) {
            ports.releaseService();
            verifyFailedStartupProcessExits(ports.registryPort, ports.servicePort, data.file);
            expect(IOException.class, () -> TaskServer.start(HOST, ports.registryPort, ports.servicePort, data.file));
            ports.releaseRegistry();
            TaskServer recovered = TaskServer.start(HOST, ports.registryPort, ports.servicePort, data.file);
            try {
                check(TaskTransport.connect(HOST, ports.registryPort).getAllTasks().isEmpty(),
                        "A registry-port conflict must permit a subsequent clean startup.");
            } finally {
                recovered.close();
            }
        }
    }

    private static void testServicePortConflict() throws Exception {
        try (TestData data = new TestData(); PortReservation ports = new PortReservation()) {
            ports.releaseRegistry();
            verifyFailedStartupProcessExits(ports.registryPort, ports.servicePort, data.file);
            expect(IOException.class, () -> TaskServer.start(HOST, ports.registryPort, ports.servicePort, data.file));
            expect(RemoteException.class, () -> registryAt(ports.registryPort).list());
            ports.releaseService();
            TaskServer recovered = TaskServer.start(HOST, ports.registryPort, ports.servicePort, data.file);
            try {
                TaskManager client = TaskTransport.connect(HOST, ports.registryPort);
                client.addTask("Recovered", null);
                check(client.getAllTasks().size() == 1
                                && "Recovered".equals(client.getAllTasks().get(0).getDescription()),
                        "A service-port conflict must unexport its partial registry and permit restart.");
            } finally {
                recovered.close();
            }
        }
    }

    private static void testLockedDataFile() throws Exception {
        try (TestData data = new TestData(); PortReservation ownerPorts = new PortReservation();
                PortReservation contenderPorts = new PortReservation()) {
            ownerPorts.releaseRegistry();
            ownerPorts.releaseService();
            contenderPorts.releaseRegistry();
            contenderPorts.releaseService();
            TaskServer owner = TaskServer.start(HOST, ownerPorts.registryPort, ownerPorts.servicePort, data.file);
            try {
                verifyFailedStartupProcessExits(contenderPorts.registryPort, contenderPorts.servicePort, data.file);
                expect(IOException.class, () -> TaskServer.start(HOST, contenderPorts.registryPort,
                        contenderPorts.servicePort, data.file));
                expect(RemoteException.class, () -> registryAt(contenderPorts.registryPort).list());
                TaskManager client = TaskTransport.connect(HOST, ownerPorts.registryPort);
                client.addTask("Owner remains usable", LocalDate.of(2032, 8, 9));
            } finally {
                owner.close();
            }
            try (TaskServer recovered = TaskServer.start(HOST, contenderPorts.registryPort,
                    contenderPorts.servicePort, data.file)) {
                ArrayList<Task> tasks = TaskTransport.connect(HOST, contenderPorts.registryPort).getAllTasks();
                check(recovered != null && tasks.size() == 1,
                        "Closing the owner must release its file lock and failed startup must release its endpoints.");
                checkTask(tasks.get(0), tasks.get(0).getId(), "Owner remains usable", false,
                        LocalDate.of(2032, 8, 9));
            }
        }
    }

    private static void testCorruptDataFile() throws Exception {
        try (TestData data = new TestData(); PortReservation ports = new PortReservation()) {
            ports.releaseRegistry();
            ports.releaseService();
            for (String corrupt : new String[] {
                    "This is not a valid task data file.\n",
                    TaskStore.HEADER + "\n00000000-0000-0000-0000-000000000001\tfalse\t2030-02-30\tVGFzaw==\n"}) {
                Files.writeString(data.file, corrupt, StandardCharsets.UTF_8);
                verifyFailedStartupProcessExits(ports.registryPort, ports.servicePort, data.file);
                expect(IOException.class,
                        () -> TaskServer.start(HOST, ports.registryPort, ports.servicePort, data.file));
                expect(RemoteException.class, () -> registryAt(ports.registryPort).list());
                ProcessResult main = runJavaMain(TaskServer.class.getName(), HOST,
                        Integer.toString(ports.registryPort), Integer.toString(ports.servicePort), data.file.toString());
                check(main.exitCode == 1 && main.output.contains("Could not start TaskManager:"),
                        "Corrupt storage should produce a clear server startup error: " + main.output);
                check(corrupt.equals(Files.readString(data.file, StandardCharsets.UTF_8)),
                        "Failed startup must preserve a corrupt data file for recovery.");
            }
            Files.delete(data.file);
            try (TaskServer recovered = TaskServer.start(HOST, ports.registryPort, ports.servicePort, data.file)) {
                check(recovered != null && TaskTransport.connect(HOST, ports.registryPort).getAllTasks().isEmpty(),
                        "Correcting corrupt data must allow reuse of both ports and the released file lock.");
            }
        }
    }

    private static Registry registryAt(int port) throws RemoteException {
        return LocateRegistry.getRegistry(HOST, port, TaskTransport.CLIENT_SOCKET_FACTORY);
    }

    /** A leaked export keeps a failed-startup JVM alive, so check cleanup in a child process. */
    private static void verifyFailedStartupProcessExits(int registryPort, int servicePort, Path dataFile)
            throws Exception {
        ProcessResult result = runJavaMain(TaskIntegrationTest.class.getName(), FAILURE_PROBE,
                Integer.toString(registryPort), Integer.toString(servicePort), dataFile.toString());
        check(result.exitCode == 0 && result.output.contains("Expected startup failure completed."),
                "Failed-startup child process should exit cleanly: " + result.output);
    }

    private static void testServerCommandLine() throws Exception {
        try (TestData data = new TestData()) {
            ProcessResult help = runJavaMain(TaskServer.class.getName(), "--help");
            check(help.exitCode == 0 && help.output.contains("Usage:") && help.output.contains("127.0.0.1"),
                    "Server --help should print usage and defaults and exit successfully: " + help.output);
            ProcessResult shortHelp = runJavaMain(TaskServer.class.getName(), "-h");
            check(shortHelp.exitCode == 0 && shortHelp.output.contains("Usage:"),
                    "Server -h should print usage and exit successfully: " + shortHelp.output);
            for (String[] arguments : new String[][] {
                    {HOST, "0", "1100", data.file.toString()},
                    {HOST, "not-a-port", "1100", data.file.toString()},
                    {HOST, "1099", "1099", data.file.toString()},
                    {HOST, "1099", "1100", data.file.toString(), "extra"}}) {
                ProcessResult invalid = runJavaMain(TaskServer.class.getName(), arguments);
                check(invalid.exitCode == 2 && invalid.output.contains("Invalid configuration:")
                                && invalid.output.contains("Usage:"),
                        "Invalid server arguments should exit with status 2: " + invalid.output);
            }
            try (PortReservation ports = new PortReservation()) {
                ports.releaseService();
                ProcessResult conflict = runJavaMain(TaskServer.class.getName(), HOST,
                        Integer.toString(ports.registryPort), Integer.toString(ports.servicePort), data.file.toString());
                check(conflict.exitCode == 1 && conflict.output.contains("Could not start TaskManager:"),
                        "An occupied registry port should make server main exit with status 1: " + conflict.output);
            }
            try (PortReservation ports = new PortReservation()) {
                ports.releaseRegistry();
                ProcessResult conflict = runJavaMain(TaskServer.class.getName(), HOST,
                        Integer.toString(ports.registryPort), Integer.toString(ports.servicePort), data.file.toString());
                check(conflict.exitCode == 1 && conflict.output.contains("Could not start TaskManager:"),
                        "An occupied service port should make server main exit with status 1: " + conflict.output);
            }
        }
    }

    private static ProcessResult runJavaMain(String mainClass, String... arguments) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String java = Paths.get(System.getProperty("java.home"), "bin", executable).toString();
        List<String> command = new ArrayList<>(Arrays.asList(java, "-cp",
                System.getProperty("java.class.path"), mainClass));
        command.addAll(Arrays.asList(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                throw new AssertionError("Java subprocess did not exit; an RMI endpoint may have leaked: "
                        + command);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new ProcessResult(process.exitValue(), output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                check(process.waitFor(5, TimeUnit.SECONDS), "Startup probe should terminate.");
            }
        }
    }

    private static void probeStartupFailure(int registryPort, int servicePort, Path dataFile) throws Exception {
        TaskServer unexpected;
        try {
            unexpected = TaskServer.start(HOST, registryPort, servicePort, dataFile);
        } catch (IOException expected) {
            System.out.println("Expected startup failure completed.");
            return;
        }
        unexpected.close();
        throw new AssertionError("Startup should fail while a required port or data file is unavailable.");
    }

    private static void checkSameTasks(List<Task> actual, List<Task> expected) {
        check(actual.size() == expected.size(), "Restarted service must restore the saved task count.");
        for (int index = 0; index < expected.size(); index++) {
            Task task = expected.get(index);
            checkTask(actual.get(index), task.getId(), task.getDescription(), task.isCompleted(), task.getDueDate());
        }
    }

    private static void checkTask(Task actual, String id, String description, boolean completed, LocalDate dueDate) {
        check(actual.getId().equals(id) && actual.getDescription().equals(description)
                        && actual.isCompleted() == completed && Objects.equals(actual.getDueDate(), dueDate),
                "A task's identifier, normalized description, completion, and due date must round-trip intact.");
    }

    private static void expect(Class<? extends Throwable> expected, CheckedAction action) throws Exception {
        try {
            action.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) {
                return;
            }
            throw new AssertionError("Expected " + expected.getSimpleName() + ", got " + failure, failure);
        }
        throw new AssertionError("Expected " + expected.getSimpleName() + " to be thrown.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    private static final class ProcessResult {
        private final int exitCode;
        private final String output;

        private ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static final class TestData implements AutoCloseable {
        private final Path directory;
        private final Path file;

        private TestData() throws IOException {
            directory = Files.createTempDirectory("task-rmi-integration-");
            file = directory.resolve("tasks.tsv");
        }

        @Override
        public void close() throws IOException {
            Path[] paths;
            try (Stream<Path> entries = Files.walk(directory)) {
                paths = entries.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
            }
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static final class PortReservation implements AutoCloseable {
        private final ServerSocket registrySocket;
        private final ServerSocket serviceSocket;
        private final int registryPort;
        private final int servicePort;

        private PortReservation() throws IOException {
            InetAddress address = InetAddress.getByName(HOST);
            registrySocket = new ServerSocket(0, 50, address);
            try {
                serviceSocket = new ServerSocket(0, 50, address);
            } catch (IOException failure) {
                registrySocket.close();
                throw failure;
            }
            registryPort = registrySocket.getLocalPort();
            servicePort = serviceSocket.getLocalPort();
        }

        private void releaseRegistry() throws IOException {
            registrySocket.close();
        }

        private void releaseService() throws IOException {
            serviceSocket.close();
        }

        @Override
        public void close() throws IOException {
            try {
                registrySocket.close();
            } finally {
                serviceSocket.close();
            }
        }
    }
}
