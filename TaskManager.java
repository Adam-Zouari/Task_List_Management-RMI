import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.ArrayList;

/**
 * A shared, ordered task list accessed through Java RMI.
 *
 * <p>Tasks are stored in memory until the server exits. Duplicate task text is
 * allowed. Each call is atomic; separate calls are not a transaction. Task text
 * is normalized with {@link String#strip()} before adding or removing it.</p>
 */
public interface TaskManager extends Remote {
    /**
     * Appends a task to the list.
     *
     * @param task task text, with leading and trailing whitespace removed
     * @throws IllegalArgumentException if the normalized task is empty, null,
     *         or longer than 1000 UTF-16 code units
     * @throws IllegalStateException if the list already contains 10000 tasks
     * @throws RemoteException if the remote call fails; the task may already
     *         have been added, so callers must not automatically retry
     */
    void addTask(String task) throws RemoteException;

    /**
     * Removes the first matching normalized task, if one exists.
     *
     * @param task text of the task to remove
     * @throws IllegalArgumentException if the normalized task is empty, null,
     *         or longer than 1000 UTF-16 code units
     * @throws RemoteException if the remote call fails; its outcome may be unknown
     */
    void removeTask(String task) throws RemoteException;

    /**
     * Returns a detached snapshot of all tasks, in insertion order.
     * Changes to the returned list do not change the server's list.
     *
     * @return a mutable copy of the task list
     * @throws RemoteException if the remote call fails
     */
    ArrayList<String> getAllTasks() throws RemoteException;

    /**
     * Removes all tasks from the list.
     *
     * @throws RemoteException if the remote call fails; its outcome may be unknown
     */
    void clearTasks() throws RemoteException;
}
