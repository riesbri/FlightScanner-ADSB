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
            "A330", "A332", "A333", "A337", "A338", "A339",
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

    private static final Set<String> MILITARY_TYPES = Set.of(
            "A400","C130","C17","C5M","C5","C141","C160","CN35","CN95",
            "E3TF","E737","EUFI","F15","F16","F18","F22","F35","F4","F5",
            "H47","H53","H60","H64","K35R","KC10","KC135","KC46",
            "P3","P8","R135","SU27","SU30","SU35","SU57",
            "T38","TOR","TU95","U2","V22"
    );

    private static final Set<String> BIZJET_TYPES = Set.of(
            "C25A","C25B","C25C","C510","C525","C550","C560",
            "C56X","C680","C700","C750","CL30","CL35","CL60",
            "E35L","E50P","E55P","E545","E550","FA50","FA7X",
            "FA8X","F2TH","F900","G150","G200","G280","GALX",
            "GL5T","GL6T","GL7T","GLF4","GLF5","GLF6","GLEX",
            "H25B","H25C","HA4T","HDJT","LJ35","LJ40","LJ45",
            "LJ55","LJ60","LJ70","LJ75","LJ85","PRM1","PC12",
            "PC24","SF50","TBM7","TBM8","TBM9","BE40","BE20",
            "BE9L","BE9T","P180","PAY1","PAY2","PAY3","PAY4"
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
    public boolean isInteresting(String aircraftType) {
        return isWidebody(aircraftType) || isMilitary(aircraftType) || isBizjet(aircraftType);
    }

    public boolean isMilitary(String aircraftType) {
        if (aircraftType == null || aircraftType.isEmpty() || "UNKNOWN".equals(aircraftType)) {
            return false;
        }
        String normalized = normalizeAircraftCode(aircraftType);
        return MILITARY_TYPES.contains(normalized)
                || MILITARY_TYPES.stream().anyMatch(t -> normalized.startsWith(t) || t.startsWith(normalized));
    }

    public boolean isBizjet(String aircraftType) {
        if (aircraftType == null || aircraftType.isEmpty() || "UNKNOWN".equals(aircraftType)) {
            return false;
        }
        String normalized = normalizeAircraftCode(aircraftType);
        return BIZJET_TYPES.contains(normalized)
                || BIZJET_TYPES.stream().anyMatch(t -> normalized.startsWith(t) || t.startsWith(normalized));
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
