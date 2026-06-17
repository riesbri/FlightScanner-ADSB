package com.flightscanner.adsb;

import com.flightscanner.model.Flight;

import java.util.List;

/**
 * Interface for ADS-B data sources.
 * Provides real-time aircraft data from receivers like dump1090-fa.
 */
public interface ADSBDataSource {
    
    /**
     * Start receiving data from the source.
     * This typically opens a socket connection or starts polling.
     */
    void start();
    
    /**
     * Stop receiving data and close connections.
     */
    void stop();
    
    /**
     * Check if the data source is currently connected and receiving.
     */
    boolean isConnected();
    
    /**
     * Get all currently tracked aircraft.
     * @return list of flights currently being tracked
     */
    List<Flight> getCurrentFlights();
    
    /**
     * Get flights arriving at or departing from a specific airport.
     * This requires airport proximity detection based on position.
     * @param airportCode ICAO or IATA code of the airport
     * @param radiusNm radius in nautical miles to consider "at" the airport
     * @return list of flights near the airport
     */
    List<Flight> getFlightsNearAirport(String airportCode, double radiusNm);
    
    /**
     * Get a specific aircraft by its callsign.
     */
    Flight getFlightByCallsign(String callsign);
    
    /**
     * Get statistics about the data source.
     */
    ADSBStats getStats();
    
    /**
     * Add a listener for new aircraft events.
     */
    void addListener(ADSBListener listener);
    
    /**
     * Remove a listener.
     */
    void removeListener(ADSBListener listener);
}
