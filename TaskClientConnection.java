import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/** Runs one request at a time off the Swing event thread; callbacks run on that thread. */
final class TaskClientConnection implements AutoCloseable {
    interface Listener {
        void started(String description);
        void tasksReceived(ArrayList<String> tasks);
        void failed(String message);
        void finished(boolean connected);
    }

    @FunctionalInterface
    interface Connector {
        TaskManager connect() throws Exception;
    }

    private enum Operation {
        REFRESH("Refreshing tasks..."), ADD("Adding task..."), REMOVE("Removing task..."),
        CLEAR("Deleting all tasks...");

        private final String description;

        Operation(String description) {
            this.description = description;
        }
    }

    private final Connector connector;
    private final Listener listener;
    private TaskManager service;
    private SwingWorker<ArrayList<String>, Void> worker;
    private boolean closed;

    TaskClientConnection(String host, int port, Listener listener) {
        this(() -> TaskTransport.connect(host, port), listener);
    }

    TaskClientConnection(Connector connector, Listener listener) {
        this.connector = connector;
        this.listener = listener;
    }

    boolean refresh() {
        return submit(Operation.REFRESH, null);
    }

    boolean add(String task) {
        return submit(Operation.ADD, task);
    }

    boolean remove(String task) {
        return submit(Operation.REMOVE, task);
    }

    boolean clear() {
        return submit(Operation.CLEAR, null);
    }

    private boolean submit(Operation operation, String task) {
        requireEventThread();
        if (closed || worker != null || (operation != Operation.REFRESH && service == null)) {
            return false;
        }
        TaskManager previousService = service;
        SwingWorker<ArrayList<String>, Void> request = new SwingWorker<ArrayList<String>, Void>() {
            private TaskManager currentService;
            private boolean mutationAttempted;
            private boolean mutationCompleted;

            @Override
            protected ArrayList<String> doInBackground() throws Exception {
                // Refresh always looks up a fresh stub, allowing recovery after a server restart.
                currentService = operation == Operation.REFRESH ? connector.connect() : previousService;
                if (operation != Operation.REFRESH) {
                    mutationAttempted = true;
                    switch (operation) {
                        case ADD:
                            currentService.addTask(task);
                            break;
                        case REMOVE:
                            currentService.removeTask(task);
                            break;
                        case CLEAR:
                            currentService.clearTasks();
                            break;
                        default:
                            throw new IllegalStateException("Unknown task operation.");
                    }
                    mutationCompleted = true;
                }
                // Never patch the local list: other clients may have changed the server state.
                return currentService.getAllTasks();
            }

            @Override
            protected void done() {
                if (closed) {
                    return;
                }
                try {
                    ArrayList<String> tasks = get();
                    service = currentService;
                    listener.tasksReceived(tasks);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    service = null;
                    listener.failed("The request was interrupted. Refresh to reconnect before continuing.");
                } catch (CancellationException e) {
                    service = null;
                    listener.failed("The request was cancelled. Refresh before continuing.");
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (mutationAttempted && !mutationCompleted
                            && (cause instanceof IllegalArgumentException || cause instanceof IllegalStateException)) {
                        service = currentService;
                        listener.failed("Task rejected: " + cause.getMessage());
                    } else {
                        service = null;
                        cause.printStackTrace(System.err);
                        String outcome = mutationAttempted
                                ? " The change may already have been applied. Refresh and check the list before trying again."
                                : " Start the server or check the connection, then use Refresh to reconnect.";
                        listener.failed("Could not contact TaskManager." + outcome);
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
        listener.started(operation.description);
        if (!closed) {
            request.execute();
        }
        return true;
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
