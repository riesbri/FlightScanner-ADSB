package com.richi;

import com.richi.model.Flight;
import lombok.extern.slf4j.Slf4j;
import java.sql.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

@Slf4j
public class FlightDatabase {
    private static final String DB_URL = "jdbc:mysql://localhost:3306/flight_tracker"
            + "?useSSL=false"
            + "&serverTimezone=UTC"
            + "&allowPublicKeyRetrieval=true";
    private static final String USER = "richi";
    private static final String PASS = "passwordXDrichi!";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public void initialize() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL JDBC Driver not found", e);
        }
        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS);
             Statement stmt = conn.createStatement()) {

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS flights (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    flight_number VARCHAR(10) NOT NULL,
                    origin VARCHAR(4) NOT NULL,
                    aircraft VARCHAR(50) NOT NULL,
                    scheduled_time DATETIME NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )""");
        }
    }

    public boolean saveFlight(Flight flight) throws SQLException {
        String sql = """
            INSERT INTO flights (flight_number, origin, aircraft, scheduled_time)
            VALUES (?, ?, ?, ?)
            """;

        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, flight.flightNumber());
            pstmt.setString(2, flight.origin());
            pstmt.setString(3, flight.aircraft());
            pstmt.setObject(4, parseTo24HourTime(flight.time()));

            return pstmt.executeUpdate() > 0;
        }
        catch (SQLException e) {
            log.error("Database error saving flight {}: {}", flight.flightNumber(), e.getMessage());
            return false;
        }
    }

    private LocalTime parseTo24HourTime(String timeString) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("h:mm a")
                    .withLocale(Locale.US);
            return LocalTime.parse(timeString.toUpperCase(), formatter);
        } catch (DateTimeParseException e) {
            log.warn("Failed to parse time '{}', using fallback", timeString);
            return LocalTime.MIDNIGHT;
        }
    }

}