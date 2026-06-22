package com.flightscanner.repository;

import com.flightscanner.config.ConfigManager;
import com.flightscanner.model.Flight;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
public class SqlFlightRepository implements FlightRepository {
    
    private final HikariDataSource dataSource;
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    public SqlFlightRepository() {
        this(ConfigManager.getInstance());
    }
    
    public SqlFlightRepository(ConfigManager config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.getDbUrl());
        
        if (config.getDbUser() != null) {
            hikariConfig.setUsername(config.getDbUser());
        }
        if (config.getDbPassword() != null) {
            hikariConfig.setPassword(config.getDbPassword());
        }
        
        hikariConfig.setMaximumPoolSize(config.getDbPoolMaxSize());
        hikariConfig.setMinimumIdle(config.getDbPoolMinSize());
        hikariConfig.setConnectionTimeout(config.getDbConnectionTimeoutMs());
        
        // SQLite specific settings
        if (config.getDbUrl().contains("sqlite")) {
            hikariConfig.setDriverClassName("org.sqlite.JDBC");
            hikariConfig.setConnectionTestQuery("SELECT 1");
        } else {
            hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
        }
        
        this.dataSource = new HikariDataSource(hikariConfig);
        log.info("Database connection pool initialized");
    }

    /**
     * Build directly from a JDBC URL (used by tests, e.g. a temp SQLite file)
     * without going through ConfigManager's singleton/global config.
     */
    public SqlFlightRepository(String jdbcUrl) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        if (jdbcUrl.contains("sqlite")) {
            hikariConfig.setDriverClassName("org.sqlite.JDBC");
            hikariConfig.setConnectionTestQuery("SELECT 1");
        } else {
            hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
        }
        hikariConfig.setMaximumPoolSize(2);
        this.dataSource = new HikariDataSource(hikariConfig);
        log.info("Database connection pool initialized (direct JDBC URL)");
    }

    @Override
    public void initialize() throws SQLException {
        String sql = isSQLite() ?
            """
            CREATE TABLE IF NOT EXISTS flights (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                flight_number VARCHAR(10) NOT NULL,
                origin VARCHAR(4) NOT NULL,
                aircraft VARCHAR(50) NOT NULL,
                scheduled_time DATETIME NOT NULL,
                altitude INTEGER,
                speed INTEGER,
                squawk TEXT,
                hex_ident TEXT,
                operator TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(flight_number, scheduled_time)
            )
            """ :
            """
            CREATE TABLE IF NOT EXISTS flights (
                id INT AUTO_INCREMENT PRIMARY KEY,
                flight_number VARCHAR(10) NOT NULL,
                origin VARCHAR(4) NOT NULL,
                aircraft VARCHAR(50) NOT NULL,
                scheduled_time DATETIME NOT NULL,
                altitude INT,
                speed INT,
                squawk VARCHAR(4),
                hex_ident VARCHAR(6),
                operator VARCHAR(100),
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                UNIQUE KEY unique_flight (flight_number, scheduled_time)
            )
            """;

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
            // ADD COLUMN is a no-op on fresh DBs (columns already in CREATE TABLE above).
            // On pre-existing flights.db files the column may be missing — add it in-place.
            // ADD COLUMN throws if the column already exists; swallow that case.
            addColumnIfMissing(conn, "altitude", "INTEGER");
            addColumnIfMissing(conn, "speed",    "INTEGER");
            addColumnIfMissing(conn, "squawk",   "TEXT");
            addColumnIfMissing(conn, "hex_ident","TEXT");
            addColumnIfMissing(conn, "operator", "TEXT");
            log.info("Database schema initialized");
        }
    }

    private void addColumnIfMissing(Connection conn, String column, String type) {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("ALTER TABLE flights ADD COLUMN " + column + " " + type);
            log.info("Added missing column '{}' to flights table", column);
        } catch (SQLException e) {
            // Column already exists (normal case) — nothing to do.
            log.debug("Column '{}' already present: {}", column, e.getMessage());
        }
    }
    
    @Override
    public boolean saveFlight(Flight flight) throws SQLException {
        // Check if flight already exists first
        if (flightExists(flight.flightNumber(), flight.scheduledTime().format(formatter))) {
            log.debug("Flight {} at {} already exists, skipping", 
                    flight.flightNumber(), flight.scheduledTime());
            return false;
        }
        
        String sql = isSQLite() ?
            "INSERT INTO flights (flight_number, origin, aircraft, scheduled_time, altitude, speed, squawk, hex_ident, operator) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)" :
            "INSERT IGNORE INTO flights (flight_number, origin, aircraft, scheduled_time, altitude, speed, squawk, hex_ident, operator) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, flight.flightNumber());
            pstmt.setString(2, flight.origin());
            pstmt.setString(3, flight.aircraft());
            // Store as formatted text (not setTimestamp, which xerial persists as epoch-millis
            // INTEGER) so DATE()/datetime() queries and the flightExists dedup actually match,
            // and the value is human-readable in the db. See mapResultSetToFlight for the read.
            pstmt.setString(4, flight.scheduledTime().format(formatter));
            pstmt.setObject(5, flight.altitude(), Types.INTEGER);
            pstmt.setObject(6, flight.speed(), Types.INTEGER);
            pstmt.setString(7, flight.squawk());
            pstmt.setString(8, flight.hexIdent());
            pstmt.setString(9, flight.operator());

            int affected = pstmt.executeUpdate();
            boolean inserted = affected > 0;
            
            if (inserted) {
                log.info("Saved flight: {}", flight);
            }
            
            return inserted;
        } catch (SQLException e) {
            log.error("Failed to save flight {}: {}", flight.flightNumber(), e.getMessage());
            throw e;
        }
    }
    
    @Override
    public int saveFlights(List<Flight> flights) {
        int saved = 0;
        for (Flight flight : flights) {
            try {
                if (saveFlight(flight)) {
                    saved++;
                }
            } catch (SQLException e) {
                log.error("Failed to save flight {}: {}", flight.flightNumber(), e.getMessage());
            }
        }
        log.info("Saved {} of {} flights", saved, flights.size());
        return saved;
    }
    
    @Override
    public boolean flightExists(String flightNumber, String scheduledTime) {
        String sql = "SELECT 1 FROM flights WHERE flight_number = ? AND scheduled_time = ?";
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, flightNumber);
            pstmt.setString(2, scheduledTime);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("Error checking flight existence: {}", e.getMessage());
            return false;
        }
    }
    
    @Override
    public Optional<Flight> findByFlightNumberAndTime(String flightNumber, String scheduledTime) {
        String sql = "SELECT * FROM flights WHERE flight_number = ? AND scheduled_time = ?";
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, flightNumber);
            pstmt.setString(2, scheduledTime);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToFlight(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Error finding flight: {}", e.getMessage());
        }
        
        return Optional.empty();
    }
    
    @Override
    public List<Flight> findByDate(LocalDate date) {
        String sql = "SELECT * FROM flights WHERE DATE(scheduled_time) = ? ORDER BY scheduled_time";
        List<Flight> flights = new ArrayList<>();
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setDate(1, Date.valueOf(date));
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    flights.add(mapResultSetToFlight(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Error finding flights by date: {}", e.getMessage());
        }
        
        return flights;
    }
    
    @Override
    public List<Flight> findRecent(int hoursBack) {
        String sql = isSQLite() ?
            "SELECT * FROM flights WHERE scheduled_time >= datetime('now', '-' || ? || ' hours') ORDER BY scheduled_time DESC" :
            "SELECT * FROM flights WHERE scheduled_time >= DATE_SUB(NOW(), INTERVAL ? HOUR) ORDER BY scheduled_time DESC";
        
        List<Flight> flights = new ArrayList<>();
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, hoursBack);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    flights.add(mapResultSetToFlight(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Error finding recent flights: {}", e.getMessage());
        }
        
        return flights;
    }
    
    @Override
    public java.util.Optional<java.time.LocalDate> findLastSeen(String flightNumber,
                                                                  java.time.LocalDateTime before) {
        String sql = isSQLite()
            ? "SELECT MAX(scheduled_time) FROM flights WHERE flight_number = ? AND DATE(scheduled_time) < DATE(?)"
            : "SELECT MAX(scheduled_time) FROM flights WHERE flight_number = ? AND DATE(scheduled_time) < DATE(?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, flightNumber);
            ps.setString(2, before.format(formatter));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getString(1) != null) {
                    return java.util.Optional.of(
                        java.time.LocalDateTime.parse(rs.getString(1), formatter).toLocalDate());
                }
            }
        } catch (SQLException e) {
            log.warn("findLastSeen failed for {}: {}", flightNumber, e.getMessage());
        }
        return java.util.Optional.empty();
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("Database connection pool closed");
        }
    }
    
    private Flight mapResultSetToFlight(ResultSet rs) throws SQLException {
        return new Flight(
                rs.getString("flight_number"),
                rs.getString("origin"),
                rs.getString("aircraft"),
                rs.getTimestamp("scheduled_time").toLocalDateTime(),
                (Integer) rs.getObject("altitude"),
                (Integer) rs.getObject("speed"),
                rs.getString("squawk"),
                rs.getString("hex_ident"),
                rs.getString("operator")
        );
    }
    
    private boolean isSQLite() {
        return dataSource.getJdbcUrl().contains("sqlite");
    }
}
