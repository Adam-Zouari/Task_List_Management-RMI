import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JCheckBox;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.table.TableCellRenderer;

/** Headless checks for task table styling, identity, checkbox edits, sorting, and date entry. */
public final class TaskTableModelTest {
    private TaskTableModelTest() {
    }

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            testTable();
            testTaskRenderers();
            testPrimaryButtonPainting();
            testDescriptionEditor();
        });
        testDateInput();
        System.out.println("TaskTableModelTest: all checks passed.");
    }

    private static void testPrimaryButtonPainting() {
        JButton button = new JButton("Add Task");
        TaskClientStyle.button(button, true, false);
        button.setSize(button.getPreferredSize());
        BufferedImage image = new BufferedImage(button.getWidth(), button.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        button.paint(graphics);
        graphics.dispose();
        check(image.getRGB(4, 4) == button.getBackground().getRGB(),
                "The primary button must paint its background so its white label stays visible.");
        check(!button.getForeground().equals(button.getBackground()), "The primary action needs contrasting text.");
    }

    private static void testTaskRenderers() {
        LocalDate today = LocalDate.now();
        Task open = new Task(UUID.randomUUID().toString(), "Z open", false, null);
        Task completed = new Task(UUID.randomUUID().toString(), "A completed", true, today.minusDays(2));
        Task overdue = new Task(UUID.randomUUID().toString(), "M overdue", false, today.minusDays(1));
        TaskTableModel model = new TaskTableModel((id, value) -> { });
        model.replaceTasks(Arrays.asList(open, completed, overdue));
        JTable table = new JTable(model);
        table.setFont(TaskClientStyle.FONT);
        table.setAutoCreateRowSorter(true);
        table.getRowSorter().toggleSortOrder(1);
        // Renderers must interpret view indices correctly after sorting and moving columns.
        table.moveColumn(1, 3);
        int completedRow = table.convertRowIndexToView(1);
        int overdueRow = table.convertRowIndexToView(2);
        int openRow = table.convertRowIndexToView(0);
        int descriptionColumn = table.convertColumnIndexToView(1);
        int dateColumn = table.convertColumnIndexToView(2);
        int statusColumn = table.convertColumnIndexToView(3);
        TaskClient.TaskRenderer text = new TaskClient.TaskRenderer();
        TaskClient.CompletionRenderer checkbox = new TaskClient.CompletionRenderer();

        Component cell = render(text, table, completedRow, descriptionColumn, false);
        check(Boolean.TRUE.equals(cell.getFont().getAttributes().get(TextAttribute.STRIKETHROUGH))
                        && TaskClientStyle.MUTED.equals(cell.getForeground()),
                "A sorted completed description must be muted and crossed out after moving its column.");
        cell = render(text, table, completedRow, dateColumn, false);
        check(!Boolean.TRUE.equals(cell.getFont().getAttributes().get(TextAttribute.STRIKETHROUGH))
                        && TaskClientStyle.MUTED.equals(cell.getForeground()),
                "Renderer reuse must not cross out completed due dates.");
        cell = render(text, table, completedRow, statusColumn, true);
        check("Completed".equals(((JLabel) cell).getText())
                        && TaskClientStyle.GREEN.equals(cell.getForeground()),
                "Completed status must remain recognizable when selected, even with a past due date.");
        for (int column : new int[] {descriptionColumn, dateColumn, statusColumn}) {
            cell = render(text, table, overdueRow, column, true);
            check(TaskClientStyle.RED.equals(cell.getForeground()),
                    "Overdue text must remain recognizable in a selected row.");
        }
        cell = render(text, table, openRow, descriptionColumn, false);
        check(TaskClientStyle.TEXT.equals(cell.getForeground())
                        && !Boolean.TRUE.equals(cell.getFont().getAttributes().get(TextAttribute.STRIKETHROUGH)),
                "Renderer reuse must reset completed/overdue styling for an open task.");
        cell = render(text, table, openRow, dateColumn, false);
        check("".equals(((JLabel) cell).getText()), "An absent date must not retain a previous cell's date.");

        for (int row = 0; row < model.getRowCount(); row++) {
            for (int column = 0; column < model.getColumnCount(); column++) {
                TableCellRenderer renderer = table.convertColumnIndexToModel(column) == 0
                        ? checkbox : text;
                cell = render(renderer, table, row, column, false);
                check((row % 2 == 0 ? TaskClientStyle.SURFACE : TaskClientStyle.STRIPE).equals(cell.getBackground()),
                        "Text and checkbox cells must share alternating row backgrounds.");
                cell = render(renderer, table, row, column, true);
                check(TaskClientStyle.SELECTION.equals(cell.getBackground()),
                        "Selection must override striping consistently across text and checkbox cells.");
            }
        }
        table.setEnabled(false);
        JCheckBox completion = (JCheckBox) render(checkbox, table, completedRow, 0, false);
        check(completion.isSelected() && !completion.isEnabled(),
                "Disabled checkboxes must retain the saved completion state.");
        table.setEnabled(true);
        completion = (JCheckBox) render(checkbox, table, openRow, 0, false);
        check(!completion.isSelected() && completion.isEnabled(),
                "Checkbox renderer reuse must reset both completion and disabled state.");
    }

    private static Component render(TableCellRenderer renderer, JTable table,
            int row, int column, boolean selected) {
        return renderer.getTableCellRendererComponent(table, table.getValueAt(row, column), selected,
                false, row, column);
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
