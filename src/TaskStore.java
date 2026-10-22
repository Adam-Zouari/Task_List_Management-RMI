import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Versioned UTF-8 storage with replacement writes and a lock held for its entire lifetime. */
public final class TaskStore implements AutoCloseable {
    public static final String HEADER = "TASK_LIST_RMI\t1";
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private final Path dataFile;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private boolean closed;

    public TaskStore(Path file) throws IOException {
        if (file == null) { throw new IllegalArgumentException("The task data file must not be null."); }
        Path absolute = file.toAbsolutePath().normalize();
        if (absolute.getFileName() == null) {
            throw new IllegalArgumentException("The task data path must name a file.");
        }
        Path parent = absolute.getParent();
        Files.createDirectories(parent);
        // Parent aliases must share the same lock. The sidecar itself is never deleted.
        dataFile = parent.toRealPath().resolve(absolute.getFileName());
        Path lockFile = dataFile.resolveSibling(dataFile.getFileName() + ".lock");
        if (Files.isSymbolicLink(dataFile) || Files.isSymbolicLink(lockFile)) {
            throw new IOException("Task data and lock files must not be symbolic links.");
        }
        FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            try { acquired = channel.tryLock(); }
            catch (OverlappingFileLockException failure) {
                throw new IOException("The task data file is already in use: " + dataFile, failure);
            }
            if (acquired == null) {
                throw new IOException("The task data file is already in use: " + dataFile);
            }
        } catch (IOException | RuntimeException | Error failure) {
            try { channel.close(); }
            catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
        lockChannel = channel;
        lock = acquired;
    }

    public synchronized ArrayList<Task> load() throws IOException {
        requireOpen();
        ArrayList<Task> tasks = new ArrayList<>();
        if (Files.notExists(dataFile)) { return tasks; }
        if (Files.size(dataFile) > MAX_FILE_BYTES) {
            throw new IOException("The task data file exceeds the supported size.");
        }
        Set<String> ids = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            if (!HEADER.equals(reader.readLine())) {
                throw new IOException("Unsupported or missing task data header: " + dataFile);
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (tasks.size() >= TaskManagerImpl.MAX_TASKS) {
                    throw new IOException("The task data file contains too many tasks.");
                }
                try {
                    String[] fields = line.split("\t", -1);
                    if (fields.length != 4 || !("true".equals(fields[1]) || "false".equals(fields[1]))) {
                        throw new IllegalArgumentException("Invalid task columns or completion value.");
                    }
                    LocalDate dueDate = fields[2].isEmpty() ? null : LocalDate.parse(fields[2]);
                    String description = decodeDescription(fields[3]);
                    Task task = new Task(fields[0], description, Boolean.parseBoolean(fields[1]), dueDate);
                    if (!description.equals(task.getDescription()) || !ids.add(task.getId())) {
                        throw new IllegalArgumentException("Unnormalized description or duplicate task ID.");
                    }
                    tasks.add(task);
                } catch (IllegalArgumentException | CharacterCodingException | DateTimeParseException failure) {
                    throw new IOException("Invalid task data at line " + lineNumber + ": " + dataFile, failure);
                }
            }
        }
        return tasks;
    }

    public synchronized void save(List<Task> tasks) throws IOException {
        requireOpen();
        if (tasks == null || tasks.size() > TaskManagerImpl.MAX_TASKS) {
            throw new IllegalArgumentException("Invalid task list size.");
        }
        Set<String> ids = new HashSet<>();
        for (Task task : tasks) {
            if (task == null || !ids.add(task.getId())) {
                throw new IllegalArgumentException("Tasks must be nonnull and have unique IDs.");
            }
        }
        Path temporary = Files.createTempFile(dataFile.getParent(), "tasks-", ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                writer.write(HEADER);
                writer.newLine();
                for (Task task : tasks) {
                    writer.write(task.getId());
                    writer.write('\t');
                    writer.write(Boolean.toString(task.isCompleted()));
                    writer.write('\t');
                    if (task.getDueDate() != null) { writer.write(task.getDueDate().toString()); }
                    writer.write('\t');
                    writer.write(Base64.getEncoder().encodeToString(
                            task.getDescription().getBytes(StandardCharsets.UTF_8)));
                    writer.newLine();
                }
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, dataFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, dataFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException | Error failure) {
            try { Files.deleteIfExists(temporary); }
            catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    private static String decodeDescription(String base64) throws CharacterCodingException {
        byte[] bytes = Base64.getDecoder().decode(base64);
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private void requireOpen() throws IOException {
        if (closed) { throw new IOException("The task store is closed."); }
    }

    @Override
    public synchronized void close() {
        if (closed) { return; }
        closed = true;
        try { lock.release(); }
        catch (IOException failure) {
            System.err.println("Could not release the task file lock: " + failure.getMessage());
        }
        try { lockChannel.close(); }
        catch (IOException failure) {
            System.err.println("Could not close the task file lock: " + failure.getMessage());
        }
    }
}
