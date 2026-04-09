package com.richi.repository;

import com.richi.model.Flight;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public interface FlightRepository {
    
    /**
     * Initialize the database schema
     */
    void initialize() throws SQLException;
    
    /**
     * Save a flight to the database.
     * @return true if the flight was newly inserted, false if it already existed
     */
    boolean saveFlight(Flight flight) throws SQLException;
    
    /**
     * Save multiple flights, returning the count of newly inserted flights
     */
    int saveFlights(List<Flight> flights);
    
    /**
     * Check if a flight already exists in the database
     */
    boolean flightExists(String flightNumber, String scheduledTime);
    
    /**
     * Find a flight by flight number and scheduled time
     */
    Optional<Flight> findByFlightNumberAndTime(String flightNumber, String scheduledTime);
    
    /**
     * Get all flights for a specific date
     */
    List<Flight> findByDate(java.time.LocalDate date);
    
    /**
     * Get recent flights (last N hours)
     */
    List<Flight> findRecent(int hoursBack);
    
    /**
     * Close the repository and release resources
     */
    void close();
}
