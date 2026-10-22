import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.font.TextAttribute;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;

/** Desktop client for the persistent shared task list. */
public final class TaskClient extends JFrame {
    private static final long serialVersionUID = 1L;
    private final TaskTableModel taskModel = new TaskTableModel(this::setCompleted);
    private final JTable taskTable = new JTable(taskModel);
    private final JTextField descriptionField = new JTextField(30);
    private final JTextField dueDateField = new JTextField(10);
    private final JButton addButton = new JButton("Add Task");
    private final JButton editButton = new JButton("Edit Task");
    private final JButton removeButton = new JButton("Remove Task");
    private final JButton deleteAllButton = new JButton("Delete All");
    private final JButton refreshButton = new JButton("Refresh");
    private final JLabel status = new JLabel("Connecting...");
    private final transient TaskClientConnection connection;
    private final Timer refreshTimer;
    private boolean busy;
    private boolean connected;
    private boolean dialogOpen;
    private boolean addPending;
    private String submittedDescription;
    private String submittedDueText;

    public TaskClient() {
        this(TaskTransport.DEFAULT_HOST, TaskTransport.DEFAULT_REGISTRY_PORT);
    }

    public TaskClient(String host, int port) {
        super("Task Manager");
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Create TaskClient on the Swing event thread.");
        }
        host = TaskTransport.validateHost(host);
        TaskTransport.validatePort(port);
        setSize(860, 480);
        setMinimumSize(new Dimension(740, 360));
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setContentPane(content);

        JPanel heading = new JPanel(new BorderLayout(8, 8));
        heading.add(new JLabel("Shared tasks on " + host + ":" + port), BorderLayout.NORTH);
        JPanel entry = new JPanel(new BorderLayout(8, 8));
        descriptionField.setToolTipText("Enter a task and press Enter to add it.");
        descriptionField.getAccessibleContext().setAccessibleName("Task description");
        dueDateField.setToolTipText("Optional due date, for example 2026-10-25. Leave blank for no deadline.");
        dueDateField.getAccessibleContext().setAccessibleName("Optional due date, YYYY-MM-DD");
        JPanel descriptionEntry = new JPanel(new BorderLayout(6, 0));
        descriptionEntry.add(new JLabel("Task:"), BorderLayout.WEST);
        descriptionEntry.add(descriptionField, BorderLayout.CENTER);
        JPanel dateEntry = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        dateEntry.add(new JLabel("Due (YYYY-MM-DD):"));
        dateEntry.add(dueDateField);
        dateEntry.add(addButton);
        entry.add(descriptionEntry, BorderLayout.CENTER);
        entry.add(dateEntry, BorderLayout.EAST);
        heading.add(entry, BorderLayout.SOUTH);
        add(heading, BorderLayout.NORTH);

        taskTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        taskTable.setAutoCreateRowSorter(true);
        taskTable.setRowHeight(26);
        taskTable.setFillsViewportHeight(true);
        taskTable.getColumnModel().getColumn(0).setMaxWidth(60);
        taskTable.getColumnModel().getColumn(1).setPreferredWidth(400);
        taskTable.getColumnModel().getColumn(2).setPreferredWidth(110);
        taskTable.getColumnModel().getColumn(3).setPreferredWidth(95);
        taskTable.setDefaultRenderer(Object.class, new TaskRenderer());
        add(new JScrollPane(taskTable), BorderLayout.CENTER);

