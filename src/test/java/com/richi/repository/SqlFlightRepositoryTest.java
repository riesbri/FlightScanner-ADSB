package com.richi.repository;

import com.richi.model.Flight;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlFlightRepositoryTest {

    @TempDir
    Path tempDir;

    private SqlFlightRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        Path db = tempDir.resolve("test-flights.db");
        repo = new SqlFlightRepository("jdbc:sqlite:" + db.toAbsolutePath());
        repo.initialize();
    }

    @AfterEach
    void tearDown() {
        if (repo != null) {
            repo.close();
        }
    }

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Test
    void savesAndReadsBackAllSixFields() throws Exception {
        LocalDateTime when = LocalDateTime.of(2026, 6, 7, 12, 0, 0);
        Flight original = new Flight("RYR123", "DUB", "B77W", when, 35000, 450);

        assertTrue(repo.saveFlight(original));

        Optional<Flight> found =
                repo.findByFlightNumberAndTime("RYR123", when.format(FORMATTER));
        assertTrue(found.isPresent());

        Flight readBack = found.get();
        assertEquals("RYR123", readBack.flightNumber());
        assertEquals("DUB", readBack.origin());
        assertEquals("B77W", readBack.aircraft());
        assertEquals(when, readBack.scheduledTime());
        assertEquals(35000, readBack.altitude());
        assertEquals(450, readBack.speed());
    }
}
