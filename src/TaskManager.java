import java.rmi.Remote;
import java.rmi.RemoteException;
import java.time.LocalDate;
import java.util.ArrayList;

/**
 * Ordered tasks with stable UUIDs and optional due dates. Each call is atomic;
 * a mutation and a subsequent snapshot are separate calls. A failed remote call
 * may have been applied, so mutations must not be automatically retried.
 */
public interface TaskManager extends Remote {
    /**
     * Appends an incomplete task with a generated UUID; descriptions may repeat.
     * @param description stripped text, 1..1000 UTF-16 code units
     * @param dueDate optional due date, or null
     * @throws IllegalArgumentException if the description is invalid
     * @throws IllegalStateException if the list already contains 10000 tasks
     * @throws RemoteException if communication or persistence fails
     */
    void addTask(String description, LocalDate dueDate) throws RemoteException;

    /**
     * Edits description/date, preserving UUID, completion, and list order.
     * @throws IllegalArgumentException if the ID is invalid/unknown or description invalid
     * @throws RemoteException if communication or persistence fails
     */
    void updateTask(String id, String description, LocalDate dueDate) throws RemoteException;

    /**
     * Changes completion while preserving all other fields and list order.
     * @throws IllegalArgumentException if the ID is invalid or unknown
     * @throws RemoteException if communication or persistence fails
     */
    void setTaskCompleted(String id, boolean completed) throws RemoteException;

    /**
     * Removes exactly the task with this UUID.
     * @throws IllegalArgumentException if the ID is invalid or unknown
     * @throws RemoteException if communication or persistence fails
     */
    void removeTask(String id) throws RemoteException;

    /**
     * Returns a detached mutable list of immutable tasks, in insertion order.
     * @throws RemoteException if communication fails
     */
    ArrayList<Task> getAllTasks() throws RemoteException;

    /**
     * Removes every task.
     * @throws RemoteException if communication or persistence fails
     */
    void clearTasks() throws RemoteException;
}
