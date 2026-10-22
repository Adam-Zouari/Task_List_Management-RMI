import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.table.AbstractTableModel;

/** A small table model that keeps task identity independent of descriptions and row sorting. */
final class TaskTableModel extends AbstractTableModel {
    private static final long serialVersionUID = 1L;
    private static final String[] COLUMNS = {"Done", "Task", "Due date", "Status"};
    private final ArrayList<Task> tasks = new ArrayList<>();
    private final transient BiConsumer<String, Boolean> completionChanged;
    private boolean editable;

    TaskTableModel(BiConsumer<String, Boolean> completionChanged) {
        this.completionChanged = completionChanged;
    }

    void replaceTasks(List<Task> replacement) {
        tasks.clear();
        tasks.addAll(replacement);
        fireTableDataChanged();
    }

    Task getTaskAt(int row) {
        return tasks.get(row);
    }

    void setEditable(boolean editable) {
        this.editable = editable;
    }

    @Override
    public int getRowCount() {
        return tasks.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int column) {
        if (column == 0) {
            return Boolean.class;
        }
        return column == 2 ? LocalDate.class : String.class;
    }

    @Override
    public Object getValueAt(int row, int column) {
        Task task = tasks.get(row);
        switch (column) {
            case 0:
                return task.isCompleted();
            case 1:
                return task.getDescription();
            case 2:
                return task.getDueDate();
            case 3:
                return task.isCompleted() ? "Completed" : task.isOverdue(LocalDate.now()) ? "Overdue" : "Open";
            default:
                throw new IndexOutOfBoundsException("Unknown task column: " + column);
        }
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return editable && column == 0;
    }

    @Override
    public void setValueAt(Object value, int row, int column) {
        if (isCellEditable(row, column) && value instanceof Boolean) {
            Task task = tasks.get(row);
            boolean completed = (Boolean) value;
            if (completed != task.isCompleted()) {
                completionChanged.accept(task.getId(), completed);
            }
        }
    }
}
