import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

public final class TaskClient extends JFrame {
    private static final long serialVersionUID = 1L;
    private final DefaultListModel<String> taskListModel = new DefaultListModel<>();
    private final JList<String> taskList = new JList<>(taskListModel);
    private final JButton addButton = new JButton("Add Task");
    private final JButton removeButton = new JButton("Remove Task");
    private final JButton deleteAllButton = new JButton("Delete All");
    private final JButton refreshButton = new JButton("Refresh");
    private final JLabel status = new JLabel("Connecting...");
    private final transient TaskClientConnection connection;
    private boolean busy;
    private boolean connected;

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
        setSize(640, 400);
        setMinimumSize(new Dimension(560, 300));
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setContentPane(content);
        add(new JLabel("Shared tasks on " + host + ":" + port), BorderLayout.NORTH);
        taskList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // Task descriptions are plain text, even when they begin with an HTML tag.
        DefaultListCellRenderer renderer = new DefaultListCellRenderer();
        renderer.putClientProperty("html.disable", Boolean.TRUE);
        taskList.setCellRenderer(renderer);
        add(new JScrollPane(taskList), BorderLayout.CENTER);

        connection = new TaskClientConnection(host, port, new TaskClientConnection.Listener() {
            @Override
            public void started(String description) {
                busy = true;
                status.setText(description);
                updateControls();
            }

            @Override
            public void tasksReceived(ArrayList<String> tasks) {
                String selection = taskList.getSelectedValue();
                taskListModel.clear();
                for (String task : tasks) {
                    taskListModel.addElement(task);
                }
                if (selection != null) {
                    taskList.setSelectedValue(selection, true);
                }
                status.setText(tasks.size() + " task(s). Use Refresh to see changes from other clients.");
            }

            @Override
            public void failed(String message) {
                JOptionPane.showMessageDialog(TaskClient.this, message, "Task Manager", JOptionPane.ERROR_MESSAGE);
            }

            @Override
            public void finished(boolean available) {
                busy = false;
                connected = available;
                if (!connected) {
                    status.setText("Disconnected. Displayed tasks may be outdated; use Refresh to reconnect.");
                } else if (status.getText().endsWith("...")) {
                    status.setText("Connected. Use Refresh to see the current task list.");
                }
                updateControls();
            }
        });

        JPanel buttonPanel = new JPanel(new FlowLayout());
        addButton.addActionListener(event -> addTask());
        removeButton.addActionListener(event -> {
            String selectedTask = taskList.getSelectedValue();
            if (selectedTask != null) {
                connection.remove(selectedTask);
            }
        });
        deleteAllButton.addActionListener(event -> {
            int answer = JOptionPane.showConfirmDialog(TaskClient.this,
                    "Delete every task for all connected clients?", "Delete all tasks",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer == JOptionPane.YES_OPTION) {
                connection.clear();
            }
        });
        refreshButton.addActionListener(event -> connection.refresh());
        taskList.addListSelectionListener(event -> updateControls());
        buttonPanel.add(addButton);
        buttonPanel.add(removeButton);
        buttonPanel.add(deleteAllButton);
        buttonPanel.add(refreshButton);
        JPanel footer = new JPanel(new BorderLayout(4, 4));
        footer.add(buttonPanel, BorderLayout.NORTH);
        footer.add(status, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                connection.close();
            }

            @Override
            public void windowClosed(WindowEvent event) {
                connection.close();
            }
        });
        updateControls();
        connection.refresh();
    }

    private void addTask() {
        String task = JOptionPane.showInputDialog(this, "Enter task (maximum "
                + TaskManagerImpl.MAX_TASK_LENGTH + " characters):", "Add task", JOptionPane.PLAIN_MESSAGE);
        if (task == null) {
            return;
        }
        task = task.strip();
        if (task.isEmpty() || task.length() > TaskManagerImpl.MAX_TASK_LENGTH) {
            JOptionPane.showMessageDialog(this, "Enter a nonblank task of at most "
                    + TaskManagerImpl.MAX_TASK_LENGTH + " characters.", "Invalid task", JOptionPane.WARNING_MESSAGE);
            return;
        }
        connection.add(task);
    }

    private void updateControls() {
        addButton.setEnabled(!busy && connected);
        removeButton.setEnabled(!busy && connected && taskList.getSelectedIndex() >= 0);
        deleteAllButton.setEnabled(!busy && connected && !taskListModel.isEmpty());
        refreshButton.setEnabled(!busy);
        taskList.setEnabled(!busy && connected);
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
