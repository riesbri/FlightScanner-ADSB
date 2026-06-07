package com.richi.analyzer;

import com.richi.config.ConfigManager;
import com.richi.model.Flight;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.List;

@Slf4j
public class LocalAircraftAnalyzer implements AircraftAnalyzerService {

    private final boolean aiEnabled;
    private final DeepSeekAircraftAnalyzer aiAnalyzer;
    private final AircraftAlerter alerter;

    public LocalAircraftAnalyzer() {
        this(ConfigManager.getInstance());
    }

    public LocalAircraftAnalyzer(ConfigManager config) {
        this.aiEnabled = config.isAiAnalysisEnabled();
        this.aiAnalyzer = aiEnabled ? new DeepSeekAircraftAnalyzer(config) : null;
        this.alerter = new AircraftAlerter(config);
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
        return AircraftTypes.classify(aircraftType) == AircraftTypes.AircraftCategory.WIDEBODY;
    }

    @Override
    public boolean isInteresting(String aircraftType) {
        return isWidebody(aircraftType) || isMilitary(aircraftType) || isBizjet(aircraftType);
    }

    public boolean isMilitary(String aircraftType) {
        return AircraftTypes.classify(aircraftType) == AircraftTypes.AircraftCategory.MILITARY;
    }

    public boolean isBizjet(String aircraftType) {
        return AircraftTypes.classify(aircraftType) == AircraftTypes.AircraftCategory.BIZJET;
    }

    @Override
    public boolean isAlert(Flight flight) {
        return alerter.isAlert(flight);
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
}
