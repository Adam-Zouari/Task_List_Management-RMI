import java.time.LocalDate;
import java.rmi.ConnectException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/** Runs one request at a time off the Swing event thread; callbacks run on that thread. */
final class TaskClientConnection implements AutoCloseable {
    interface Listener {
        void started(String description);
        void tasksReceived(ArrayList<Task> tasks);
        void failed(String message);
        void finished(boolean connected);
    }

    @FunctionalInterface
    interface Connector {
        TaskManager connect() throws Exception;
    }

    @FunctionalInterface
    private interface RemoteAction {
        void run(TaskManager service) throws RemoteException;
    }

    private final Connector connector;
    private final Listener listener;
    private TaskManager service;
    private SwingWorker<ArrayList<Task>, Void> worker;
    private boolean closed;

    TaskClientConnection(String host, int port, Listener listener) {
        this(() -> TaskTransport.connect(host, port), listener);
    }

    TaskClientConnection(Connector connector, Listener listener) {
        this.connector = connector;
        this.listener = listener;
    }

    boolean refresh() {
        return submit("Refreshing tasks...", true, false, null);
    }

    /** Poll the current service quietly; only a manual refresh may reconnect. */
    boolean refreshAutomatically() {
        return submit("Synchronizing tasks...", false, true, null);
    }

    boolean add(String description, LocalDate dueDate) {
        return mutate("Adding task...", service -> service.addTask(description, dueDate));
    }

    boolean update(String id, String description, LocalDate dueDate) {
        return mutate("Updating task...", service -> service.updateTask(id, description, dueDate));
    }

    boolean setCompleted(String id, boolean completed) {
        return mutate("Updating completion...", service -> service.setTaskCompleted(id, completed));
    }

    boolean remove(String id) {
        return mutate("Removing task...", service -> service.removeTask(id));
    }

    boolean clear() {
        return mutate("Deleting all tasks...", TaskManager::clearTasks);
    }

    private boolean mutate(String description, RemoteAction mutation) {
        return submit(description, false, false, mutation);
    }

    private boolean submit(String description, boolean lookupService, boolean automatic, RemoteAction mutation) {
        requireEventThread();
        if (closed || worker != null || (!lookupService && service == null)) {
            return false;
        }
        TaskManager previousService = service;
        SwingWorker<ArrayList<Task>, Void> request = new SwingWorker<ArrayList<Task>, Void>() {
            private TaskManager currentService;
            private boolean mutationAttempted;
            private boolean mutationCompleted;

            @Override
            protected ArrayList<Task> doInBackground() throws Exception {
                // Manual refresh looks up a fresh stub; polling never reconnects automatically.
                currentService = lookupService ? connector.connect() : previousService;
                if (mutation != null) {
                    mutationAttempted = true;
                    mutation.run(currentService);
                    mutationCompleted = true;
                }
                // Other clients may have changed the list during any operation.
                return currentService.getAllTasks();
            }

            @Override
            protected void done() {
                if (closed) {
                    return;
                }
                try {
                    ArrayList<Task> tasks = get();
                    service = currentService;
                    listener.tasksReceived(tasks);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail(e, automatic, "The request was interrupted. Refresh to reconnect before continuing.");
                } catch (CancellationException e) {
                    fail(e, automatic, "The request was cancelled. Refresh before continuing.");
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (mutationAttempted && !mutationCompleted
                            && (cause instanceof IllegalArgumentException || cause instanceof IllegalStateException)) {
                        service = currentService;
                        listener.failed("Task rejected: " + cause.getMessage());
                    } else {
                        String outcome = mutationAttempted
                                ? " The change may already have been applied. Refresh and check the list before trying again."
                                : " Start the server or check the connection, then use Refresh to reconnect.";
                        fail(cause, automatic, "Could not contact TaskManager." + outcome);
                    }
                } finally {
                    worker = null;
                    if (!closed) {
                        listener.finished(service != null);
                    }
                }
            }
        };
        worker = request;
        listener.started(description);
        if (!closed) {
            request.execute();
        }
        return true;
    }

    private void fail(Throwable cause, boolean automatic, String message) {
        service = null;
        if (cause instanceof ConnectException) {
            System.err.println("TaskManager connection refused. Check that the server is running "
                    + "and the host/ports are correct, then use Refresh.");
        } else {
            cause.printStackTrace(System.err);
        }
        if (!automatic) {
            listener.failed(message);
        }
    }

    @Override
    public void close() {
        requireEventThread();
        closed = true;
        service = null;
        if (worker != null) {
            worker.cancel(true);
            worker = null;
        }
    }

    private static void requireEventThread() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Client actions must run on the Swing event thread.");
        }
    }
}