        connection = new TaskClientConnection(host, port, new TaskClientConnection.Listener() {
            @Override
            public void started(String description) {
                busy = true;
                status.setText(description);
                updateControls();
            }

            @Override
            public void tasksReceived(ArrayList<Task> tasks) {
                Task selection = selectedTask();
                String selectedId = selection == null ? null : selection.getId();
                taskModel.replaceTasks(tasks);
                if (selectedId != null) {
                    for (int row = 0; row < taskModel.getRowCount(); row++) {
                        if (selectedId.equals(taskModel.getTaskAt(row).getId())) {
                            int viewRow = taskTable.convertRowIndexToView(row);
                            taskTable.setRowSelectionInterval(viewRow, viewRow);
                            break;
                        }
                    }
                }
                if (addPending) {
                    // A user can keep typing during a request; preserve any new input.
                    if (descriptionField.getText().equals(submittedDescription)
                            && dueDateField.getText().equals(submittedDueText)) {
                        descriptionField.setText("");
                        dueDateField.setText("");
                    }
                    addPending = false;
                }
                long completed = tasks.stream().filter(Task::isCompleted).count();
                long overdue = tasks.stream().filter(task -> task.isOverdue(LocalDate.now())).count();
                status.setText(tasks.size() + " task(s), " + completed + " completed, " + overdue
                        + " overdue. Refreshes every 5 seconds.");
            }

            @Override
            public void failed(String message) {
                addPending = false;
                showMessage(message, "Task Manager", JOptionPane.ERROR_MESSAGE);
            }

            @Override
            public void finished(boolean available) {
                busy = false;
                connected = available;
                if (!connected) {
                    status.setText("Disconnected. Displayed tasks may be outdated; use Refresh to reconnect.");
                } else if (status.getText().endsWith("...")) {
                    status.setText("Connected. Refreshes every 5 seconds.");
                }
                updateControls();
            }
        });
        refreshTimer = new Timer(5_000, event -> {
            if (!dialogOpen && !taskTable.isEditing()) {
                connection.refreshAutomatically();
            }
        });

        addButton.addActionListener(event -> addTask());
        descriptionField.addActionListener(event -> addTask());
        dueDateField.addActionListener(event -> addTask());
        editButton.addActionListener(event -> editTask());
        removeButton.addActionListener(event -> {
            Task task = selectedTask();
            if (task != null) {
                connection.remove(task.getId());
            }
        });
        deleteAllButton.addActionListener(event -> clearTasks());
        refreshButton.addActionListener(event -> connection.refresh());
        taskTable.getSelectionModel().addListSelectionListener(event -> updateControls());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(editButton);
        buttons.add(removeButton);
        buttons.add(deleteAllButton);
        buttons.add(refreshButton);
        JPanel footer = new JPanel(new BorderLayout(8, 8));
        footer.add(buttons, BorderLayout.NORTH);
        footer.add(status, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent event) {
                refreshTimer.start();
                descriptionField.requestFocusInWindow();
            }

            @Override
            public void windowClosing(WindowEvent event) {
                closeConnection();
            }

