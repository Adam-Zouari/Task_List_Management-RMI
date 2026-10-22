import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Headless client regression checks. Run with java -cp out TaskClientTest. */
public final class TaskClientTest {
    private static final long WAIT_SECONDS = 10;

    private TaskClientTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        testBackgroundRequestsAndAuthoritativeSnapshots();
        testSlowRequestsKeepEventThreadResponsive();
        withExpectedErrors(() -> {
            testReconnectAfterFailureAndRestart();
            testUncertainMutationIsNeverRetried();
            testSnapshotFailureAfterSuccessfulMutation();
            testCloseFromCallbacks();
        });
        testValidationErrorsKeepConnection();
        testCloseSuppressesLateCallbacks();
        testActionsRequireEventThread();
        testCommandLine();
        System.out.println("TaskClientTest: all checks passed.");
    }

    private static void testBackgroundRequestsAndAuthoritativeSnapshots() throws Exception {
        FakeService service = new FakeService("Existing");
        AtomicInteger lookups = new AtomicInteger();
        Harness client = new Harness(() -> {
            checkBackgroundThread();
            lookups.incrementAndGet();
            return service;
        });
        try {
            onEventThread(() -> {
                check(!client.connection.add("Too early"), "Add must be disabled before connecting.");
                check(!client.connection.remove("Existing"), "Remove must be disabled before connecting.");
                check(!client.connection.clear(), "Clear must be disabled before connecting.");
                return null;
            });
            Request first = client.perform(client.connection::refresh);
            first.expectTasks("Existing");
            check(lookups.get() == 1, "Refresh must look up a service off the event thread.");

            service.calls.clear();
            service.afterAdd = () -> service.inject("Other client's task");
            client.perform(() -> client.connection.add("New")).expectTasks("Existing", "New", "Other client's task");
            check(service.calls.equals(Arrays.asList("add", "read")),
                    "Add must be followed by an authoritative full-list fetch.");

            service.calls.clear();
            service.afterRemove = () -> service.inject("Concurrent addition");
            client.perform(() -> client.connection.remove("Existing"))
                    .expectTasks("New", "Other client's task", "Concurrent addition");
            check(service.calls.equals(Arrays.asList("remove", "read")),
                    "Remove must be followed by a full-list fetch.");

            service.calls.clear();
            service.afterClear = () -> service.inject("Added after clear");
            client.perform(client.connection::clear).expectTasks("Added after clear");
            check(service.calls.equals(Arrays.asList("clear", "read")),
                    "Clear must fetch server state rather than assume the list is empty.");
            check(service.adds.get() == 1 && service.removes.get() == 1 && service.clears.get() == 1,
                    "Each accepted mutation must run exactly once.");
            client.perform(client.connection::refresh).expectTasks("Added after clear");
            check(lookups.get() == 2, "Even a connected refresh must look up a fresh stub.");
        } finally {
            client.close();
        }
    }

    private static void testSlowRequestsKeepEventThreadResponsive() throws Exception {
        FakeService service = new FakeService();
        CountDownLatch lookupEntered = new CountDownLatch(1);
        CountDownLatch releaseLookup = new CountDownLatch(1);
        CountDownLatch mutationEntered = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        AtomicInteger lookups = new AtomicInteger();
        Harness client = new Harness(() -> {
            checkBackgroundThread();
            lookups.incrementAndGet();
            lookupEntered.countDown();
            await(releaseLookup, "Release slow lookup");
            return service;
        });
        try {
            Request refresh = client.start(client.connection::refresh);
            await(lookupEntered, "The background lookup must start");
            assertEventThreadResponsive();
            assertBusyRejectsWork(client);
            check(lookups.get() == 1 && service.reads.get() == 0,
                    "An outstanding lookup must not allow another operation.");
            releaseLookup.countDown();
            client.await(refresh).expectTasks();

            service.beforeAdd = () -> {
                mutationEntered.countDown();
                awaitRemote(releaseMutation, "Release slow mutation");
            };
            Request addition = client.start(() -> client.connection.add("One request"));
            await(mutationEntered, "The background mutation must start");
            assertEventThreadResponsive();
            assertBusyRejectsWork(client);
            releaseMutation.countDown();
            client.await(addition).expectTasks("One request");
            check(service.adds.get() == 1 && service.reads.get() == 2 && lookups.get() == 1,
                    "Busy operations must not cause duplicate calls or extra lookups.");
        } finally {
            releaseLookup.countDown();
            releaseMutation.countDown();
            client.close();
        }
    }

    private static void testReconnectAfterFailureAndRestart() throws Exception {
        FakeService original = new FakeService("Old server");
        FakeService replacement = new FakeService("New server");
        AtomicInteger lookups = new AtomicInteger();
        AtomicReference<FakeService> current = new AtomicReference<>(original);
        Harness client = new Harness(() -> {
            checkBackgroundThread();
            if (lookups.incrementAndGet() == 1) {
                throw new RemoteException("Server not started yet");
            }
            return current.get();
        });
        try {
            client.perform(client.connection::refresh).expectFailure(false, "Refresh");
            assertDisconnectedRejectsMutations(client);
            client.perform(client.connection::refresh).expectTasks("Old server");
            original.beforeRemove = () -> {
                throw new RemoteException("Server stopped");
            };
            client.perform(() -> client.connection.remove("Old server")).expectFailure(false, "may already");
            assertDisconnectedRejectsMutations(client);
            current.set(replacement);
            client.perform(client.connection::refresh).expectTasks("New server");
            client.perform(() -> client.connection.add("Recovered")).expectTasks("New server", "Recovered");
            check(lookups.get() == 3, "Only explicit refreshes may look up replacement stubs.");
            check(original.removes.get() == 1 && original.adds.get() == 0 && replacement.adds.get() == 1,
                    "After recovery, mutations must use the replacement service.");
        } finally {
            client.close();
        }
    }

    private static void testUncertainMutationIsNeverRetried() throws Exception {
        FakeService service = new FakeService();
        AtomicInteger lookups = new AtomicInteger();
        Harness client = new Harness(() -> {
            checkBackgroundThread();
            lookups.incrementAndGet();
            return service;
        });
        try {
            client.perform(client.connection::refresh).expectTasks();
            service.afterAdd = () -> {
                throw new RemoteException("Applied, but the response was lost");
            };
            client.perform(() -> client.connection.add("Apply once")).expectFailure(false, "may already");
            check(service.snapshot().equals(Arrays.asList("Apply once")), "The fake server must have applied the task.");
            check(service.adds.get() == 1 && service.reads.get() == 1 && lookups.get() == 1,
                    "An uncertain mutation must never be retried or followed by an automatic reconnect.");
            assertDisconnectedRejectsMutations(client);
            service.afterAdd = () -> { };
            client.perform(client.connection::refresh).expectTasks("Apply once");
            check(service.adds.get() == 1 && lookups.get() == 2,
                    "Manual refresh must inspect state without repeating the previous mutation.");
        } finally {
            client.close();
        }
    }

    private static void testSnapshotFailureAfterSuccessfulMutation() throws Exception {
        FakeService service = new FakeService();
        Harness client = new Harness(() -> service);
        try {
            client.perform(client.connection::refresh).expectTasks();
            service.beforeRead = () -> {
                throw new RemoteException("Snapshot response failed");
            };
            client.perform(() -> client.connection.add("Committed")).expectFailure(false, "may already");
            check(service.adds.get() == 1 && service.reads.get() == 2,
                    "A successful mutation must attempt exactly one authoritative snapshot.");
            check(service.snapshot().equals(Arrays.asList("Committed")),
                    "A snapshot failure must not undo or retry the successful mutation.");
            assertDisconnectedRejectsMutations(client);
            service.beforeRead = () -> { };
            client.perform(client.connection::refresh).expectTasks("Committed");
            check(service.adds.get() == 1, "Recovery must not replay a mutation after a snapshot failure.");
        } finally {
            client.close();
        }
    }

    private static void testValidationErrorsKeepConnection() throws Exception {
        FakeService service = new FakeService();
        AtomicInteger lookups = new AtomicInteger();
        Harness client = new Harness(() -> {
            lookups.incrementAndGet();
            return service;
        });
        try {
            client.perform(client.connection::refresh).expectTasks();
            service.beforeAdd = () -> {
                throw new IllegalArgumentException("A task must not be blank");
            };
            client.perform(() -> client.connection.add(" ")).expectFailure(true, "Task rejected:");
            service.beforeAdd = () -> {
                throw new IllegalStateException("The task list is full");
            };
            client.perform(() -> client.connection.add("Overflow")).expectFailure(true, "Task rejected:");
            check(service.snapshot().isEmpty() && service.reads.get() == 1,
                    "Rejected mutations must not update state or fetch a misleading snapshot.");
            service.beforeAdd = () -> { };
            client.perform(() -> client.connection.add("Valid")).expectTasks("Valid");
            check(lookups.get() == 1, "Validation errors must retain the current working connection.");
        } finally {
            client.close();
        }
    }

    private static void testCloseSuppressesLateCallbacks() throws Exception {
        FakeService service = new FakeService();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        Harness client = new Harness(() -> {
            checkBackgroundThread();
            entered.countDown();
            try {
                awaitIgnoringInterrupts(release);
                return service;
            } finally {
                exited.countDown();
            }
        });
        try {
            client.start(client.connection::refresh);
            await(entered, "The in-flight lookup must start");
            int callbacksBeforeClose = client.listener.callbacks.get();
            client.close();
            onEventThread(() -> {
                check(!client.connection.refresh(), "A closed client must reject refresh.");
                check(!client.connection.add("Closed"), "A closed client must reject add.");
                check(!client.connection.remove("Closed"), "A closed client must reject remove.");
                check(!client.connection.clear(), "A closed client must reject clear.");
                return null;
            });
            release.countDown();
            await(exited, "The cancelled worker must leave the connector");
            // SwingWorker batches its done callbacks through a short Swing timer.
            CountDownLatch settled = new CountDownLatch(1);
            onEventThread(() -> {
                Timer timer = new Timer(150, event -> settled.countDown());
                timer.setRepeats(false);
                timer.start();
                return null;
            });
            await(settled, "Process pending SwingWorker completion callbacks");
            check(client.listener.callbacks.get() == callbacksBeforeClose,
                    "A closed connection must suppress all late success/error/finished callbacks.");
            client.listener.checkThreads();
        } finally {
            release.countDown();
            client.close();
        }
    }

    private static void testActionsRequireEventThread() throws Exception {
        Harness client = new Harness(FakeService::new);
        try {
            expectEventThreadFailure(client.connection::refresh);
            expectEventThreadFailure(() -> client.connection.add("Wrong thread"));
            expectEventThreadFailure(() -> client.connection.remove("Wrong thread"));
            expectEventThreadFailure(client.connection::clear);
            try {
                client.connection.close();
                throw new AssertionError("Close must require the event thread.");
            } catch (IllegalStateException expected) {
                check(expected.getMessage().contains("event thread"), "Close should explain the thread requirement.");
            }
        } finally {
            client.close();
        }
    }

    private static void testCloseFromCallbacks() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        Harness closeOnStart = new Harness(() -> {
            lookups.incrementAndGet();
            return new FakeService();
        });
        try {
            onEventThread(() -> {
                closeOnStart.listener.onStarted = closeOnStart.connection::close;
                return null;
            });
            closeOnStart.start(closeOnStart.connection::refresh);
            check(lookups.get() == 0, "Closing in started() must prevent the request from executing.");
        } finally {
            closeOnStart.close();
        }

        Harness closeOnFailure = new Harness(() -> {
            throw new RemoteException("Expected failure before the error callback closes the client");
        });
        CountDownLatch failedAndClosed = new CountDownLatch(1);
        try {
            onEventThread(() -> {
                closeOnFailure.listener.onFailed = () -> {
                    closeOnFailure.connection.close();
                    failedAndClosed.countDown();
                };
                return null;
            });
            closeOnFailure.start(closeOnFailure.connection::refresh);
            await(failedAndClosed, "The failure callback must close the connection");
            onEventThread(() -> {
                check(closeOnFailure.listener.callbacks.get() == 2,
                        "Closing in failed() must suppress the following finished() callback.");
                check(!closeOnFailure.connection.refresh(), "A callback-closed connection must reject new work.");
                return null;
            });
            closeOnFailure.listener.checkThreads();
        } finally {
            closeOnFailure.close();
        }

        Harness closeOnSuccess = new Harness(FakeService::new);
        CountDownLatch receivedAndClosed = new CountDownLatch(1);
        try {
            onEventThread(() -> {
                closeOnSuccess.listener.onReceived = () -> {
                    closeOnSuccess.connection.close();
                    receivedAndClosed.countDown();
                };
                return null;
            });
            closeOnSuccess.start(closeOnSuccess.connection::refresh);
            await(receivedAndClosed, "The success callback must close the connection");
            onEventThread(() -> {
                check(closeOnSuccess.listener.callbacks.get() == 2,
                        "Closing in tasksReceived() must suppress the following finished() callback.");
                return null;
            });
            closeOnSuccess.listener.checkThreads();
        } finally {
            closeOnSuccess.close();
        }
    }

    private static void testCommandLine() throws Exception {
        expectClientExit(0, "Usage:", "--help");
        expectClientExit(2, "Invalid configuration:", "127.0.0.1", "0");
        expectClientExit(2, "Invalid configuration:", "127.0.0.1", "not-a-port");
        expectClientExit(2, "Too many arguments", "127.0.0.1", "1099", "extra");
        expectClientExit(1, "needs a graphical desktop");
    }

    private static void expectClientExit(int expectedExit, String expectedOutput, String... arguments)
            throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Paths.get(System.getProperty("java.home"), "bin", executable);
        List<String> command = new ArrayList<>(Arrays.asList(java.toString(), "-Djava.awt.headless=true", "-cp",
                System.getProperty("java.class.path"), "TaskClient"));
        command.addAll(Arrays.asList(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            check(process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS), "TaskClient command must exit promptly.");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            check(process.exitValue() == expectedExit && output.contains(expectedOutput),
                    "Unexpected client CLI result: exit=" + process.exitValue() + ", output=" + output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                check(process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS), "The client test process must terminate.");
            }
        }
    }

    private static void assertBusyRejectsWork(Harness client) throws Exception {
        onEventThread(() -> {
            check(!client.connection.refresh(), "Only one refresh may be active.");
            check(!client.connection.add("Busy"), "Add must be rejected while busy.");
            check(!client.connection.remove("Busy"), "Remove must be rejected while busy.");
            check(!client.connection.clear(), "Clear must be rejected while busy.");
            return null;
        });
    }

    private static void assertDisconnectedRejectsMutations(Harness client) throws Exception {
        onEventThread(() -> {
            check(!client.connection.add("Disconnected"), "Disconnected add must be rejected.");
            check(!client.connection.remove("Disconnected"), "Disconnected remove must be rejected.");
            check(!client.connection.clear(), "Disconnected clear must be rejected.");
            return null;
        });
    }

    private static void assertEventThreadResponsive() throws Exception {
        CountDownLatch pulse = new CountDownLatch(1);
        SwingUtilities.invokeLater(pulse::countDown);
        check(pulse.await(2, TimeUnit.SECONDS), "A slow remote call must not block the Swing event thread.");
    }

    private static void expectEventThreadFailure(BooleanSupplier action) {
        try {
            action.getAsBoolean();
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("event thread"), "The failure should explain the thread requirement.");
            return;
        }
        throw new AssertionError("A client action outside the event thread must be rejected.");
    }

    private static void checkBackgroundThread() {
        check(!SwingUtilities.isEventDispatchThread(), "Connector and remote calls must run outside the Swing thread.");
    }

    private static void withExpectedErrors(CheckedTest test) throws Exception {
        PrintStream previous = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(captured, true, StandardCharsets.UTF_8.name())) {
            System.setErr(output);
            try {
                test.run();
            } catch (Exception | AssertionError failure) {
                previous.print(new String(captured.toByteArray(), StandardCharsets.UTF_8));
                throw failure;
            } finally {
                System.setErr(previous);
            }
        }
    }

    private static <T> T onEventThread(java.util.concurrent.Callable<T> action) throws Exception {
        FutureTask<T> result = new FutureTask<>(action);
        SwingUtilities.invokeLater(result);
        return result.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch, String description) throws InterruptedException {
        check(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), description + " timed out.");
    }

    private static void awaitRemote(CountDownLatch latch, String description) throws RemoteException {
        try {
            await(latch, description);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new RemoteException("Test request interrupted", failure);
        }
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        boolean interrupted = false;
        try {
            while (latch.getCount() != 0) {
                long remaining = deadline - System.nanoTime();
                check(remaining > 0, "The simulated uninterruptible request must eventually be released.");
                try {
                    check(latch.await(remaining, TimeUnit.NANOSECONDS), "The slow request release timed out.");
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class Harness {
        private final RecordingListener listener = new RecordingListener();
        private final TaskClientConnection connection;

        Harness(TaskClientConnection.Connector connector) throws Exception {
            connection = onEventThread(() -> new TaskClientConnection(connector, listener));
        }

        Request start(BooleanSupplier action) throws Exception {
            return onEventThread(() -> {
                Request request = new Request();
                listener.current = request;
                check(action.getAsBoolean(), "The requested client operation must be accepted.");
                check(request.started, "The start callback must run before submission returns.");
                return request;
            });
        }

        Request perform(BooleanSupplier action) throws Exception {
            return await(start(action));
        }

        Request await(Request request) throws Exception {
            TaskClientTest.await(request.done, "The client operation must finish");
            // The latch is signalled in finished(), after the worker becomes idle.
            listener.checkThreads();
            return request;
        }

        void close() throws Exception {
            onEventThread(() -> {
                connection.close();
                return null;
            });
        }
    }

    private static final class Request {
        private final CountDownLatch done = new CountDownLatch(1);
        private boolean started;
        private ArrayList<String> tasks;
        private String error;
        private boolean connected;

        void expectTasks(String... expected) {
            check(connected && error == null && tasks != null && tasks.equals(Arrays.asList(expected)),
                    "Expected tasks " + Arrays.asList(expected) + "; got " + tasks + ", error=" + error);
        }

        void expectFailure(boolean remainsConnected, String message) {
            check(connected == remainsConnected && tasks == null && error != null && error.contains(message),
                    "Unexpected failure state: connected=" + connected + ", tasks=" + tasks + ", error=" + error);
        }
    }

    private static final class RecordingListener implements TaskClientConnection.Listener {
        private final AtomicInteger callbacks = new AtomicInteger();
        private final AtomicReference<AssertionError> threadFailure = new AtomicReference<>();
        private Request current;
        private Runnable onStarted = () -> { };
        private Runnable onReceived = () -> { };
        private Runnable onFailed = () -> { };

        private void callback() {
            callbacks.incrementAndGet();
            if (!SwingUtilities.isEventDispatchThread()) {
                threadFailure.compareAndSet(null, new AssertionError("Every listener callback must run on the EDT."));
            }
        }

        void checkThreads() {
            AssertionError failure = threadFailure.get();
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public void started(String description) {
            callback();
            current.started = true;
            onStarted.run();
        }

        @Override
        public void tasksReceived(ArrayList<String> tasks) {
            callback();
            current.tasks = new ArrayList<>(tasks);
            onReceived.run();
        }

        @Override
        public void failed(String message) {
            callback();
            current.error = message;
            onFailed.run();
        }

        @Override
        public void finished(boolean connected) {
            callback();
            current.connected = connected;
            current.done.countDown();
        }
    }

    @FunctionalInterface
    private interface CheckedTest {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface RemoteAction {
        void run() throws RemoteException;
    }

    private static final class FakeService implements TaskManager {
        private final ArrayList<String> tasks = new ArrayList<>();
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private final AtomicInteger adds = new AtomicInteger();
        private final AtomicInteger removes = new AtomicInteger();
        private final AtomicInteger clears = new AtomicInteger();
        private final AtomicInteger reads = new AtomicInteger();
        private volatile RemoteAction beforeAdd = () -> { };
        private volatile RemoteAction afterAdd = () -> { };
        private volatile RemoteAction beforeRemove = () -> { };
        private volatile RemoteAction afterRemove = () -> { };
        private volatile RemoteAction afterClear = () -> { };
        private volatile RemoteAction beforeRead = () -> { };

        FakeService(String... initialTasks) {
            tasks.addAll(Arrays.asList(initialTasks));
        }

        synchronized void inject(String task) {
            tasks.add(task);
        }

        synchronized ArrayList<String> snapshot() {
            return new ArrayList<>(tasks);
        }

        @Override
        public void addTask(String task) throws RemoteException {
            checkBackgroundThread();
            adds.incrementAndGet();
            calls.add("add");
            beforeAdd.run();
            inject(task);
            afterAdd.run();
        }

        @Override
        public void removeTask(String task) throws RemoteException {
            checkBackgroundThread();
            removes.incrementAndGet();
            calls.add("remove");
            beforeRemove.run();
            synchronized (this) {
                tasks.remove(task);
            }
            afterRemove.run();
        }

        @Override
        public void clearTasks() throws RemoteException {
            checkBackgroundThread();
            clears.incrementAndGet();
            calls.add("clear");
            synchronized (this) {
                tasks.clear();
            }
            afterClear.run();
        }

        @Override
        public ArrayList<String> getAllTasks() throws RemoteException {
            checkBackgroundThread();
            reads.incrementAndGet();
            calls.add("read");
            beforeRead.run();
            return snapshot();
        }
    }
}
