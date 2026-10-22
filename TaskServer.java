import java.io.IOException;
import java.net.InetAddress;
import java.rmi.NoSuchObjectException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

/** Owns both RMI endpoints, including cleanup after a partial startup. */
public final class TaskServer implements AutoCloseable {
    private final Registry registry;
    private final TaskManagerImpl service;
    private final String bindAddress;
    private boolean closed;

    private TaskServer(Registry registry, TaskManagerImpl service, String bindAddress) {
        this.registry = registry;
        this.service = service;
        this.bindAddress = bindAddress;
    }

    public static TaskServer start(String bindHost, int registryPort, int servicePort) throws IOException {
        TaskTransport.validatePort(registryPort);
        TaskTransport.validatePort(servicePort);
        if (registryPort == servicePort) {
            throw new IllegalArgumentException("Registry and service ports must be different.");
        }
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
            service = new TaskManagerImpl(servicePort, TaskTransport.CLIENT_SOCKET_FACTORY, sockets);
            registry.rebind(TaskTransport.BINDING_NAME, service);
            return new TaskServer(registry, service, address.getHostAddress());
        } catch (IOException | RuntimeException e) {
            unexport(service);
            unexport(registry);
            throw e;
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            unexport(service);
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
            if (args.length > 3) {
                throw new IllegalArgumentException("Too many arguments.");
            }
            String host = args.length > 0 ? TaskTransport.validateHost(args[0]) : TaskTransport.DEFAULT_HOST;
            int registryPort = args.length > 1 ? TaskTransport.parsePort(args[1])
                    : TaskTransport.DEFAULT_REGISTRY_PORT;
            int servicePort = args.length > 2 ? TaskTransport.parsePort(args[2])
                    : TaskTransport.DEFAULT_SERVICE_PORT;
            TaskServer server = start(host, registryPort, servicePort);
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(server::close, "task-server-shutdown"));
            } catch (RuntimeException e) {
                server.close();
                throw e;
            }
            System.out.printf("TaskManager ready on %s (registry: %d, service: %d).%n",
                    server.bindAddress, registryPort, servicePort);
            System.out.println("Tasks are stored in memory. Press Ctrl+C to stop the server.");
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid configuration: " + e.getMessage());
            usage();
            System.exit(2);
        } catch (IOException e) {
            System.err.println("Could not start TaskManager: " + e.getMessage());
            System.err.println("Check that the address belongs to this computer and both ports are available.");
            System.exit(1);
        }
    }

    private static void usage() {
        System.out.println("Usage: java -cp out TaskServer [bind-address [registry-port [service-port]]]");
        System.out.println("Defaults: 127.0.0.1 1099 1100. Use a trusted LAN interface to allow remote clients.");
    }
}
