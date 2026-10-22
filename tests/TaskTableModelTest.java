import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/** Headless checks for table identity, checkbox edits, sorting, and date entry. */
public final class TaskTableModelTest {
    private TaskTableModelTest() {
    }

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            testTable();
            testDescriptionEditor();
        });
        testDateInput();
        System.out.println("TaskTableModelTest: all checks passed.");
    }

    private static void testTable() {
        AtomicReference<String> changedId = new AtomicReference<>();
        AtomicReference<Boolean> changedCompletion = new AtomicReference<>();
        TaskTableModel model = new TaskTableModel((id, completed) -> {
            changedId.set(id);
            changedCompletion.set(completed);
        });
        LocalDate today = LocalDate.now();
        Task first = new Task(UUID.randomUUID().toString(), "Same description", false, today.plusDays(1));
        Task second = new Task(UUID.randomUUID().toString(), "Same description", true, today.minusDays(1));
        Task overdue = new Task(UUID.randomUUID().toString(), "Past due", false, today.minusDays(1));
        model.replaceTasks(Arrays.asList(first, second, overdue));
        check(model.getRowCount() == 3 && model.getColumnCount() == 4, "The table should expose all task fields.");
        check(Boolean.class.equals(model.getColumnClass(0)), "Completion must use a checkbox column.");
        check(LocalDate.class.equals(model.getColumnClass(2)), "Due dates must sort as dates.");
        check("Open".equals(model.getValueAt(0, 3)), "A future task should be open.");
        check("Completed".equals(model.getValueAt(1, 3)), "Completed tasks should not be overdue.");
        check("Overdue".equals(model.getValueAt(2, 3)), "An unfinished past-due task should be overdue.");
        check(!model.isCellEditable(0, 0), "Disconnected or busy tables must not accept changes.");
        model.setValueAt(Boolean.TRUE, 0, 0);
        check(changedId.get() == null, "A disabled checkbox must not dispatch a mutation.");

        model.setEditable(true);
        for (int column = 1; column < model.getColumnCount(); column++) {
            check(!model.isCellEditable(0, column), "Only completion is edited directly in the table.");
        }
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.getRowSorter().toggleSortOrder(2);
        int secondViewRow = table.convertRowIndexToView(1);
        table.setValueAt(Boolean.FALSE, secondViewRow, 0);
        check(second.getId().equals(changedId.get()) && Boolean.FALSE.equals(changedCompletion.get()),
                "A sorted checkbox must target the correct ID, even for duplicate descriptions.");
        check(Boolean.TRUE.equals(model.getValueAt(1, 0)),
                "The table must wait for the server snapshot before changing completion.");
        Task updated = new Task(second.getId(), "Updated", false, null);
        model.replaceTasks(Arrays.asList(first, updated));
        check(model.getTaskAt(1).equals(updated) && model.getValueAt(1, 2) == null,
                "A new snapshot must reflect edited details and a removed due date.");
        check("Open".equals(model.getValueAt(1, 3)), "An unfinished task without a deadline should be open.");
    }

    private static void testDescriptionEditor() {
        String description = "Caf\u00e9 \uD83D\uDCD6\tfirst line\nsecond line\rthird line";
        JTextArea editor = TaskClient.createDescriptionEditor(description);
        check(description.equals(editor.getText()),
                "Opening the edit dialog must preserve newlines, carriage returns, tabs, and Unicode.");
        check(description.equals(Task.normalizeDescription(editor.getText())),
                "Saving a due-date-only edit must preserve the original description.");
    }

    private static void testDateInput() {
        check(TaskClient.parseDueDate(null) == null, "Null should represent no deadline.");
        check(TaskClient.parseDueDate(" \t") == null, "Blank should represent no deadline.");
        check(LocalDate.of(2028, 2, 29).equals(TaskClient.parseDueDate(" 2028-02-29 ")),
                "A valid leap date should be parsed after stripping whitespace.");
        for (String invalid : new String[] {"2026-02-29", "2026-02-30", "2026-13-01", "tomorrow",
                "2026-2-3", "2026-01-01 12:00"}) {
            try {
                TaskClient.parseDueDate(invalid);
            } catch (IllegalArgumentException expected) {
                continue;
            }
            throw new AssertionError("Invalid due date was accepted: " + invalid);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
