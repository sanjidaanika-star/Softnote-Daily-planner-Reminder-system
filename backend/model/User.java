package backend.model;

import java.time.LocalDateTime;

public final class User {
    private long id;
    private String name;
    private String email;
    private LocalDateTime createdAt;

    public User(long id, String name, String email, LocalDateTime createdAt) {
        setId(id);
        setName(name);
        setEmail(email);
        setCreatedAt(createdAt);
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("User ID must be positive");
        }
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 255) {
            throw new IllegalArgumentException("Name must contain 1 to 255 characters");
        }
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        if (email == null || email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalArgumentException("Email must have a valid address format");
        }
        this.email = email;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        if (createdAt == null) {
            throw new IllegalArgumentException("Creation timestamp is required");
        }
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "User{id=" + id + ", name=" + name + ", createdAt=" + createdAt + '}';
    }
}
