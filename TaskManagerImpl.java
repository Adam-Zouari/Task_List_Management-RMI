import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;

public class TaskManagerImpl extends UnicastRemoteObject implements TaskManager {
    private ArrayList<String> tasks;

    public TaskManagerImpl() throws RemoteException {
        super();
        tasks = new ArrayList<>();
    }

    @Override
    public void addTask(String task) throws RemoteException {
        tasks.add(task);
    }

    @Override
    public void removeTask(String task) throws RemoteException {
        tasks.remove(task);
    }

    @Override
    public ArrayList<String> getAllTasks() throws RemoteException {
        return tasks;
    }

    @Override
    public void clearTasks() throws RemoteException {
        tasks.clear(); // Implementation to clear all tasks
    }
}

