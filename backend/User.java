package backend;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

public class User {

    private String fullName;
    private String studentId;
    private String department;
    private String email;
    private String username;
    private String password;

    private int currentStreak;
    private String lastStudyDate;

    // Default Constructor
    public User() {

    }

    // Parameterized Constructor
    public User(String fullName, String studentId, String department,
                String email, String username, String password,
                int currentStreak, String lastStudyDate) {

        setFullName(fullName);
        setStudentId(studentId);
        setDepartment(department);
        setEmail(email);
        setUsername(username);
        setPassword(password);
        setCurrentStreak(currentStreak);
        setLastStudyDate(lastStudyDate);
    }

    // Getters

    public String getFullName() {
        return fullName;
    }

    public String getStudentId() {
        return studentId;
    }

    public String getDepartment() {
        return department;
    }

    public String getEmail() {
        return email;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public int getCurrentStreak() {
        return currentStreak;
    }

    public String getLastStudyDate() {
        return lastStudyDate;
    }

    // Setters

    public void setFullName(String fullName) {
        requireNonBlank(fullName, "Full name");
        this.fullName = fullName;
    }

    public void setStudentId(String studentId) {
        requireNonBlank(studentId, "Student ID");
        this.studentId = studentId;
    }

    public void setDepartment(String department) {
        requireNonBlank(department, "Department");
        this.department = department;
    }

    public void setEmail(String email) {
        requireNonBlank(email, "Email");
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalArgumentException("Email must have a valid address format");
        }
        this.email = email;
    }

    public void setUsername(String username) {
        requireNonBlank(username, "Username");
        this.username = username;
    }

    public void setPassword(String password) {
        requireNonBlank(password, "Password");
        this.password = password;
    }

    public void setCurrentStreak(int currentStreak) {
        if (currentStreak < 0) {
            throw new IllegalArgumentException("Current streak cannot be negative");
        }
        this.currentStreak = currentStreak;
    }

    public void setLastStudyDate(String lastStudyDate) {
        requireNonBlank(lastStudyDate, "Last study date");
        if (!"Not Started".equals(lastStudyDate)) {
            try {
                LocalDate.parse(lastStudyDate);
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException("Last study date must be an ISO date or Not Started", exception);
            }
        }
        this.lastStudyDate = lastStudyDate;
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
    }
}
