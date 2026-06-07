package com.richi.analyzer;

import com.richi.model.Flight;

import java.util.List;

public interface AircraftAnalyzerService {

    List<String> findWidebodyFlights(List<Flight> flights);

    boolean isWidebody(String aircraftType);

    /** Returns true for widebody / military / bizjet aircraft types. */
    boolean isInteresting(String aircraftType);

    /** Returns true if this flight warrants an ALERT-tier notification (squawk/altitude/hex/operator).
     *  Default returns false; LocalAircraftAnalyzer overrides via AircraftAlerter. */
    default boolean isAlert(Flight flight) {
        return false;
    }

    List<String> findInterestingFlights(List<Flight> flights);

    List<Flight> analyzeFlights(List<Flight> flights);
}
