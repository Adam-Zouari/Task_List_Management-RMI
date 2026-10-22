import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Dependency-free regression tests. Run with java -cp build TaskManagerImplTest. */
public final class TaskManagerImplTest {
    private TaskManagerImplTest() {
    }

    public static void main(String[] args) throws Exception {
        withService(TaskManagerImplTest::testBasicOperations);
        withService(TaskManagerImplTest::testValidation);
        withService(TaskManagerImplTest::testDefensiveSnapshots);
        withService(TaskManagerImplTest::testCapacity);
        withService(TaskManagerImplTest::testConcurrentOperationsAndSerialization);
        System.out.println("TaskManagerImplTest: all checks passed.");
    }

    private static void testBasicOperations(TaskManagerImpl service) throws Exception {
        check(service.getAllTasks().isEmpty(), "A new service should be empty.");
        service.addTask("  First task\t");
        service.addTask("Second task");
        service.addTask("First task");
        check(service.getAllTasks().equals(Arrays.asList("First task", "Second task", "First task")),
                "Add should strip whitespace and preserve insertion order and duplicates.");
        service.removeTask("\nFirst task  ");
        check(service.getAllTasks().equals(Arrays.asList("Second task", "First task")),
                "Remove should remove only the first normalized match.");
        service.removeTask("Missing task");
        check(service.getAllTasks().size() == 2, "Removing a missing task should be harmless.");
        service.clearTasks();
        service.clearTasks();
        check(service.getAllTasks().isEmpty(), "Clear should be idempotent.");
    }

    private static void testValidation(TaskManagerImpl service) throws Exception {
        for (String invalid : new String[] {null, "", " \t\n\r", "\u2003\u2003",
                "x".repeat(TaskManagerImpl.MAX_TASK_LENGTH + 1)}) {
            expect(IllegalArgumentException.class, () -> service.addTask(invalid));
            expect(IllegalArgumentException.class, () -> service.removeTask(invalid));
        }
        check(service.getAllTasks().isEmpty(), "Invalid input must not change state.");
        String maximumLength = "x".repeat(TaskManagerImpl.MAX_TASK_LENGTH);
        service.addTask("  " + maximumLength + "  ");
        check(service.getAllTasks().equals(Arrays.asList(maximumLength)),
                "The normalized maximum-length task should be accepted.");
        service.removeTask(maximumLength);
        check(service.getAllTasks().isEmpty(), "Maximum-length task should be removable.");
    }

    private static void testDefensiveSnapshots(TaskManagerImpl service) throws Exception {
        service.addTask("Original");
        ArrayList<String> snapshot = service.getAllTasks();
        snapshot.clear();
        snapshot.add("Local modification");
        check(service.getAllTasks().equals(Arrays.asList("Original")),
                "Changing a snapshot must not change service state.");
        ArrayList<String> oldSnapshot = service.getAllTasks();
        service.addTask("Later");
        service.clearTasks();
        check(oldSnapshot.equals(Arrays.asList("Original")),
                "Subsequent service mutations must not change an earlier snapshot.");
    }

    private static void testCapacity(TaskManagerImpl service) throws Exception {
        for (int index = 0; index < TaskManagerImpl.MAX_TASKS; index++) {
            service.addTask("Repeated task");
        }
        check(service.getAllTasks().size() == TaskManagerImpl.MAX_TASKS,
                "The exact capacity should be accepted, including duplicate tasks.");
        expect(IllegalStateException.class, () -> service.addTask("Overflow"));
        check(service.getAllTasks().size() == TaskManagerImpl.MAX_TASKS,
                "An overflow failure must not change state.");
        service.removeTask("Repeated task");
        service.addTask("Available slot");
        check(service.getAllTasks().size() == TaskManagerImpl.MAX_TASKS,
                "A removal should make room for another task.");
        service.clearTasks();
        service.addTask("After clear");
        check(service.getAllTasks().equals(Arrays.asList("After clear")),
                "Clearing a full list should release its capacity.");
    }

    private static void testConcurrentOperationsAndSerialization(TaskManagerImpl service) throws Exception {
        int writerCount = 8;
        int tasksPerWriter = 200;
        ExecutorService workers = Executors.newFixedThreadPool(writerCount + 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int writer = 0; writer < writerCount; writer++) {
                final int writerId = writer;
                results.add(workers.submit(() -> {
                    start.await();
                    for (int index = 0; index < tasksPerWriter; index++) {
                        String task = writerId + ":" + index;
                        service.addTask(task);
                        if (index % 2 == 0) {
                            service.removeTask(task);
                        }
                    }
                    return null;
                }));
            }
            for (int reader = 0; reader < 2; reader++) {
                results.add(workers.submit(() -> {
                    start.await();
                    for (int iteration = 0; iteration < 100; iteration++) {
                        ArrayList<String> snapshot = service.getAllTasks();
                        check(new HashSet<>(snapshot).size() == snapshot.size(),
                                "Concurrent snapshots should contain no duplicate unique tasks.");
                        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                            try (ObjectOutputStream output = new ObjectOutputStream(buffer)) {
                                output.writeObject(snapshot);
                            }
                            try (ObjectInputStream input = new ObjectInputStream(
                                    new ByteArrayInputStream(buffer.toByteArray()))) {
                                check(snapshot.equals(input.readObject()),
                                        "A snapshot must serialize safely during concurrent mutations.");
                            }
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
            Set<String> expected = new HashSet<>();
            for (int writer = 0; writer < writerCount; writer++) {
                for (int index = 1; index < tasksPerWriter; index += 2) {
                    expected.add(writer + ":" + index);
                }
            }
            ArrayList<String> actual = service.getAllTasks();
            check(actual.size() == expected.size() && new HashSet<>(actual).equals(expected),
                    "Concurrent adds and removes must preserve every surviving task.");
        } finally {
            start.countDown();
            workers.shutdownNow();
            check(workers.awaitTermination(10, TimeUnit.SECONDS), "Test workers should terminate.");
        }
    }

    private static void withService(ServiceCheck test) throws Exception {
        TaskManagerImpl service = new TaskManagerImpl();
        try {
            test.run(service);
        } finally {
            UnicastRemoteObject.unexportObject(service, true);
        }
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

    @FunctionalInterface
    private interface ServiceCheck {
        void run(TaskManagerImpl service) throws Exception;
    }
}
