import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.rmi.RemoteException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Endpoint validation, socket factory identity, and real stalled RMI handshake checks. */
public final class TaskTransportTest {
    private static final String HOST = "127.0.0.1";

    private TaskTransportTest() {
    }

    public static void main(String[] args) throws Exception {
        testEndpointValidation();
        testClientSocketFactorySerialization();
        testBoundServerSocketFactory();
        testReadTimeoutCannotBeDisabled();
        testStalledRmiHandshakeTimesOut();
        System.out.println("TaskTransportTest: all checks passed.");
    }

    private static void testEndpointValidation() throws Exception {
        for (int port : new int[] {1, 1099, 65535}) {
            check(TaskTransport.validatePort(port) == port, "A valid port should be preserved.");
            check(TaskTransport.parsePort(Integer.toString(port)) == port,
                    "A valid decimal port should be parsed.");
        }
        for (int port : new int[] {Integer.MIN_VALUE, -1, 0, 65536, Integer.MAX_VALUE}) {
            expect(IllegalArgumentException.class, () -> TaskTransport.validatePort(port));
            expect(IllegalArgumentException.class, () -> TaskTransport.connect(HOST, port));
        }
        for (String value : new String[] {null, "", " ", "abc", "1.5", "-1", "0", "65536",
                "2147483648", " 1099 "}) {
            expect(IllegalArgumentException.class, () -> TaskTransport.parsePort(value));
        }
        for (String host : new String[] {null, "", " \t\r\n", "\u2003"}) {
            expect(IllegalArgumentException.class, () -> TaskTransport.validateHost(host));
            expect(IllegalArgumentException.class, () -> TaskTransport.connect(host, 1099));
            expect(IllegalArgumentException.class,
                    () -> new TaskTransport.BoundServerSocketFactory(host));
        }
        for (String host : new String[] {HOST, "localhost", "::1"}) {
            check(host.equals(TaskTransport.validateHost(" \t" + host + "\n ")),
                    "Host validation should strip surrounding whitespace.");
        }
    }

    private static void testClientSocketFactorySerialization() throws Exception {
        TaskTransport.TimeoutClientSocketFactory original = new TaskTransport.TimeoutClientSocketFactory();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(original);
        }
        Object restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = input.readObject();
        }
        check(original != restored, "Serialization should restore a separate factory instance.");
        check(original.equals(restored) && restored.equals(original),
                "Deserialized client factories must retain equivalent identity.");
        check(original.hashCode() == restored.hashCode(), "Equal factories must have equal hashes.");
        check(original.equals(TaskTransport.CLIENT_SOCKET_FACTORY),
                "The shared client factory should use the same identity contract.");
        check(!original.equals(null) && !original.equals(new Object()),
                "A client factory must differ from null and unrelated objects.");
    }

    private static void testBoundServerSocketFactory() throws Exception {
        TaskTransport.BoundServerSocketFactory first = new TaskTransport.BoundServerSocketFactory(HOST);
        TaskTransport.BoundServerSocketFactory same = new TaskTransport.BoundServerSocketFactory(" " + HOST + " ");
        TaskTransport.BoundServerSocketFactory other = new TaskTransport.BoundServerSocketFactory("localhost");
        check(first.equals(first) && first.equals(same) && same.equals(first),
                "Server factory identity should use its normalized bind address.");
        check(first.hashCode() == same.hashCode(), "Equal server factories must have equal hashes.");
        check(!first.equals(other) && !first.equals(null) && !first.equals(new Object()),
                "Different bind addresses and unrelated objects must have different identity.");
        try (ServerSocket listener = first.createServerSocket(0)) {
            listener.setSoTimeout(TaskTransport.CONNECT_TIMEOUT_MS);
            check(listener.isBound() && listener.getLocalPort() > 0, "The factory should create a listener.");
            check(listener.getInetAddress().isLoopbackAddress()
                            && HOST.equals(listener.getInetAddress().getHostAddress()),
                    "The listener must bind specifically to the requested IPv4 loopback interface.");
            try (Socket client = new Socket(HOST, listener.getLocalPort()); Socket peer = listener.accept()) {
                check(client.isConnected() && peer.getInetAddress().isLoopbackAddress(),
                        "The loopback listener should accept a real connection.");
            }
        }
    }

    private static void testReadTimeoutCannotBeDisabled() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 50, InetAddress.getByName(HOST))) {
            listener.setSoTimeout(TaskTransport.CONNECT_TIMEOUT_MS);
            try (Socket client = TaskTransport.CLIENT_SOCKET_FACTORY.createSocket(HOST, listener.getLocalPort());
                    Socket peer = listener.accept()) {
                check(client.isConnected() && peer.isConnected(), "The timeout factory should connect a socket.");
                check(client.getSoTimeout() == TaskTransport.READ_TIMEOUT_MS,
                        "A connected socket should have the configured read timeout.");
                client.setSoTimeout(60_000);
                check(client.getSoTimeout() == TaskTransport.READ_TIMEOUT_MS,
                        "A long RMI handshake timeout must be capped.");
                client.setSoTimeout(0);
                check(client.getSoTimeout() == TaskTransport.READ_TIMEOUT_MS,
                        "An infinite RMI timeout must remain bounded.");
                client.setSoTimeout(Integer.MAX_VALUE);
                check(client.getSoTimeout() == TaskTransport.READ_TIMEOUT_MS,
                        "The largest timeout must also be capped.");
                client.setSoTimeout(100);
                check(client.getSoTimeout() == 100, "A shorter timeout should be preserved.");
                expect(IllegalArgumentException.class, () -> client.setSoTimeout(-1));
                check(client.getSoTimeout() == 100, "An invalid timeout must leave the previous setting intact.");
            }
        }
    }

    private static void testStalledRmiHandshakeTimesOut() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 50, InetAddress.getByName(HOST))) {
            listener.setSoTimeout(TaskTransport.CONNECT_TIMEOUT_MS);
            ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "stalled-rmi-handshake-test");
                thread.setDaemon(true);
                return thread;
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            Future<TaskManager> connection = worker.submit(() -> TaskTransport.connect(HOST, listener.getLocalPort()));
            try (Socket peer = listener.accept()) {
                check(peer.isConnected(), "The stalled peer must accept the RMI transport connection.");
                // Keep the TCP connection open without sending a JRMP handshake response.
                try {
                    connection.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    throw new AssertionError("A peer that never speaks JRMP must not produce a service connection.");
                } catch (ExecutionException failure) {
                    check(failure.getCause() instanceof RemoteException,
                            "A stalled RMI handshake should fail as a RemoteException.");
                    check(hasCause(failure.getCause(), SocketTimeoutException.class),
                            "The stalled handshake must fail because of the configured socket read timeout.");
                } catch (TimeoutException failure) {
                    throw new AssertionError("The stalled RMI handshake did not fail within 10 seconds.", failure);
                }
            } finally {
                connection.cancel(true);
                worker.shutdownNow();
                check(worker.awaitTermination(5, TimeUnit.SECONDS), "The handshake test worker must shut down.");
            }
        }
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (int depth = 0; failure != null && depth < 20; depth++, failure = failure.getCause()) {
            if (type.isInstance(failure)) {
                return true;
            }
        }
        return false;
    }

    private static void expect(Class<? extends Throwable> type, CheckedAction action) throws Exception {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) {
                return;
            }
            throw new AssertionError("Expected " + type.getSimpleName() + " but got " + failure, failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName() + ".");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }
}
