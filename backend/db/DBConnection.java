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
        this(Path.of("config", "database.properties"));
    }

    public DBConnection(Path configFile) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(configFile)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException exception) {
            throw new DataAccessException("Could not read database configuration from " + configFile, exception);
        }

        url = properties.getProperty("db.url");
        user = properties.getProperty("db.user");
        password = properties.getProperty("db.password");
        if (url == null || url.isBlank() || user == null || user.isBlank() || password == null) {
            throw new IllegalArgumentException("Database configuration requires db.url, db.user, and db.password");
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
}
