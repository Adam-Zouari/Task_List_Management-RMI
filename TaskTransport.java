import java.io.IOException;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.rmi.NotBoundException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;

/** Shared endpoint settings and socket factories used by the server and its stubs. */
public final class TaskTransport {
    public static final String BINDING_NAME = "TaskManager";
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_REGISTRY_PORT = 1099;
    public static final int DEFAULT_SERVICE_PORT = 1100;
    public static final int CONNECT_TIMEOUT_MS = 3_000;
    public static final int READ_TIMEOUT_MS = 5_000;
    public static final RMIClientSocketFactory CLIENT_SOCKET_FACTORY = new TimeoutClientSocketFactory();

    private TaskTransport() {
    }

    public static TaskManager connect(String host, int port) throws RemoteException, NotBoundException {
        host = validateHost(host);
        validatePort(port);
        Remote service = LocateRegistry.getRegistry(host, port, CLIENT_SOCKET_FACTORY).lookup(BINDING_NAME);
        if (!(service instanceof TaskManager)) {
            throw new RemoteException("The registry entry is not a TaskManager service.");
        }
        return (TaskManager) service;
    }

    public static int parsePort(String value) {
        try {
            return validatePort(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Port must be an integer from 1 to 65535: " + value, e);
        }
    }

    public static int validatePort(int port) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Port must be from 1 to 65535: " + port);
        }
        return port;
    }

    public static String validateHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host must not be blank.");
        }
        return host.strip();
    }

    /** Serializable because an exported service includes this factory in its stub. */
    public static final class TimeoutClientSocketFactory implements RMIClientSocketFactory, Serializable {
        private static final long serialVersionUID = 1L;

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket socket = new BoundedReadSocket();
            try {
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(READ_TIMEOUT_MS);
                return socket;
            } catch (IOException | RuntimeException e) {
                try {
                    socket.close();
                } catch (IOException closeError) {
                    e.addSuppressed(closeError);
                }
                throw e;
            }
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TimeoutClientSocketFactory;
        }

        @Override
        public int hashCode() {
            return TimeoutClientSocketFactory.class.hashCode();
        }
    }

    private static final class BoundedReadSocket extends Socket {
        @Override
        public synchronized void setSoTimeout(int timeout) throws SocketException {
            // RMI temporarily changes SO_TIMEOUT for its handshake. Keep that phase bounded too.
            // Preserve negative values so Socket still rejects invalid timeout arguments.
            super.setSoTimeout(timeout == 0 ? READ_TIMEOUT_MS : Math.min(timeout, READ_TIMEOUT_MS));
        }
    }

    /** Binds to one interface; the default server uses the IPv4 loopback address. */
    public static final class BoundServerSocketFactory implements RMIServerSocketFactory {
        private final String address;

        public BoundServerSocketFactory(String address) {
            this.address = validateHost(address);
        }

        @Override
        public ServerSocket createServerSocket(int port) throws IOException {
            return new ServerSocket(port, 50, InetAddress.getByName(address));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof BoundServerSocketFactory
                    && address.equals(((BoundServerSocketFactory) other).address);
        }

        @Override
        public int hashCode() {
            return address.hashCode();
        }
    }
}
