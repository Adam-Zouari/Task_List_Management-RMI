import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.RemoteException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
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
            testAutomaticRefresh();
            testReconnectAfterFailureAndRestart();
            testUncertainMutationIsNeverRetried();
            testPersistenceFailureBeforeMutation();
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
                check(!client.connection.add("Too early", null), "Add must be disabled before connecting.");
                check(!client.connection.update("id", "Too early", null), "Edit must be disabled before connecting.");
                check(!client.connection.setCompleted("id", true), "Completion must be disabled before connecting.");
                check(!client.connection.remove("Existing"), "Remove must be disabled before connecting.");
                check(!client.connection.clear(), "Clear must be disabled before connecting.");
                check(!client.connection.refreshAutomatically(), "Polling must be disabled before connecting.");
                return null;
            });
            Request first = client.perform(client.connection::refresh);
            first.expectTasks("Existing");
            check(lookups.get() == 1, "Refresh must look up a service off the event thread.");

            service.calls.clear();
            service.afterAdd = () -> service.inject("Other client's task");
            LocalDate dueDate = LocalDate.of(2030, 4, 12);
            Request added = client.perform(() -> client.connection.add("New", dueDate));
            added.expectTasks("Existing", "New", "Other client's task");
            Task addedTask = added.tasks.get(1);
            check(dueDate.equals(addedTask.getDueDate()) && !addedTask.isCompleted(),
                    "Add must forward its due date and receive the server-assigned identity.");
            check(service.calls.equals(Arrays.asList("add", "read")),
                    "Add must be followed by an authoritative full-list fetch.");

            service.calls.clear();
            LocalDate updatedDate = dueDate.plusDays(1);
            service.afterUpdate = () -> service.inject("Concurrent edit addition");
            Request edited = client.perform(() -> client.connection.update(addedTask.getId(), "Edited", updatedDate));
            edited.expectTasks("Existing", "Edited", "Other client's task", "Concurrent edit addition");
            check(service.calls.equals(Arrays.asList("update", "read")),
                    "Editing must be followed by an authoritative snapshot.");
            check(addedTask.getId().equals(edited.tasks.get(1).getId())
                            && updatedDate.equals(edited.tasks.get(1).getDueDate()),
                    "Editing must forward the selected task identity and new due date.");

            service.calls.clear();
            service.afterCompleted = () -> service.inject("Concurrent completion addition");
            Request completed = client.perform(() -> client.connection.setCompleted(addedTask.getId(), true));
            completed.expectTasks("Existing", "Edited", "Other client's task", "Concurrent edit addition",
                    "Concurrent completion addition");
            check(service.calls.equals(Arrays.asList("complete", "read")),
                    "Completion must be followed by an authoritative snapshot.");
            Task completedTask = completed.tasks.get(1);
            check(completedTask.isCompleted() && updatedDate.equals(completedTask.getDueDate())
                            && addedTask.getId().equals(completedTask.getId()),
                    "Completion must update the selected task and preserve its other fields.");
            service.afterUpdate = () -> { };
            Request noDeadline = client.perform(() -> client.connection.update(addedTask.getId(), "Edited", null));
            check(noDeadline.tasks.get(1).getDueDate() == null && noDeadline.tasks.get(1).isCompleted(),
                    "Editing must allow clearing a due date while preserving completion.");
            service.afterCompleted = () -> { };
            Request reopened = client.perform(() -> client.connection.setCompleted(addedTask.getId(), false));
            check(!reopened.tasks.get(1).isCompleted(), "Completion must support marking a task unfinished.");

            service.calls.clear();
            service.afterRemove = () -> service.inject("Concurrent addition");
            client.perform(() -> client.connection.remove(service.idFor("Existing")))
                    .expectTasks("Edited", "Other client's task", "Concurrent edit addition",
                            "Concurrent completion addition", "Concurrent addition");
            check(service.calls.equals(Arrays.asList("remove", "read")),
                    "Remove must be followed by a full-list fetch.");

            service.calls.clear();
            service.afterClear = () -> service.inject("Added after clear");
            client.perform(client.connection::clear).expectTasks("Added after clear");
            check(service.calls.equals(Arrays.asList("clear", "read")),
                    "Clear must fetch server state rather than assume the list is empty.");
            check(service.adds.get() == 1 && service.updates.get() == 2 && service.completions.get() == 2
                            && service.removes.get() == 1 && service.clears.get() == 1,
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
            Request addition = client.start(() -> client.connection.add("One request", null));
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

    private static void testAutomaticRefresh() throws Exception {
        FakeService original = new FakeService("Shared task");
        FakeService replacement = new FakeService("Restarted server");
        AtomicInteger lookups = new AtomicInteger();
        AtomicReference<FakeService> current = new AtomicReference<>(original);
        Harness client = new Harness(() -> {
            checkBackgroundThread();
            lookups.incrementAndGet();
            return current.get();
        });
        try {
            onEventThread(() -> {
                check(!client.connection.refreshAutomatically(), "Polling must require an existing connection.");
                return null;
            });
            check(lookups.get() == 0, "Polling must not connect a disconnected client.");
            client.perform(client.connection::refresh).expectTasks("Shared task");
            String id = original.idFor("Shared task");
            original.updateTaskForTest(id, "Changed by another client", LocalDate.of(2031, 2, 3));
            original.calls.clear();
            Request poll = client.perform(client.connection::refreshAutomatically);
            poll.expectTasks("Changed by another client");
            check(id.equals(poll.tasks.get(0).getId())
                            && LocalDate.of(2031, 2, 3).equals(poll.tasks.get(0).getDueDate()),
                    "Polling must receive complete task fields from the authoritative snapshot.");
            check(original.calls.equals(Arrays.asList("read")) && lookups.get() == 1,
                    "Polling must reuse the existing stub without looking up or mutating anything.");

            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            original.beforeRead = () -> {
                entered.countDown();
                awaitRemote(release, "Release automatic refresh");
            };
            try {
                Request slowPoll = client.start(client.connection::refreshAutomatically);
                await(entered, "The automatic refresh must start off the EDT");
                assertEventThreadResponsive();
                assertBusyRejectsWork(client);
                release.countDown();
                client.await(slowPoll).expectTasks("Changed by another client");
            } finally {
                release.countDown();
            }

            original.beforeRead = () -> {
                throw new RemoteException("Expected polling failure");
            };
            Request failedPoll = client.perform(client.connection::refreshAutomatically);
            check(!failedPoll.connected && failedPoll.tasks == null && failedPoll.error == null,
                    "A failed poll must disconnect without delivering a modal failure callback.");
            int readsAfterFailure = original.reads.get();
            assertDisconnectedRejectsMutations(client);
            check(lookups.get() == 1 && original.reads.get() == readsAfterFailure,
                    "A failed poll must stop polling without automatically reconnecting.");
            current.set(replacement);
            client.perform(client.connection::refresh).expectTasks("Restarted server");
            client.perform(client.connection::refreshAutomatically).expectTasks("Restarted server");
            check(lookups.get() == 2, "Manual refresh must reconnect; subsequent polls must reuse that new stub.");
        } finally {
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
            client.perform(() -> client.connection.remove(original.idFor("Old server")))
                    .expectFailure(false, "may already");
            assertDisconnectedRejectsMutations(client);
            current.set(replacement);
            client.perform(client.connection::refresh).expectTasks("New server");
            client.perform(() -> client.connection.add("Recovered", null)).expectTasks("New server", "Recovered");
            check(lookups.get() == 3, "Only explicit refreshes may look up replacement stubs.");
            check(original.removes.get() == 1 && original.adds.get() == 0 && replacement.adds.get() == 1,
                    "After recovery, mutations must use the replacement service.");
        } finally {
            client.close();
        }
    }

    private static void testUncertainMutationIsNeverRetried() throws Exception {
        for (String operation : Arrays.asList("add", "update", "complete", "remove", "clear")) {
            FakeService service = new FakeService("Existing");
            AtomicInteger lookups = new AtomicInteger();
            Harness client = new Harness(() -> {
                checkBackgroundThread();
                lookups.incrementAndGet();
                return service;
            });
            try {
                client.perform(client.connection::refresh).expectTasks("Existing");
                String id = service.idFor("Existing");
                RemoteAction lostResponse = () -> {
                    throw new RemoteException("Applied " + operation + ", but its response was lost");
                };
                BooleanSupplier action;
                String[] expectedDescriptions;
                switch (operation) {
                    case "add":
                        service.afterAdd = lostResponse;
                        action = () -> client.connection.add("Apply once", null);
                        expectedDescriptions = new String[] {"Existing", "Apply once"};
                        break;
                    case "update":
                        service.afterUpdate = lostResponse;
                        action = () -> client.connection.update(id, "Edited once", null);
                        expectedDescriptions = new String[] {"Edited once"};
                        break;
                    case "complete":
                        service.afterCompleted = lostResponse;
                        action = () -> client.connection.setCompleted(id, true);
                        expectedDescriptions = new String[] {"Existing"};
                        break;
                    case "remove":
                        service.afterRemove = lostResponse;
                        action = () -> client.connection.remove(id);
                        expectedDescriptions = new String[0];
                        break;
                    case "clear":
                        service.afterClear = lostResponse;
                        action = client.connection::clear;
                        expectedDescriptions = new String[0];
                        break;
                    default:
                        throw new AssertionError("Unknown mutation test: " + operation);
                }
                service.calls.clear();
                client.perform(action).expectFailure(false, "may already");
                check(descriptions(service.snapshot()).equals(Arrays.asList(expectedDescriptions)),
                        "The fake server must have applied " + operation + " exactly once.");
                check(service.calls.equals(Arrays.asList(operation)) && service.reads.get() == 1 && lookups.get() == 1,
                        "An uncertain mutation must never be retried or followed by automatic reconnection.");
                if ("complete".equals(operation)) {
                    check(service.snapshot().get(0).isCompleted(), "The completion must have reached the fake server.");
                }
                assertDisconnectedRejectsMutations(client);
                client.perform(client.connection::refresh).expectTasks(expectedDescriptions);
                check(lookups.get() == 2 && service.calls.equals(Arrays.asList(operation, "read")),
                        "Manual refresh must inspect state without repeating the mutation.");
            } finally {
                client.close();
            }
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
            client.perform(() -> client.connection.add("Committed", null)).expectFailure(false, "may already");
            check(service.adds.get() == 1 && service.reads.get() == 2,
                    "A successful mutation must attempt exactly one authoritative snapshot.");
            check(descriptions(service.snapshot()).equals(Arrays.asList("Committed")),
                    "A snapshot failure must not undo or retry the successful mutation.");
            assertDisconnectedRejectsMutations(client);
            service.beforeRead = () -> { };
            client.perform(client.connection::refresh).expectTasks("Committed");
            check(service.adds.get() == 1, "Recovery must not replay a mutation after a snapshot failure.");
        } finally {
            client.close();
        }
    }

    private static void testPersistenceFailureBeforeMutation() throws Exception {
        FakeService service = new FakeService("Already saved");
        AtomicInteger lookups = new AtomicInteger();
        Harness client = new Harness(() -> {
            lookups.incrementAndGet();
            return service;
        });
        try {
            client.perform(client.connection::refresh).expectTasks("Already saved");
            service.calls.clear();
            service.beforeAdd = () -> {
                throw new RemoteException("Could not save tasks; no changes were applied.");
            };
            client.perform(() -> client.connection.add("Not saved", null)).expectFailure(false, "may already");
            check(descriptions(service.snapshot()).equals(Arrays.asList("Already saved")),
                    "A failed save must not fabricate an added task in the client or server snapshot.");
            check(service.calls.equals(Arrays.asList("add")) && lookups.get() == 1,
                    "The client must not retry a mutation after a persistence failure.");
            assertDisconnectedRejectsMutations(client);
            client.perform(client.connection::refresh).expectTasks("Already saved");
            check(service.adds.get() == 1 && lookups.get() == 2,
                    "Manual refresh must recover saved state without reapplying the failed mutation.");
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
            client.perform(() -> client.connection.add(" ", null)).expectFailure(true, "Task rejected:");
            service.beforeAdd = () -> {
                throw new IllegalStateException("The task list is full");
            };
            client.perform(() -> client.connection.add("Overflow", null)).expectFailure(true, "Task rejected:");
            check(service.snapshot().isEmpty() && service.reads.get() == 1,
                    "Rejected mutations must not update state or fetch a misleading snapshot.");
            service.beforeAdd = () -> { };
            client.perform(() -> client.connection.add("Valid", null)).expectTasks("Valid");
            String id = service.idFor("Valid");
            service.beforeUpdate = () -> {
                throw new IllegalArgumentException("The new description is invalid");
            };
            client.perform(() -> client.connection.update(id, " ", null)).expectFailure(true, "Task rejected:");
            client.perform(() -> client.connection.setCompleted("missing-id", true))
                    .expectFailure(true, "Task rejected:");
            client.perform(() -> client.connection.remove("missing-id")).expectFailure(true, "Task rejected:");
            service.beforeUpdate = () -> { };
            client.perform(() -> client.connection.update(id, "Still connected", null)).expectTasks("Still connected");
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
                check(!client.connection.add("Closed", null), "A closed client must reject add.");
                check(!client.connection.update("id", "Closed", null), "A closed client must reject edit.");
                check(!client.connection.setCompleted("id", true), "A closed client must reject completion.");
                check(!client.connection.remove("Closed"), "A closed client must reject remove.");
                check(!client.connection.clear(), "A closed client must reject clear.");
                check(!client.connection.refreshAutomatically(), "A closed client must reject automatic refresh.");
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
            expectEventThreadFailure(client.connection::refreshAutomatically);
            expectEventThreadFailure(() -> client.connection.add("Wrong thread", null));
            expectEventThreadFailure(() -> client.connection.update("id", "Wrong thread", null));
            expectEventThreadFailure(() -> client.connection.setCompleted("id", true));
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
            check(!client.connection.refreshAutomatically(), "Automatic refresh must not overlap another operation.");
            check(!client.connection.add("Busy", null), "Add must be rejected while busy.");
            check(!client.connection.update("id", "Busy", null), "Edit must be rejected while busy.");
            check(!client.connection.setCompleted("id", true), "Completion must be rejected while busy.");
            check(!client.connection.remove("Busy"), "Remove must be rejected while busy.");
            check(!client.connection.clear(), "Clear must be rejected while busy.");
            return null;
        });
    }

    private static void assertDisconnectedRejectsMutations(Harness client) throws Exception {
        onEventThread(() -> {
            check(!client.connection.add("Disconnected", null), "Disconnected add must be rejected.");
            check(!client.connection.update("id", "Disconnected", null), "Disconnected edit must be rejected.");
            check(!client.connection.setCompleted("id", true), "Disconnected completion must be rejected.");
            check(!client.connection.remove("Disconnected"), "Disconnected remove must be rejected.");
            check(!client.connection.clear(), "Disconnected clear must be rejected.");
            check(!client.connection.refreshAutomatically(), "Disconnected clients must not poll or reconnect automatically.");
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

    private static List<String> descriptions(List<Task> tasks) {
        ArrayList<String> descriptions = new ArrayList<>();
        for (Task task : tasks) {
            descriptions.add(task.getDescription());
        }
        return descriptions;
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
        private ArrayList<Task> tasks;
        private String error;
        private boolean connected;

        void expectTasks(String... expected) {
            check(connected && error == null && tasks != null && descriptions(tasks).equals(Arrays.asList(expected)),
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
        public void tasksReceived(ArrayList<Task> tasks) {
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
        private final ArrayList<Task> tasks = new ArrayList<>();
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private final AtomicInteger adds = new AtomicInteger();
        private final AtomicInteger updates = new AtomicInteger();
        private final AtomicInteger completions = new AtomicInteger();
        private final AtomicInteger removes = new AtomicInteger();
        private final AtomicInteger clears = new AtomicInteger();
        private final AtomicInteger reads = new AtomicInteger();
        private volatile RemoteAction beforeAdd = () -> { };
        private volatile RemoteAction afterAdd = () -> { };
        private volatile RemoteAction beforeUpdate = () -> { };
        private volatile RemoteAction afterUpdate = () -> { };
        private volatile RemoteAction afterCompleted = () -> { };
        private volatile RemoteAction beforeRemove = () -> { };
        private volatile RemoteAction afterRemove = () -> { };
        private volatile RemoteAction afterClear = () -> { };
        private volatile RemoteAction beforeRead = () -> { };

        FakeService(String... initialTasks) {
            for (String description : initialTasks) {
                inject(description);
            }
        }

        synchronized void inject(String description) {
            tasks.add(new Task(UUID.randomUUID().toString(), description, false, null));
        }

        synchronized String idFor(String description) {
            for (Task task : tasks) {
                if (description.equals(task.getDescription())) {
                    return task.getId();
                }
            }
            throw new AssertionError("Missing fake task: " + description);
        }

        private synchronized int indexOf(String id) {
            for (int index = 0; index < tasks.size(); index++) {
                if (tasks.get(index).getId().equals(id)) {
                    return index;
                }
            }
            throw new IllegalArgumentException("No task with id: " + id);
        }

        synchronized void updateTaskForTest(String id, String description, LocalDate dueDate) {
            int index = indexOf(id);
            Task previous = tasks.get(index);
            tasks.set(index, new Task(id, description, previous.isCompleted(), dueDate));
        }

        synchronized ArrayList<Task> snapshot() {
            return new ArrayList<>(tasks);
        }

        @Override
        public void addTask(String description, LocalDate dueDate) throws RemoteException {
            checkBackgroundThread();
            adds.incrementAndGet();
            calls.add("add");
            beforeAdd.run();
            synchronized (this) {
                tasks.add(new Task(UUID.randomUUID().toString(), description, false, dueDate));
            }
            afterAdd.run();
        }

        @Override
        public void updateTask(String id, String description, LocalDate dueDate) throws RemoteException {
            checkBackgroundThread();
            updates.incrementAndGet();
            calls.add("update");
            beforeUpdate.run();
            synchronized (this) {
                int index = indexOf(id);
                Task previous = tasks.get(index);
                tasks.set(index, new Task(id, description, previous.isCompleted(), dueDate));
            }
            afterUpdate.run();
        }

        @Override
        public void setTaskCompleted(String id, boolean completed) throws RemoteException {
            checkBackgroundThread();
            completions.incrementAndGet();
            calls.add("complete");
            synchronized (this) {
                int index = indexOf(id);
                Task previous = tasks.get(index);
                tasks.set(index, new Task(id, previous.getDescription(), completed, previous.getDueDate()));
            }
            afterCompleted.run();
        }

        @Override
        public void removeTask(String id) throws RemoteException {
            checkBackgroundThread();
            removes.incrementAndGet();
            calls.add("remove");
            beforeRemove.run();
            synchronized (this) {
                tasks.remove(indexOf(id));
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
        public ArrayList<Task> getAllTasks() throws RemoteException {
            checkBackgroundThread();
            reads.incrementAndGet();
            calls.add("read");
            beforeRead.run();
            return snapshot();
        }
    }
}
