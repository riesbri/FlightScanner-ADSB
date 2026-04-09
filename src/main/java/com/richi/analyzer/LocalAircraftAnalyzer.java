package com.richi.analyzer;

import com.richi.config.ConfigManager;
import com.richi.model.Flight;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class LocalAircraftAnalyzer implements AircraftAnalyzerService {
    
    // Common widebody aircraft types
    private static final Set<String> WIDEBODY_AIRCRAFT = Set.of(
            // Boeing
            "B747", "B748", "B74R",
            "B767", "B763", "B764",
            "B777", "B772", "B773", "B77W", "B77L", "B77F",
            "B787", "B788", "B789", "B78X",
            // Airbus
            "A330", "A332", "A333",
            "A340", "A342", "A343", "A345", "A346",
            "A350", "A359", "A35K",
            "A380", "A388",
            // McDonnell Douglas
            "MD11", "MD1F",
            // Ilyushin
            "IL96", "IL76",
            // Antonov
            "AN124", "AN22", "AN225"
    );
    
    private final boolean aiEnabled;
    private final DeepSeekAircraftAnalyzer aiAnalyzer;
    
    public LocalAircraftAnalyzer() {
        this(ConfigManager.getInstance());
    }
    
    public LocalAircraftAnalyzer(ConfigManager config) {
        this.aiEnabled = config.isAiAnalysisEnabled();
        this.aiAnalyzer = aiEnabled ? new DeepSeekAircraftAnalyzer(config) : null;
        log.info("Aircraft analyzer initialized (AI enabled: {})", aiEnabled);
    }
    
    @Override
    public List<String> findWidebodyFlights(List<Flight> flights) {
        // First use local detection (fast, no API cost)
        List<String> widebodyFlightNumbers = flights.stream()
                .filter(f -> isWidebody(f.aircraft()))
                .map(Flight::flightNumber)
                .toList();
        
        log.debug("Found {} widebody flights using local detection", widebodyFlightNumbers.size());
        
        // Optionally use AI for uncertain cases
        if (aiEnabled && widebodyFlightNumbers.isEmpty()) {
            try {
                List<String> aiResults = aiAnalyzer.findWidebodyFlights(flights);
                log.info("AI analysis found {} widebody flights", aiResults.size());
                return aiResults;
            } catch (Exception e) {
                log.error("AI analysis failed: {}", e.getMessage());
            }
        }
        
        return widebodyFlightNumbers;
    }
    
    @Override
    public boolean isWidebody(String aircraftType) {
        if (aircraftType == null || aircraftType.isEmpty() || "UNKNOWN".equals(aircraftType)) {
            return false;
        }
        
        String normalized = normalizeAircraftCode(aircraftType);
        
        // Direct match
        if (WIDEBODY_AIRCRAFT.contains(normalized)) {
            return true;
        }
        
        // Prefix match (e.g., "B777-300ER" should match "B777")
        return WIDEBODY_AIRCRAFT.stream()
                .anyMatch(widebody -> normalized.startsWith(widebody) || widebody.startsWith(normalized));
    }
    
    @Override
    public List<Flight> analyzeFlights(List<Flight> flights) {
        List<String> widebodyNumbers = findWidebodyFlights(flights);
        
        return flights.stream()
                .filter(f -> widebodyNumbers.contains(f.flightNumber()))
                .toList();
    }
    
    private String normalizeAircraftCode(String code) {
        return code.toUpperCase()
                   .replace("-", "")
                   .replace(" ", "")
                   .replace("BOEING", "B")
                   .replace("AIRBUS", "A")
                   .replace("B777", "B77")  // Normalize variants
                   .replace("B787", "B78")
                   .replace("A350", "A35")
                   .trim();
    }
}
