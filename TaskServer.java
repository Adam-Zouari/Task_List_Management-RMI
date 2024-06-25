import java.rmi.Naming;
import java.rmi.registry.LocateRegistry;

public class TaskServer {
    public static void main(String[] args) {
        try {
            TaskManager taskManager = new TaskManagerImpl();
            LocateRegistry.createRegistry(1099);
            Naming.rebind("//localhost/TaskManager", taskManager);
            System.out.println("TaskManager bound in registry");
        } catch (Exception e) {
            System.err.println("TaskManager exception:");
            e.printStackTrace();
        }
    }
}
