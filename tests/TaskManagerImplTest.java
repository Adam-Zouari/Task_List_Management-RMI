import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Dependency-free checks of task identity, validation, atomic operations, and snapshots. */
public final class TaskManagerImplTest {
    private TaskManagerImplTest() { }

    public static void main(String[] args) throws Exception {
        testModel();
        withService(TaskManagerImplTest::testOperations);
        withService(TaskManagerImplTest::testValidation);
        withService(TaskManagerImplTest::testSnapshots);
        withService(TaskManagerImplTest::testCapacity);
        withService(TaskManagerImplTest::testConcurrency);
        withService(service -> {
            service.close();
            service.close();
            expect(IllegalStateException.class, service::getAllTasks);
            expect(IllegalStateException.class, () -> service.addTask("Closed", null));
        });
        System.out.println("TaskManagerImplTest: all checks passed.");
    }

    private static void testModel() throws Exception {
        String id = UUID.randomUUID().toString();
        LocalDate today = LocalDate.of(2026, 10, 22);
        Task overdue = new Task(id, "  Read \uD83D\uDCD6\t", false, today.minusDays(1));
        check(overdue.getDescription().equals("Read \uD83D\uDCD6"), "Model should normalize valid Unicode.");
        check(overdue.isOverdue(today), "An incomplete past-due task should be overdue.");
        check(!new Task(id, "Task", false, today).isOverdue(today), "Today's tasks are not overdue.");
        check(!new Task(id, "Task", false, today.plusDays(1)).isOverdue(today), "Future tasks are not overdue.");
        check(!new Task(id, "Task", false, null).isOverdue(today), "Undated tasks are not overdue.");
        check(!new Task(id, "Task", true, today.minusDays(1)).isOverdue(today),
                "Completed tasks are not overdue.");
        Task equal = new Task(id, overdue.getDescription(), false, overdue.getDueDate());
        check(overdue.equals(equal) && overdue.hashCode() == equal.hashCode(), "Task values should compare equally.");
        expect(NullPointerException.class, () -> overdue.isOverdue(null));
        for (String invalid : new String[] {null, "", "uuid", "1-1-1-1-1", " " + id}) {
            expect(IllegalArgumentException.class, () -> new Task(invalid, "Task", false, null));
        }
    }

    private static void testOperations(TaskManagerImpl service) throws Exception {
        LocalDate due = LocalDate.of(2030, 1, 20);
        service.addTask("  Same text\t", due);
        service.addTask("Same text", null);
        ArrayList<Task> initial = service.getAllTasks();
        Task first = initial.get(0);
        Task second = initial.get(1);
        check(!first.getId().equals(second.getId()), "Duplicate descriptions need distinct UUIDs.");
        check(first.getDescription().equals("Same text") && !first.isCompleted() && due.equals(first.getDueDate()),
                "New tasks should normalize text and start incomplete with the requested date.");
        service.setTaskCompleted(second.getId(), true);
        service.updateTask(second.getId(), "  Edited  ", due.plusDays(1));
        ArrayList<Task> edited = service.getAllTasks();
        check(edited.get(0).equals(first) && edited.get(1).getId().equals(second.getId()),
                "Editing must preserve IDs and insertion order.");
        check(edited.get(1).isCompleted() && edited.get(1).getDescription().equals("Edited")
                        && due.plusDays(1).equals(edited.get(1).getDueDate()),
                "Editing should change description/date and preserve completion.");
        service.updateTask(second.getId(), "Edited", null);
        service.setTaskCompleted(second.getId(), false);
        check(!service.getAllTasks().get(1).isCompleted() && service.getAllTasks().get(1).getDueDate() == null,
                "A due date and completion should both be removable.");
        service.removeTask(second.getId());
        check(service.getAllTasks().size() == 1 && service.getAllTasks().get(0).equals(first),
                "Removal must target the selected UUID, even for duplicate descriptions.");
        expect(IllegalArgumentException.class, () -> service.removeTask(second.getId()));
        service.clearTasks();
        service.clearTasks();
        check(service.getAllTasks().isEmpty(), "Clear should be idempotent.");
    }

    private static void testValidation(TaskManagerImpl service) throws Exception {
        service.addTask("Original", null);
        String id = service.getAllTasks().get(0).getId();
        for (String invalid : new String[] {null, "", " \t\n", "\u2003", "\uD800", "\uDC00", "x\uD800y",
                "x".repeat(Task.MAX_DESCRIPTION_LENGTH + 1)}) {
            expect(IllegalArgumentException.class, () -> service.addTask(invalid, null));
            expect(IllegalArgumentException.class, () -> service.updateTask(id, invalid, null));
        }
        for (String invalid : new String[] {null, "", " \t", "not-an-id", UUID.randomUUID().toString()}) {
            expect(IllegalArgumentException.class, () -> service.updateTask(invalid, "Edited", null));
            expect(IllegalArgumentException.class, () -> service.setTaskCompleted(invalid, true));
            expect(IllegalArgumentException.class, () -> service.removeTask(invalid));
        }
        check(service.getAllTasks().size() == 1 && service.getAllTasks().get(0).getDescription().equals("Original"),
                "Invalid operations must not change state.");
        String maximum = "x".repeat(Task.MAX_DESCRIPTION_LENGTH);
        service.updateTask(id, " " + maximum + " ", null);
        check(service.getAllTasks().get(0).getDescription().equals(maximum),
                "The normalized maximum-length description should be accepted.");
    }