            @Override
            public void windowClosed(WindowEvent event) {
                closeConnection();
            }
        });
        updateControls();
        connection.refresh();
    }

    private void addTask() {
        if (busy || !connected) {
            return;
        }
        try {
            String description = Task.normalizeDescription(descriptionField.getText());
            LocalDate dueDate = parseDueDate(dueDateField.getText());
            String originalText = descriptionField.getText();
            String originalDate = dueDateField.getText();
            if (connection.add(description, dueDate)) {
                submittedDescription = originalText;
                submittedDueText = originalDate;
                addPending = true;
            }
        } catch (IllegalArgumentException e) {
            showMessage(e.getMessage(), "Invalid task", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void editTask() {
        Task task = selectedTask();
        if (task == null || busy || !connected) {
            return;
        }
        JTextArea description = createDescriptionEditor(task.getDescription());
        JTextField dueDate = new JTextField(task.getDueDate() == null ? "" : task.getDueDate().toString(), 12);
        JPanel fields = new JPanel(new BorderLayout(6, 6));
        fields.add(new JLabel("Task description:"), BorderLayout.NORTH);
        fields.add(new JScrollPane(description), BorderLayout.CENTER);
        JPanel dateEntry = new JPanel(new BorderLayout(6, 0));
        dateEntry.add(new JLabel("Due date (YYYY-MM-DD, optional):"), BorderLayout.WEST);
        dateEntry.add(dueDate, BorderLayout.CENTER);
        fields.add(dateEntry, BorderLayout.SOUTH);
        dialogOpen = true;
        try {
            while (JOptionPane.showConfirmDialog(this, fields, "Edit task", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                try {
                    String text = Task.normalizeDescription(description.getText());
                    LocalDate date = parseDueDate(dueDate.getText());
                    connection.update(task.getId(), text, date);
                    return;
                } catch (IllegalArgumentException e) {
                    JOptionPane.showMessageDialog(this, e.getMessage(), "Invalid task", JOptionPane.WARNING_MESSAGE);
                }
            }
        } finally {
            dialogOpen = false;
        }
    }

    static JTextArea createDescriptionEditor(String description) {
        JTextArea editor = new JTextArea(description, 3, 32);
        editor.setLineWrap(true);
        editor.setWrapStyleWord(true);
        return editor;
    }

    private void setCompleted(String id, boolean completed) {
        connection.setCompleted(id, completed);
    }

    private Task selectedTask() {
        int selectedRow = taskTable.getSelectedRow();
        return selectedRow < 0 ? null : taskModel.getTaskAt(taskTable.convertRowIndexToModel(selectedRow));
    }

    private void clearTasks() {
        dialogOpen = true;
        try {
            int answer = JOptionPane.showConfirmDialog(this, "Delete every task for all connected clients?",
                    "Delete all tasks", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer == JOptionPane.YES_OPTION) {
                connection.clear();
            }
        } finally {
            dialogOpen = false;
        }
    }

    static LocalDate parseDueDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Use a valid due date in YYYY-MM-DD format, or leave it blank.", e);
        }
    }

    private void showMessage(String message, String title, int type) {
        boolean previousDialog = dialogOpen;
        dialogOpen = true;
        try {
            JOptionPane.showMessageDialog(this, message, title, type);
        } finally {
            dialogOpen = previousDialog;
        }
    }

    private void updateControls() {
        boolean canChange = !busy && connected;
        addButton.setEnabled(canChange);
        editButton.setEnabled(canChange && taskTable.getSelectedRow() >= 0);
        removeButton.setEnabled(canChange && taskTable.getSelectedRow() >= 0);
        deleteAllButton.setEnabled(canChange && taskModel.getRowCount() > 0);
        refreshButton.setEnabled(!busy);
        taskTable.setEnabled(canChange);
        taskModel.setEditable(canChange);
        descriptionField.setEnabled(connected);
        dueDateField.setEnabled(connected);
    }

    private void closeConnection() {
        refreshTimer.stop();
        connection.close();
    }

    private static final class TaskRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        private TaskRenderer() {
            putClientProperty("html.disable", Boolean.TRUE);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                boolean focused, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            Task task = ((TaskTableModel) table.getModel()).getTaskAt(table.convertRowIndexToModel(row));
            Font font = table.getFont();
            if (task.isCompleted() && table.convertColumnIndexToModel(column) == 1) {
                font = font.deriveFont(Collections.singletonMap(
                        TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON));
            }
            setFont(font);
            if (!selected) {
                setForeground(task.isCompleted() ? Color.GRAY
                        : task.isOverdue(LocalDate.now()) ? new Color(170, 35, 35) : table.getForeground());
            }
            return this;
        }
    }

    public static void main(String[] args) {
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            usage();
            return;
        }
        try {
            if (args.length > 2) {
                throw new IllegalArgumentException("Too many arguments.");
            }
            String host = args.length > 0 ? TaskTransport.validateHost(args[0]) : TaskTransport.DEFAULT_HOST;
            int port = args.length > 1 ? TaskTransport.parsePort(args[1]) : TaskTransport.DEFAULT_REGISTRY_PORT;
            if (GraphicsEnvironment.isHeadless()) {
                System.err.println("TaskClient needs a graphical desktop. Run it in a desktop session.");
                System.exit(1);
            }
            SwingUtilities.invokeLater(() -> new TaskClient(host, port).setVisible(true));
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid configuration: " + e.getMessage());
            usage();
            System.exit(2);
        }
    }

    private static void usage() {
        System.out.println("Usage: java -cp out TaskClient [host [registry-port]]");
        System.out.println("Defaults: 127.0.0.1 1099. Start TaskServer before connecting.");
    }
}
