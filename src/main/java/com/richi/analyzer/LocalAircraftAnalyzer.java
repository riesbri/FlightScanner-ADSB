package com.richi.analyzer;

import com.richi.config.ConfigManager;
import com.richi.model.Flight;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class LocalAircraftAnalyzer implements AircraftAnalyzerService {

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
        if (AircraftTypes.WIDEBODY.contains(normalized)) {
            return true;
        }
        
        // Prefix match (e.g., "B777-300ER" should match "B777")
        return AircraftTypes.WIDEBODY.stream()
                .anyMatch(widebody -> normalized.startsWith(widebody) || widebody.startsWith(normalized));
    }
    
    @Override
    public boolean isInteresting(String aircraftType) {
        return isWidebody(aircraftType) || isMilitary(aircraftType) || isBizjet(aircraftType);
    }

    public boolean isMilitary(String aircraftType) {
        if (aircraftType == null || aircraftType.isEmpty() || "UNKNOWN".equals(aircraftType)) {
            return false;
        }
        String normalized = normalizeAircraftCode(aircraftType);
        return AircraftTypes.MILITARY.contains(normalized)
                || AircraftTypes.MILITARY.stream().anyMatch(t -> normalized.startsWith(t) || t.startsWith(normalized));
    }

    public boolean isBizjet(String aircraftType) {
        if (aircraftType == null || aircraftType.isEmpty() || "UNKNOWN".equals(aircraftType)) {
            return false;
        }
        String normalized = normalizeAircraftCode(aircraftType);
        return AircraftTypes.BIZJET.contains(normalized)
                || AircraftTypes.BIZJET.stream().anyMatch(t -> normalized.startsWith(t) || t.startsWith(normalized));
    }

    @Override
    public List<String> findInterestingFlights(List<Flight> flights) {
        List<String> interestingFlightNumbers = flights.stream()
                .filter(f -> isInteresting(f.aircraft()))
                .map(Flight::flightNumber)
                .toList();

        log.debug("Found {} interesting flights (widebody/military/bizjet)", interestingFlightNumbers.size());
        return interestingFlightNumbers;
    }

    @Override
    public List<Flight> analyzeFlights(List<Flight> flights) {
        List<String> interestingNumbers = findInterestingFlights(flights);
        
        return flights.stream()
                .filter(f -> interestingNumbers.contains(f.flightNumber()))
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
