import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.util.ArrayList;

public class TaskClient extends JFrame {
    private TaskManager service;
    private DefaultListModel<String> taskListModel;
    private JList<String> taskList;

    public TaskClient() {
        super("Task Manager");
        setSize(400, 300);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // Initialize RMI connection
        try {
            service = (TaskManager) Naming.lookup("rmi://localhost/TaskManager");
        } catch (Exception e) {
            System.err.println("Failed to connect to TaskManager:");
            e.printStackTrace();
            System.exit(1);
        }

        // Initialize task list model and JList
        taskListModel = new DefaultListModel<>();
        taskList = new JList<>(taskListModel);
        JScrollPane scrollPane = new JScrollPane(taskList);
        add(scrollPane, BorderLayout.CENTER);

        // Initialize buttons panel
        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton addButton = new JButton("Add Task");
        addButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String task = JOptionPane.showInputDialog("Enter task:");
                if (task != null && !task.isEmpty()) {
                    try {
                        service.addTask(task);
                        taskListModel.addElement(task);
                    } catch (RemoteException ex) {
                        ex.printStackTrace();
                        JOptionPane.showMessageDialog(TaskClient.this, "Failed to add task.", "Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        });
        buttonPanel.add(addButton);

        JButton removeButton = new JButton("Remove Task");
        removeButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String selectedTask = taskList.getSelectedValue();
                if (selectedTask != null) {
                    try {
                        service.removeTask(selectedTask);
                        taskListModel.removeElement(selectedTask); // Remove the selected task from the list model
                    } catch (RemoteException ex) {
                        ex.printStackTrace();
                        JOptionPane.showMessageDialog(TaskClient.this, "Failed to remove task.", "Error", JOptionPane.ERROR_MESSAGE);
                    }
                } else {
                    JOptionPane.showMessageDialog(TaskClient.this, "Please select a task to remove.", "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        buttonPanel.add(removeButton);

        JButton deleteAllButton = new JButton("Delete All");
        deleteAllButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    service.clearTasks(); // Clear tasks on the server
                    taskListModel.clear(); // Clear tasks in the list model
                } catch (RemoteException ex) {
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(TaskClient.this, "Failed to delete all tasks.", "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        buttonPanel.add(deleteAllButton);

        add(buttonPanel, BorderLayout.SOUTH);

        // Display the frame
        setVisible(true);

        // Populate initial task list
        showTaskList();
    }

    private void showTaskList() {
        try {
            ArrayList<String> tasks = new ArrayList<>(service.getAllTasks());
            for (String task : tasks) {
                taskListModel.addElement(task);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(TaskClient.this, "Failed to fetch tasks.", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new TaskClient();
            }
        });
    }
}
