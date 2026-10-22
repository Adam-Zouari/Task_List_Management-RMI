import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.NoSuchObjectException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

/** Owns both RMI endpoints, including cleanup after a partial startup. */
public final class TaskServer implements AutoCloseable {
    private final Registry registry;
    private final TaskManagerImpl service;
    private final String bindAddress;
    private final Path dataFile;
    private boolean closed;

    private TaskServer(Registry registry, TaskManagerImpl service, String bindAddress, Path dataFile) {
        this.registry = registry;
        this.service = service;
        this.bindAddress = bindAddress;
        this.dataFile = dataFile;
    }

    public static TaskServer start(String bindHost, int registryPort, int servicePort) throws IOException {
        return start(bindHost, registryPort, servicePort, Paths.get("data", "tasks.tsv"));
    }

    public static TaskServer start(String bindHost, int registryPort, int servicePort, Path dataFile)
            throws IOException {
        TaskTransport.validatePort(registryPort);
        TaskTransport.validatePort(servicePort);
        if (registryPort == servicePort) {
            throw new IllegalArgumentException("Registry and service ports must be different.");
        }
        if (dataFile == null) {
            throw new IllegalArgumentException("Data file must not be null.");
        }
        dataFile = dataFile.toAbsolutePath().normalize();
        InetAddress address = InetAddress.getByName(TaskTransport.validateHost(bindHost));
        if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
            throw new IllegalArgumentException("Use a specific local interface address, such as 127.0.0.1.");
        }

        // Set before exporting either endpoint so returned stubs advertise the bound interface.
        System.setProperty("java.rmi.server.hostname", address.getHostAddress());
        TaskTransport.BoundServerSocketFactory sockets =
                new TaskTransport.BoundServerSocketFactory(address.getHostAddress());
        Registry registry = null;
        TaskManagerImpl service = null;
        try {
            registry = LocateRegistry.createRegistry(registryPort, TaskTransport.CLIENT_SOCKET_FACTORY, sockets);
            service = new TaskManagerImpl(servicePort, TaskTransport.CLIENT_SOCKET_FACTORY, sockets, dataFile);
            registry.rebind(TaskTransport.BINDING_NAME, service);
            return new TaskServer(registry, service, address.getHostAddress(), dataFile);
        } catch (IOException | RuntimeException e) {
            if (service != null) {
                service.close();
            }
            unexport(registry);
            throw e;
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            service.close();
            unexport(registry);
        }
    }

    private static void unexport(java.rmi.Remote remote) {
        if (remote != null) {
            try {
                UnicastRemoteObject.unexportObject(remote, true);
            } catch (NoSuchObjectException ignored) {
                // Already unexported; cleanup is deliberately idempotent.
            }
        }
    }

    public static void main(String[] args) {
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            usage();
            return;
        }
        try {
            if (args.length > 4) {
                throw new IllegalArgumentException("Too many arguments.");
            }
            String host = args.length > 0 ? TaskTransport.validateHost(args[0]) : TaskTransport.DEFAULT_HOST;
            int registryPort = args.length > 1 ? TaskTransport.parsePort(args[1])
                    : TaskTransport.DEFAULT_REGISTRY_PORT;
            int servicePort = args.length > 2 ? TaskTransport.parsePort(args[2])
                    : TaskTransport.DEFAULT_SERVICE_PORT;
            if (args.length > 3 && args[3].isBlank()) {
                throw new IllegalArgumentException("Data file must not be blank.");
            }
            Path dataFile = args.length > 3 ? Paths.get(args[3]) : Paths.get("data", "tasks.tsv");
            TaskServer server = start(host, registryPort, servicePort, dataFile);
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(server::close, "task-server-shutdown"));
            } catch (RuntimeException e) {
                server.close();
                throw e;
            }
            System.out.printf("TaskManager ready on %s (registry: %d, service: %d).%n",
                    server.bindAddress, registryPort, servicePort);
            System.out.println("Tasks are saved to " + server.dataFile);
            System.out.println("Press Ctrl+C to stop the server.");
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid configuration: " + e.getMessage());
            usage();
            System.exit(2);
        } catch (IOException e) {
            System.err.println("Could not start TaskManager: " + e.getMessage());
            System.err.println("Check the address, available ports, and access to the data file.");
            System.exit(1);
        }
    }

    private static void usage() {
        System.out.println("Usage: java -cp out TaskServer [bind-address [registry-port [service-port [data-file]]]]");
        System.out.println("Defaults: 127.0.0.1 1099 1100 data/tasks.tsv.");
        System.out.println("Use a trusted LAN interface to allow remote clients.");
    }
}
