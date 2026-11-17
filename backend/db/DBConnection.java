package backend.db;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public final class DBConnection {
    private final String url;
    private final String user;
    private final String password;

    public DBConnection() {
        this(defaultProperties());
    }

    public DBConnection(Path configFile) {
        this(readProperties(configFile));
    }

    private DBConnection(Properties properties) {
        url = properties.getProperty("db.url");
        user = properties.getProperty("db.user");
        password = properties.getProperty("db.password");
        if (url == null || url.isBlank() || user == null || user.isBlank() || password == null) {
            throw new IllegalArgumentException("Database configuration requires db.url, db.user, and db.password");
        }
    }

    private static Properties defaultProperties() {
        String url = System.getenv("SOFTNOTE_DB_URL");
        String user = System.getenv("SOFTNOTE_DB_USER");
        String password = System.getenv("SOFTNOTE_DB_PASSWORD");
        if (url == null && user == null && password == null) {
            return readProperties(Path.of("config", "database.properties"));
        }
        if (url == null || url.isBlank() || user == null || user.isBlank() || password == null) {
            throw new IllegalArgumentException(
                    "Database configuration requires SOFTNOTE_DB_URL, SOFTNOTE_DB_USER, and SOFTNOTE_DB_PASSWORD");
        }
        Properties properties = new Properties();
        properties.setProperty("db.url", url);
        properties.setProperty("db.user", user);
        properties.setProperty("db.password", password);
        return properties;
    }

    private static Properties readProperties(Path configFile) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(configFile)) {
            properties.load(reader);
            return properties;
        } catch (IOException | IllegalArgumentException exception) {
            throw new DataAccessException("Could not read database configuration from " + configFile, exception);
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
}
