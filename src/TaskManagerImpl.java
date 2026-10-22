import java.io.IOException;
import java.nio.file.Path;
import java.rmi.NoSuchObjectException;
import java.rmi.RemoteException;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.UUID;

/** Thread-safe task service; persistent mutations are saved before memory is changed. */
public final class TaskManagerImpl implements TaskManager, AutoCloseable {
    public static final int MAX_TASK_LENGTH = Task.MAX_DESCRIPTION_LENGTH;
    public static final int MAX_TASKS = 10000;
    private ArrayList<Task> tasks;
    private final TaskStore store;
    private boolean closed;

    /** In-memory embedding constructor; configure the advertised hostname before export. */
    public TaskManagerImpl() throws RemoteException {
        this(0, TaskTransport.CLIENT_SOCKET_FACTORY,
                new TaskTransport.BoundServerSocketFactory("127.0.0.1"));
    }

    public TaskManagerImpl(int port, RMIClientSocketFactory clientSocketFactory,
            RMIServerSocketFactory serverSocketFactory) throws RemoteException {
        tasks = new ArrayList<>();
        store = null;
        UnicastRemoteObject.exportObject(this, port, clientSocketFactory, serverSocketFactory);
    }

    /** Validates storage before export, and closes its lock if load or export fails. */
    public TaskManagerImpl(int port, RMIClientSocketFactory clientSocketFactory,
            RMIServerSocketFactory serverSocketFactory, Path dataFile) throws IOException {
        TaskStore openedStore = new TaskStore(dataFile);
        try {
            tasks = openedStore.load();
            store = openedStore;
            UnicastRemoteObject.exportObject(this, port, clientSocketFactory, serverSocketFactory);
        } catch (IOException | RuntimeException | Error failure) {
            openedStore.close();
            throw failure;
        }
    }

    @Override
    public synchronized void addTask(String description, LocalDate dueDate) throws RemoteException {
        requireOpen();
        Task task = new Task(UUID.randomUUID().toString(), description, false, dueDate);
        if (tasks.size() >= MAX_TASKS) {
            throw new IllegalStateException("The task list is full (maximum " + MAX_TASKS + " tasks).");
        }
        ArrayList<Task> replacement = new ArrayList<>(tasks);
        replacement.add(task);
        commit(replacement);
    }

    @Override
    public synchronized void updateTask(String id, String description, LocalDate dueDate) throws RemoteException {
        requireOpen();
        int index = findTask(id);
        Task old = tasks.get(index);
        ArrayList<Task> replacement = new ArrayList<>(tasks);
        replacement.set(index, new Task(old.getId(), description, old.isCompleted(), dueDate));
        commit(replacement);
    }

    @Override
    public synchronized void setTaskCompleted(String id, boolean completed) throws RemoteException {
        requireOpen();
        int index = findTask(id);
        Task old = tasks.get(index);
        ArrayList<Task> replacement = new ArrayList<>(tasks);
        replacement.set(index, new Task(old.getId(), old.getDescription(), completed, old.getDueDate()));
        commit(replacement);
    }

    @Override
    public synchronized void removeTask(String id) throws RemoteException {
        requireOpen();
        int index = findTask(id);
        ArrayList<Task> replacement = new ArrayList<>(tasks);
        replacement.remove(index);
        commit(replacement);
    }

    @Override
    public synchronized ArrayList<Task> getAllTasks() throws RemoteException {
        requireOpen();
        return new ArrayList<>(tasks);
    }

    @Override
    public synchronized void clearTasks() throws RemoteException {
        requireOpen();
        commit(new ArrayList<>());
    }

    private int findTask(String id) {
        Task.validateId(id);
        for (int index = 0; index < tasks.size(); index++) {
            if (tasks.get(index).getId().equals(id)) { return index; }
        }
        throw new IllegalArgumentException("The task no longer exists. Refresh the task list.");
    }

    private void commit(ArrayList<Task> replacement) throws RemoteException {
        if (store != null) {
            try { store.save(replacement); }
            catch (IOException failure) {
                throw new RemoteException("Could not save tasks; no changes were applied.", failure);
            }
        }
        tasks = replacement;
    }

    private void requireOpen() {
        if (closed) { throw new IllegalStateException("The task service is closed."); }
    }

    @Override
    public synchronized void close() {
        if (closed) { return; }
        closed = true;
        try { UnicastRemoteObject.unexportObject(this, true); }
        catch (NoSuchObjectException ignored) {
            // The server may already have unexported this service.
        }
        if (store != null) { store.close(); }
    }
}
