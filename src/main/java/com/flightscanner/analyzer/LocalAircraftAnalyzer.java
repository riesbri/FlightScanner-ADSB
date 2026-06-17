package com.flightscanner.analyzer;

import com.flightscanner.config.ConfigManager;
import com.flightscanner.model.Flight;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class LocalAircraftAnalyzer implements AircraftAnalyzerService {

    private final AircraftAlerter alerter;

    public LocalAircraftAnalyzer() {
        this(ConfigManager.getInstance());
    }

    public LocalAircraftAnalyzer(ConfigManager config) {
        this.alerter = new AircraftAlerter(config);
        log.info("Aircraft analyzer initialized");
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
    public List<Flight> analyzeFlights(List<Flight> flights) {
        return flights.stream()
                .filter(f -> isInteresting(f.aircraft()))
                .toList();
    }
}
