package com.richi.analyzer;

import com.richi.model.Flight;

import java.util.List;

public interface AircraftAnalyzerService {
    
    /**
     * Identify widebody aircraft flights from the list
     */
    List<String> findWidebodyFlights(List<Flight> flights);
    
    /**
     * Check if a specific aircraft type is a widebody
     */
    boolean isWidebody(String aircraftType);
    
    /**
     * Check if a specific aircraft type is interesting (widebody, military, or bizjet)
     */
    boolean isInteresting(String aircraftType);
    
    /**
     * Find interesting flights (widebody, military, or bizjet)
     */
    List<String> findInterestingFlights(List<Flight> flights);
    
    /**
     * Analyze flights and return those matching criteria
     */
    List<Flight> analyzeFlights(List<Flight> flights);
}