    private static void testSnapshots(TaskManagerImpl service) throws Exception {
        service.addTask("Original", null);
        ArrayList<Task> snapshot = service.getAllTasks();
        Task original = snapshot.get(0);
        snapshot.clear();
        check(service.getAllTasks().size() == 1, "Changing the returned list must not change service state.");
        service.updateTask(original.getId(), "Changed", LocalDate.of(2031, 2, 3));
        service.setTaskCompleted(original.getId(), true);
        check(original.getDescription().equals("Original") && !original.isCompleted() && original.getDueDate() == null,
                "Earlier task values must remain unchanged after edits.");
        ArrayList<Task> beforeClear = service.getAllTasks();
        service.clearTasks();
        check(beforeClear.size() == 1, "Later mutations must not alter an earlier snapshot.");
    }

    private static void testCapacity(TaskManagerImpl service) throws Exception {
        for (int index = 0; index < TaskManagerImpl.MAX_TASKS; index++) { service.addTask("Repeated", null); }
        check(service.getAllTasks().size() == TaskManagerImpl.MAX_TASKS, "The exact capacity must be accepted.");
        expect(IllegalStateException.class, () -> service.addTask("Overflow", null));
        String id = service.getAllTasks().get(0).getId();
        service.updateTask(id, "Full-list edit", null);
        service.setTaskCompleted(id, true);
        service.removeTask(id);
        service.addTask("Available slot", null);
        check(service.getAllTasks().size() == TaskManagerImpl.MAX_TASKS, "Removal should release capacity.");
        service.clearTasks();
        service.addTask("After clear", null);
        check(service.getAllTasks().size() == 1, "Clearing a full list should release capacity.");
    }

    private static void testConcurrency(TaskManagerImpl service) throws Exception {
        int writerCount = 6;
        int tasksPerWriter = 100;
        ExecutorService workers = Executors.newFixedThreadPool(writerCount + 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int writer = 0; writer < writerCount; writer++) {
                final int writerId = writer;
                results.add(workers.submit(() -> {
                    start.await();
                    for (int index = 0; index < tasksPerWriter; index++) {
                        String text = writerId + ":" + index;
                        service.addTask(text, null);
                        Task added = service.getAllTasks().stream()
                                .filter(task -> task.getDescription().equals(text)).findFirst().orElseThrow();
                        service.updateTask(added.getId(), text, LocalDate.of(2030, 1, 1));
                        service.setTaskCompleted(added.getId(), true);
                        if (index % 2 == 0) { service.removeTask(added.getId()); }
                    }
                    return null;
                }));
            }
            for (int reader = 0; reader < 2; reader++) {
                results.add(workers.submit(() -> {
                    start.await();
                    for (int iteration = 0; iteration < 100; iteration++) {
                        ArrayList<Task> snapshot = service.getAllTasks();
                        Set<String> ids = new HashSet<>();
                        for (Task task : snapshot) {
                            check(ids.add(task.getId()), "Concurrent snapshots must contain unique UUIDs.");
                        }
                        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                            try (ObjectOutputStream output = new ObjectOutputStream(buffer)) { output.writeObject(snapshot); }
                            try (ObjectInputStream input = new ObjectInputStream(
                                    new ByteArrayInputStream(buffer.toByteArray()))) {
                                check(snapshot.equals(input.readObject()), "Snapshots must serialize during mutations.");
                            }
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) { result.get(30, TimeUnit.SECONDS); }
            Set<String> expected = new HashSet<>();
            for (int writer = 0; writer < writerCount; writer++) {
                for (int index = 1; index < tasksPerWriter; index += 2) { expected.add(writer + ":" + index); }
            }
            Set<String> actual = new HashSet<>();
            for (Task task : service.getAllTasks()) {
                actual.add(task.getDescription());
                check(task.isCompleted() && task.getDueDate() != null, "Concurrent edits/toggles must be retained.");
            }
            check(actual.equals(expected) && service.getAllTasks().size() == expected.size(),
                    "Concurrent operations must retain every surviving task exactly once.");
        } finally {
            start.countDown();
            workers.shutdownNow();
            check(workers.awaitTermination(10, TimeUnit.SECONDS), "Test workers should terminate.");
        }
    }

    private static void withService(ServiceCheck test) throws Exception {
        TaskManagerImpl service = new TaskManagerImpl();
        try { test.run(service); }
        finally { service.close(); }
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
    @FunctionalInterface
    private interface ServiceCheck { void run(TaskManagerImpl service) throws Exception; }
}
