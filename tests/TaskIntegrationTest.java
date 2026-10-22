import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Real RMI and server lifecycle checks, without a test framework or fixed ports. */
public final class TaskIntegrationTest {
    private static final String HOST = "127.0.0.1";
    private static final String FAILURE_PROBE = "--expect-startup-failure";

    private TaskIntegrationTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && FAILURE_PROBE.equals(args[0])) {
            probeStartupFailure(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
            return;
        }
        testInvalidConfiguration();
        testTwoClientsAndShutdown();
        testRegistryPortConflict();
        testServicePortConflict();
        testServerCommandLine();
        System.out.println("TaskIntegrationTest: all checks passed.");
    }

    private static void testInvalidConfiguration() throws Exception {
        for (int port : new int[] {-1, 0, 65536}) {
            expect(IllegalArgumentException.class, () -> TaskTransport.validatePort(port));
            expect(IllegalArgumentException.class, () -> TaskTransport.connect(HOST, port));
            expect(IllegalArgumentException.class, () -> TaskServer.start(HOST, port, 1100));
            expect(IllegalArgumentException.class, () -> TaskServer.start(HOST, 1099, port));
        }
        for (String value : new String[] {null, "", "abc", "1.5", "65536", "-1", "0"}) {
            expect(IllegalArgumentException.class, () -> TaskTransport.parsePort(value));
        }
        check(TaskTransport.parsePort("65535") == 65535, "Maximum legal port should be accepted.");
        check(TaskTransport.parsePort("1") == 1, "Minimum legal port should be accepted.");
        expect(IllegalArgumentException.class, () -> TaskServer.start(HOST, 1099, 1099));
        for (String host : new String[] {null, "", " \t"}) {
            expect(IllegalArgumentException.class, () -> TaskServer.start(host, 1099, 1100));
            expect(IllegalArgumentException.class, () -> TaskTransport.connect(host, 1099));
        }
        expect(IllegalArgumentException.class, () -> TaskServer.start("0.0.0.0", 1099, 1100));
        expect(IllegalArgumentException.class, () -> TaskServer.start("224.0.0.1", 1099, 1100));
        check(HOST.equals(TaskTransport.validateHost("  " + HOST + "  ")),
                "Host validation should strip surrounding whitespace.");
    }

    private static void testTwoClientsAndShutdown() throws Exception {
        try (PortReservation ports = new PortReservation()) {
            ports.releaseRegistry();
            ports.releaseService();
            TaskServer server = TaskServer.start(HOST, ports.registryPort, ports.servicePort);
            TaskManager first = null;
            Registry registry = registryAt(ports.registryPort);
            try {
                check(Arrays.asList(registry.list()).contains(TaskTransport.BINDING_NAME),
                        "The registry should expose the TaskManager binding.");
                first = TaskTransport.connect(HOST, ports.registryPort);
                TaskManager second = TaskTransport.connect("  " + HOST + "  ", ports.registryPort);
                check(!(first instanceof TaskManagerImpl), "Calls must use a remote stub.");
                first.addTask("  Shared task\t");
                check(second.getAllTasks().equals(Arrays.asList("Shared task")),
                        "A second client should observe normalized tasks from the first.");
                ArrayList<String> snapshot = first.getAllTasks();
                snapshot.clear();
                snapshot.add("Client-only task");
                check(second.getAllTasks().equals(Arrays.asList("Shared task")),
                        "Mutating a deserialized snapshot must not alter server state.");
                second.addTask("Other task");
                first.addTask("Shared task");
                second.removeTask(" Shared task ");
                check(first.getAllTasks().equals(Arrays.asList("Other task", "Shared task")),
                        "RMI removal should normalize input and remove the first duplicate.");
                TaskManager connected = first;
                expect(IllegalArgumentException.class, () -> connected.addTask(null));
                expect(IllegalArgumentException.class, () -> connected.addTask(" \t"));
                expect(IllegalArgumentException.class, () -> connected.removeTask("\n"));
                expect(IllegalArgumentException.class,
                        () -> connected.addTask("x".repeat(TaskManagerImpl.MAX_TASK_LENGTH + 1)));
                check(second.getAllTasks().size() == 2,
                        "Rejected remote operations must leave the list unchanged.");
                first.clearTasks();
                check(second.getAllTasks().isEmpty(), "A remote clear should be visible to every client.");
                testConcurrentRemoteClients(first, second);
            } finally {
                server.close();
                server.close();
            }
            TaskManager closedService = first;
            check(closedService != null, "The service should have connected before shutdown.");
            expect(RemoteException.class, closedService::getAllTasks);
            expect(RemoteException.class, registry::list);
            TaskServer restarted = TaskServer.start(HOST, ports.registryPort, ports.servicePort);
            try {
                check(TaskTransport.connect(HOST, ports.registryPort).getAllTasks().isEmpty(),
                        "Closing should allow a fresh, empty service on the same ports.");
            } finally {
                restarted.close();
            }
        }
    }

    private static void testConcurrentRemoteClients(TaskManager first, TaskManager second) throws Exception {
        int writerCount = 4;
        int tasksPerWriter = 100;
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
                        client.addTask("Remote " + writerId + ":" + index);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
            ArrayList<String> tasks = second.getAllTasks();
            check(tasks.size() == writerCount * tasksPerWriter
                            && new HashSet<>(tasks).size() == tasks.size(),
                    "Concurrent remote calls must retain every distinct task.");
        } finally {
            start.countDown();
            workers.shutdownNow();
            check(workers.awaitTermination(10, TimeUnit.SECONDS), "RMI test workers should terminate.");
        }
    }

    private static void testRegistryPortConflict() throws Exception {
        try (PortReservation ports = new PortReservation()) {
            ports.releaseService();
            verifyFailedStartupProcessExits(ports.registryPort, ports.servicePort);
            expect(IOException.class, () -> TaskServer.start(HOST, ports.registryPort, ports.servicePort));
            ports.releaseRegistry();
            TaskServer recovered = TaskServer.start(HOST, ports.registryPort, ports.servicePort);
            try {
                check(TaskTransport.connect(HOST, ports.registryPort).getAllTasks().isEmpty(),
                        "A registry-port conflict must permit a subsequent clean startup.");
            } finally {
                recovered.close();
            }
        }
    }

    private static void testServicePortConflict() throws Exception {
        try (PortReservation ports = new PortReservation()) {
            ports.releaseRegistry();
            verifyFailedStartupProcessExits(ports.registryPort, ports.servicePort);
            expect(IOException.class, () -> TaskServer.start(HOST, ports.registryPort, ports.servicePort));
            expect(RemoteException.class, () -> registryAt(ports.registryPort).list());
            ports.releaseService();
            TaskServer recovered = TaskServer.start(HOST, ports.registryPort, ports.servicePort);
            try {
                TaskManager client = TaskTransport.connect(HOST, ports.registryPort);
                client.addTask("Recovered");
                check(client.getAllTasks().equals(Arrays.asList("Recovered")),
                        "A service-port conflict must unexport its partial registry and permit restart.");
            } finally {
                recovered.close();
            }
        }
    }

    private static Registry registryAt(int port) throws RemoteException {
        return LocateRegistry.getRegistry(HOST, port, TaskTransport.CLIENT_SOCKET_FACTORY);
    }

    /** A leaked export keeps a failed-startup JVM alive, so check cleanup in a child process. */
    private static void verifyFailedStartupProcessExits(int registryPort, int servicePort) throws Exception {
        ProcessResult result = runJavaMain(TaskIntegrationTest.class.getName(), FAILURE_PROBE,
                Integer.toString(registryPort), Integer.toString(servicePort));
        check(result.exitCode == 0 && result.output.contains("Expected startup failure completed."),
                "Failed-startup child process should exit cleanly: " + result.output);
    }

    private static void testServerCommandLine() throws Exception {
        ProcessResult help = runJavaMain(TaskServer.class.getName(), "--help");
        check(help.exitCode == 0 && help.output.contains("Usage:") && help.output.contains("127.0.0.1"),
                "Server --help should print usage and defaults and exit successfully: " + help.output);
        ProcessResult shortHelp = runJavaMain(TaskServer.class.getName(), "-h");
        check(shortHelp.exitCode == 0 && shortHelp.output.contains("Usage:"),
                "Server -h should print usage and exit successfully: " + shortHelp.output);
        for (String[] arguments : new String[][] {
                {HOST, "0"}, {HOST, "not-a-port"}, {HOST, "1099", "1099"},
                {HOST, "1099", "1100", "extra"}}) {
            ProcessResult invalid = runJavaMain(TaskServer.class.getName(), arguments);
            check(invalid.exitCode == 2 && invalid.output.contains("Invalid configuration:")
                            && invalid.output.contains("Usage:"),
                    "Invalid server arguments should exit with status 2: " + invalid.output);
        }
        try (PortReservation ports = new PortReservation()) {
            ports.releaseService();
            ProcessResult conflict = runJavaMain(TaskServer.class.getName(), HOST,
                    Integer.toString(ports.registryPort), Integer.toString(ports.servicePort));
            check(conflict.exitCode == 1 && conflict.output.contains("Could not start TaskManager:"),
                    "An occupied registry port should make server main exit with status 1: " + conflict.output);
        }
        try (PortReservation ports = new PortReservation()) {
            ports.releaseRegistry();
            ProcessResult conflict = runJavaMain(TaskServer.class.getName(), HOST,
                    Integer.toString(ports.registryPort), Integer.toString(ports.servicePort));
            check(conflict.exitCode == 1 && conflict.output.contains("Could not start TaskManager:"),
                    "An occupied service port should make server main exit with status 1: " + conflict.output);
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

    private static void probeStartupFailure(int registryPort, int servicePort) throws Exception {
        TaskServer unexpected;
        try {
            unexpected = TaskServer.start(HOST, registryPort, servicePort);
        } catch (IOException expected) {
            System.out.println("Expected startup failure completed.");
            return;
        }
        unexpected.close();
        throw new AssertionError("Startup should fail while a required port is occupied.");
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
