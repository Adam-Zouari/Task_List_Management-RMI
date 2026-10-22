import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Immutable value describing one task; its UUID remains stable across edits. */
public final class Task implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final int MAX_DESCRIPTION_LENGTH = 1000;
    private final String id;
    private final String description;
    private final boolean completed;
    private final LocalDate dueDate;

    public Task(String id, String description, boolean completed, LocalDate dueDate) {
        this.id = validateId(id);
        this.description = normalizeDescription(description);
        this.completed = completed;
        this.dueDate = dueDate;
    }

    public String getId() { return id; }
    public String getDescription() { return description; }
    public boolean isCompleted() { return completed; }
    public LocalDate getDueDate() { return dueDate; }

    /** A task due today is not overdue, and a completed task is never overdue. */
    public boolean isOverdue(LocalDate today) {
        Objects.requireNonNull(today, "today");
        return !completed && dueDate != null && dueDate.isBefore(today);
    }

    public static String normalizeDescription(String description) {
        if (description == null) {
            throw new IllegalArgumentException("A task description must not be null.");
        }
        String normalized = description.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("A task description must not be blank.");
        }
        if (normalized.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("A task description must contain at most "
                    + MAX_DESCRIPTION_LENGTH + " characters.");
        }
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= normalized.length() || !Character.isLowSurrogate(normalized.charAt(index + 1))) {
                    throw new IllegalArgumentException("A task description must contain valid Unicode text.");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("A task description must contain valid Unicode text.");
            }
        }
        return normalized;
    }

    static String validateId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A task ID must not be blank.");
        }
        try {
            if (!UUID.fromString(id).toString().equals(id)) {
                throw new IllegalArgumentException("A task ID must be a canonical UUID.");
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("A task ID must be a canonical UUID.", failure);
        }
        return id;
    }

    private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
        input.defaultReadObject();
        try {
            validateId(id);
            if (!normalizeDescription(description).equals(description)) {
                throw new IllegalArgumentException("Serialized descriptions must already be normalized.");
            }
        } catch (IllegalArgumentException failure) {
            InvalidObjectException invalid = new InvalidObjectException("Invalid serialized task.");
            invalid.initCause(failure);
            throw invalid;
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) { return true; }
        if (!(other instanceof Task)) { return false; }
        Task task = (Task) other;
        return completed == task.completed && id.equals(task.id)
                && description.equals(task.description) && Objects.equals(dueDate, task.dueDate);
    }

    @Override
    public int hashCode() { return Objects.hash(id, description, completed, dueDate); }

    @Override
    public String toString() { return description; }
}
