import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.ArrayList;

public interface TaskManager extends Remote {
    void addTask(String task) throws RemoteException;
    void removeTask(String task) throws RemoteException;
    ArrayList<String> getAllTasks() throws RemoteException;
    void clearTasks() throws RemoteException;
}
