import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.font.TextAttribute;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
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
import javax.swing.table.TableCellRenderer;

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
    private final JLabel connectionStatus = new JLabel();
    private final JLabel summary = new JLabel("0 task(s), 0 completed, 0 overdue.");
    private final JLabel status = new JLabel("Connecting...");
    private final String serverAddress;
    private final transient TaskClientConnection connection;
    private final Timer refreshTimer;
    private boolean busy;
    private boolean connected;
    private boolean dialogOpen;
    private boolean addPending;
    private String submittedDescription;
    private String submittedDueText;
    private LocalDate displayedDate;

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
        serverAddress = host + ":" + port;
        TaskClientStyle.install();
        setSize(1100, 640);
        setMinimumSize(new Dimension(900, 480));
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(TaskClientStyle.BACKGROUND);
        setContentPane(content);
        content.add(createHeader(), BorderLayout.NORTH);

        TaskClientStyle.field(descriptionField);
        TaskClientStyle.field(dueDateField);
        descriptionField.setMinimumSize(new Dimension(160, descriptionField.getPreferredSize().height));
        dueDateField.setMinimumSize(dueDateField.getPreferredSize());
        TaskClientStyle.button(addButton, true, false);
        TaskClientStyle.button(editButton, false, false);
        TaskClientStyle.button(removeButton, false, false);
        TaskClientStyle.button(deleteAllButton, false, true);
        TaskClientStyle.button(refreshButton, false, false);
        descriptionField.setToolTipText("Enter a task and press Enter to add it.");
        descriptionField.getAccessibleContext().setAccessibleName("Task description");
        dueDateField.setToolTipText("Optional due date, for example 2026-10-25. Leave blank for no deadline.");
        dueDateField.getAccessibleContext().setAccessibleName("Optional due date, YYYY-MM-DD");
        JPanel body = new JPanel(new BorderLayout(0, 12));
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(16, 24, 16, 24));
        JPanel entryAndActions = new JPanel(new BorderLayout(0, 12));
        entryAndActions.setOpaque(false);
        entryAndActions.add(createEntry(), BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(editButton);
        buttons.add(removeButton);
        buttons.add(deleteAllButton);
        buttons.add(refreshButton);
        entryAndActions.add(buttons, BorderLayout.SOUTH);
        body.add(entryAndActions, BorderLayout.NORTH);
        configureTable();
        JScrollPane tableScroll = new JScrollPane(taskTable);
        tableScroll.setBorder(TaskClientStyle.outline(TaskClientStyle.BORDER));
        tableScroll.getViewport().setBackground(TaskClientStyle.SURFACE);
        body.add(tableScroll, BorderLayout.CENTER);
        content.add(body, BorderLayout.CENTER);
        content.add(createFooter(), BorderLayout.SOUTH);
        connection = new TaskClientConnection(host, port, new TaskClientConnection.Listener() {
            @Override
            public void started(String description) {
                busy = true;
                status.setText(description);
                updateControls();
            }

            @Override
            public void tasksReceived(ArrayList<Task> tasks) {
                LocalDate today = LocalDate.now();
                displayTasks(tasks, today);
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
                long overdue = tasks.stream().filter(task -> task.isOverdue(today)).count();
                summary.setText(tasks.size() + " task(s), " + completed + " completed, " + overdue + " overdue.");
                status.setText("Refreshes every 5 seconds.");
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
                    status.setText("Displayed tasks may be outdated. Use Refresh to reconnect.");
                } else if (status.getText().endsWith("...")) {
                    status.setText("Refreshes every 5 seconds.");
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

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, TaskClientStyle.BORDER),
                BorderFactory.createEmptyBorder(16, 24, 16, 24)));
        JLabel title = new JLabel("Task Manager");
        title.setFont(TaskClientStyle.FONT.deriveFont(Font.BOLD, 26f));
        title.setForeground(TaskClientStyle.TEXT);
        header.add(title, BorderLayout.WEST);
        connectionStatus.setFont(TaskClientStyle.SMALL_FONT.deriveFont(Font.BOLD));
        connectionStatus.setOpaque(true);
        connectionStatus.putClientProperty("html.disable", Boolean.TRUE);
        JPanel badge = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        badge.setOpaque(false);
        badge.add(connectionStatus);
        header.add(badge, BorderLayout.EAST);
        return header;
    }

    private JPanel createEntry() {
        JPanel entry = new JPanel(new GridBagLayout());
        entry.setBorder(BorderFactory.createCompoundBorder(TaskClientStyle.outline(TaskClientStyle.BORDER),
                BorderFactory.createEmptyBorder(12, 16, 12, 16)));
        GridBagConstraints cell = new GridBagConstraints();
        cell.gridy = 0;
        cell.fill = GridBagConstraints.HORIZONTAL;
        JLabel taskLabel = new JLabel("Task:");
        taskLabel.setLabelFor(descriptionField);
        cell.insets = new Insets(0, 0, 0, 8);
        entry.add(taskLabel, cell);
        cell.weightx = 1;
        cell.insets = new Insets(0, 0, 0, 24);
        entry.add(descriptionField, cell);
        cell.weightx = 0;
        cell.insets = new Insets(0, 0, 0, 8);
        JLabel dateLabel = new JLabel("Due date (YYYY-MM-DD):");
        dateLabel.setLabelFor(dueDateField);
        entry.add(dateLabel, cell);
        entry.add(dueDateField, cell);
        cell.insets = new Insets(0, 8, 0, 0);
        entry.add(addButton, cell);
        return entry;
    }

    private JPanel createFooter() {
        JPanel footer = new JPanel(new BorderLayout(16, 0));
        footer.setBackground(TaskClientStyle.BACKGROUND);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, TaskClientStyle.BORDER),
                BorderFactory.createEmptyBorder(12, 24, 12, 24)));
        summary.setFont(TaskClientStyle.SMALL_FONT);
        summary.setForeground(TaskClientStyle.TEXT);
        summary.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, TaskClientStyle.BORDER),
                BorderFactory.createEmptyBorder(0, 0, 0, 16)));
        status.setFont(TaskClientStyle.SMALL_FONT);
        status.setForeground(TaskClientStyle.MUTED);
        footer.add(summary, BorderLayout.WEST);
        footer.add(status, BorderLayout.CENTER);
        return footer;
    }

    private void configureTable() {
        taskTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        taskTable.setAutoCreateRowSorter(true);
        taskTable.setFont(TaskClientStyle.FONT);
        taskTable.setForeground(TaskClientStyle.TEXT);
        taskTable.setBackground(TaskClientStyle.SURFACE);
        taskTable.setSelectionBackground(TaskClientStyle.SELECTION);
        taskTable.setSelectionForeground(TaskClientStyle.TEXT);
        taskTable.setRowHeight(36);
        taskTable.setFillsViewportHeight(true);
        taskTable.setShowVerticalLines(false);
        taskTable.setShowHorizontalLines(true);
        taskTable.setGridColor(new Color(0xE8EDF4));
        taskTable.setIntercellSpacing(new Dimension(0, 1));
        taskTable.getColumnModel().getColumn(0).setMinWidth(60);
        taskTable.getColumnModel().getColumn(0).setMaxWidth(68);
        taskTable.getColumnModel().getColumn(1).setMinWidth(160);
        taskTable.getColumnModel().getColumn(1).setPreferredWidth(550);
        taskTable.getColumnModel().getColumn(2).setMinWidth(136);
        taskTable.getColumnModel().getColumn(2).setPreferredWidth(160);
        taskTable.getColumnModel().getColumn(3).setMinWidth(120);
        taskTable.getColumnModel().getColumn(3).setPreferredWidth(140);
        taskTable.setDefaultRenderer(Object.class, new TaskRenderer());
        taskTable.setDefaultRenderer(Boolean.class, new CompletionRenderer());
        JCheckBox completion = new JCheckBox();
        completion.setHorizontalAlignment(JCheckBox.CENTER);
        completion.setBackground(TaskClientStyle.SELECTION);
        taskTable.setDefaultEditor(Boolean.class, new DefaultCellEditor(completion));
        taskTable.getTableHeader().setPreferredSize(new Dimension(0, 36));
        taskTable.getTableHeader().setDefaultRenderer(new HeaderRenderer(
                taskTable.getTableHeader().getDefaultRenderer()));
    }

    private void displayTasks(ArrayList<Task> tasks, LocalDate today) {
        boolean changed = !today.equals(displayedDate) || taskModel.getRowCount() != tasks.size();
        for (int row = 0; !changed && row < tasks.size(); row++) {
            changed = !tasks.get(row).equals(taskModel.getTaskAt(row));
        }
        if (changed) {
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
        }
        displayedDate = today;
        taskTable.repaint();
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
        TaskClientStyle.field(dueDate);
        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints cell = new GridBagConstraints();
        cell.gridx = 0;
        cell.gridy = 0;
        cell.anchor = GridBagConstraints.NORTHWEST;
        cell.insets = new Insets(8, 0, 12, 16);
        JLabel descriptionLabel = new JLabel("Task:");
        descriptionLabel.setLabelFor(description);
        fields.add(descriptionLabel, cell);
        cell.gridx = 1;
        cell.weightx = 1;
        cell.fill = GridBagConstraints.BOTH;
        cell.insets = new Insets(0, 0, 12, 0);
        JScrollPane descriptionScroll = new JScrollPane(description);
        descriptionScroll.setBorder(TaskClientStyle.outline(TaskClientStyle.BORDER));
        fields.add(descriptionScroll, cell);
        cell.gridx = 0;
        cell.gridy = 1;
        cell.weightx = 0;
        cell.fill = GridBagConstraints.NONE;
        cell.anchor = GridBagConstraints.WEST;
        cell.insets = new Insets(0, 0, 0, 16);
        JLabel dateLabel = new JLabel("Due date (YYYY-MM-DD):");
        dateLabel.setLabelFor(dueDate);
        fields.add(dateLabel, cell);
        cell.gridx = 1;
        cell.fill = GridBagConstraints.HORIZONTAL;
        cell.insets = new Insets(0, 0, 0, 0);
        dueDate.setToolTipText("Leave blank for no deadline.");
        fields.add(dueDate, cell);
        dialogOpen = true;
        try {
            while (showDialog(fields, "Edit task", JOptionPane.PLAIN_MESSAGE, "Save Changes", true, false)) {
                try {
                    String text = Task.normalizeDescription(description.getText());
                    LocalDate date = parseDueDate(dueDate.getText());
                    connection.update(task.getId(), text, date);
                    return;
                } catch (IllegalArgumentException e) {
                    showMessage(e.getMessage(), "Invalid task", JOptionPane.WARNING_MESSAGE);
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
        editor.setFont(TaskClientStyle.FONT);
        editor.setForeground(TaskClientStyle.TEXT);
        editor.setBackground(TaskClientStyle.SURFACE);
        editor.setSelectionColor(TaskClientStyle.SELECTION);
        editor.setSelectedTextColor(TaskClientStyle.TEXT);
        editor.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
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
            if (showDialog("Delete every task for all connected clients?", "Delete all tasks",
                    JOptionPane.WARNING_MESSAGE, "Delete All", true, true)) {
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
            showDialog(message, title, type, "OK", false, false);
        } finally {
            dialogOpen = previousDialog;
        }
    }

    private boolean showDialog(Object message, String title, int type, String action,
            boolean allowCancel, boolean destructive) {
        JButton accept = new JButton(action);
        TaskClientStyle.button(accept, !destructive, destructive);
        JButton cancel = new JButton("Cancel");
        TaskClientStyle.button(cancel, false, false);
        Object[] options = !allowCancel ? new Object[] {accept}
                : destructive ? new Object[] {cancel, accept} : new Object[] {accept, cancel};
        JButton initial = destructive ? cancel : accept;
        if (message instanceof String) {
            JTextArea text = new JTextArea((String) message, 0, 42);
            text.setFont(TaskClientStyle.FONT);
            text.setForeground(TaskClientStyle.TEXT);
            text.setBackground(TaskClientStyle.SURFACE);
            text.setEditable(false);
            text.setFocusable(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            message = text;
        }
        JOptionPane pane = new JOptionPane(message, type, JOptionPane.DEFAULT_OPTION, null, options, initial);
        pane.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        accept.addActionListener(event -> pane.setValue(JOptionPane.OK_OPTION));
        cancel.addActionListener(event -> pane.setValue(JOptionPane.CANCEL_OPTION));
        JDialog dialog = pane.createDialog(this, title);
        dialog.getRootPane().setDefaultButton(initial);
        try {
            dialog.setVisible(true);
            return Integer.valueOf(JOptionPane.OK_OPTION).equals(pane.getValue());
        } finally {
            dialog.dispose();
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
        boolean connecting = busy && !connected;
        connectionStatus.setText("\u25cf  " + (connected ? "Connected" : connecting ? "Connecting..." : "Disconnected")
                + "  \u00b7  " + serverAddress);
        connectionStatus.setForeground(connected ? TaskClientStyle.GREEN
                : connecting ? TaskClientStyle.BLUE : TaskClientStyle.RED);
        connectionStatus.setBackground(connected ? TaskClientStyle.GREEN_SURFACE
                : connecting ? TaskClientStyle.STRIPE : TaskClientStyle.RED_SURFACE);
        connectionStatus.setBorder(BorderFactory.createCompoundBorder(
                TaskClientStyle.outline(connected ? new Color(0xC8E4D0) : TaskClientStyle.BORDER),
                BorderFactory.createEmptyBorder(7, 12, 7, 12)));
        connectionStatus.setToolTipText(connectionStatus.getText());
        status.setToolTipText(status.getText());
    }

    private void closeConnection() {
        refreshTimer.stop();
        connection.close();
    }

    private static Color rowBackground(int row, boolean selected) {
        return selected ? TaskClientStyle.SELECTION : row % 2 == 0 ? TaskClientStyle.SURFACE : TaskClientStyle.STRIPE;
    }

    static final class TaskRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        TaskRenderer() {
            putClientProperty("html.disable", Boolean.TRUE);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                boolean focused, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            Task task = ((TaskTableModel) table.getModel()).getTaskAt(table.convertRowIndexToModel(row));
            Font font = table.getFont();
            int modelColumn = table.convertColumnIndexToModel(column);
            if (task.isCompleted() && modelColumn == 1) {
                font = font.deriveFont(Collections.singletonMap(
                        TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON));
            }
            setFont(font);
            setBackground(rowBackground(row, selected));
            setForeground(task.isCompleted() ? modelColumn == 3 ? TaskClientStyle.GREEN : TaskClientStyle.MUTED
                    : task.isOverdue(LocalDate.now()) ? TaskClientStyle.RED : TaskClientStyle.TEXT);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(focused ? TaskClientStyle.BLUE : getBackground()),
                    BorderFactory.createEmptyBorder(0, 11, 0, 11)));
            setHorizontalAlignment(LEFT);
            return this;
        }
    }

    static final class CompletionRenderer extends JCheckBox implements TableCellRenderer {
        private static final long serialVersionUID = 1L;

        CompletionRenderer() {
            setHorizontalAlignment(CENTER);
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                boolean focused, int row, int column) {
            setSelected(Boolean.TRUE.equals(value));
            setEnabled(table.isEnabled());
            setBackground(rowBackground(row, selected));
            setBorderPainted(true);
            setBorder(BorderFactory.createLineBorder(focused ? TaskClientStyle.BLUE : getBackground()));
            return this;
        }
    }

    private static final class HeaderRenderer implements TableCellRenderer {
        private final TableCellRenderer delegate;

        HeaderRenderer(TableCellRenderer delegate) {
            this.delegate = delegate;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                boolean focused, int row, int column) {
            Component component = delegate.getTableCellRendererComponent(table, value, selected, focused, row, column);
            component.setFont(TaskClientStyle.FONT.deriveFont(Font.BOLD));
            component.setForeground(TaskClientStyle.TEXT);
            component.setBackground(TaskClientStyle.BACKGROUND);
            if (component instanceof JLabel) {
                JLabel label = (JLabel) component;
                label.setHorizontalAlignment(table.convertColumnIndexToModel(column) == 0 ? JLabel.CENTER : JLabel.LEFT);
                label.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, TaskClientStyle.BORDER),
                        BorderFactory.createEmptyBorder(0, 12, 0, 12)));
            }
            return component;
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
