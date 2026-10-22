import java.rmi.RemoteException;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;

/** Thread-safe, in-memory implementation of the shared task list. */
public class TaskManagerImpl extends UnicastRemoteObject implements TaskManager {
    private static final long serialVersionUID = 1L;

    /** Maximum length of a normalized task, measured in UTF-16 code units. */
    public static final int MAX_TASK_LENGTH = 1000;

    /** Maximum number of tasks held by a server, including duplicates. */
    public static final int MAX_TASKS = 10000;

    private final ArrayList<String> tasks = new ArrayList<>();

    /**
     * Exports on an ephemeral loopback port. Embedding callers must set
     * java.rmi.server.hostname to 127.0.0.1 before export so stubs advertise
     * the bound interface. TaskServer configures binding and advertisement together.
     */
    public TaskManagerImpl() throws RemoteException {
        this(0, TaskTransport.CLIENT_SOCKET_FACTORY,
                new TaskTransport.BoundServerSocketFactory("127.0.0.1"));
    }

    public TaskManagerImpl(int port, RMIClientSocketFactory clientSocketFactory,
            RMIServerSocketFactory serverSocketFactory) throws RemoteException {
        super(port, clientSocketFactory, serverSocketFactory);
    }

    @Override
    public synchronized void addTask(String task) throws RemoteException {
        String normalizedTask = normalizeTask(task);
        if (tasks.size() >= MAX_TASKS) {
            throw new IllegalStateException("The task list is full (maximum " + MAX_TASKS + " tasks).");
        }
        tasks.add(normalizedTask);
    }

    @Override
    public synchronized void removeTask(String task) throws RemoteException {
        tasks.remove(normalizeTask(task));
    }

    @Override
    public synchronized ArrayList<String> getAllTasks() throws RemoteException {
        return new ArrayList<>(tasks);
    }

    @Override
    public synchronized void clearTasks() throws RemoteException {
        tasks.clear();
    }

    private static String normalizeTask(String task) {
        if (task == null) {
            throw new IllegalArgumentException("A task must not be null.");
        }
        String normalizedTask = task.strip();
        if (normalizedTask.isEmpty()) {
            throw new IllegalArgumentException("A task must not be blank.");
        }
        if (normalizedTask.length() > MAX_TASK_LENGTH) {
            throw new IllegalArgumentException("A task must contain at most "
                    + MAX_TASK_LENGTH + " characters.");
        }
        return normalizedTask;
    }
}

