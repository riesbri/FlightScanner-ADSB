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

import static org.junit.jupiter.api.Assertions.*;

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
        if (repo != null) repo.close();
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
        assertEquals("DUB",    readBack.origin());
        assertEquals("B77W",   readBack.aircraft());
        assertEquals(when,     readBack.scheduledTime());
        assertEquals(35000,    readBack.altitude());
        assertEquals(450,      readBack.speed());
    }

    @Test
    void savesAndReadsBackAlertFields() throws Exception {
        LocalDateTime when = LocalDateTime.of(2026, 6, 7, 14, 0, 0);
        Flight original = new Flight("THY1", "IST", "A332", when, 5000, 280,
                "7700", "4BA2E3", "Turkish Airlines");

        assertTrue(repo.saveFlight(original));

        Optional<Flight> found =
                repo.findByFlightNumberAndTime("THY1", when.format(FORMATTER));
        assertTrue(found.isPresent());

        Flight readBack = found.get();
        assertEquals("7700",             readBack.squawk());
        assertEquals("4BA2E3",           readBack.hexIdent());
        assertEquals("Turkish Airlines", readBack.operator());
    }

    @Test
    void nullAlertFieldsRoundTrip() throws Exception {
        LocalDateTime when = LocalDateTime.of(2026, 6, 7, 16, 0, 0);
        // squawk/hexIdent/operator all null (6-arg convenience constructor)
        Flight original = new Flight("EZY42", "LGW", "A319", when, 28000, 410);

        assertTrue(repo.saveFlight(original));

        Optional<Flight> found =
                repo.findByFlightNumberAndTime("EZY42", when.format(FORMATTER));
        assertTrue(found.isPresent());

        Flight readBack = found.get();
        assertNull(readBack.squawk());
        assertNull(readBack.hexIdent());
        assertNull(readBack.operator());
    }
}
