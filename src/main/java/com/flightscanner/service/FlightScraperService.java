package com.flightscanner.service;

import com.flightscanner.model.Flight;

import java.util.List;

public interface FlightScraperService {
    
    /**
     * Scrape arrivals for the given airport code
     * @param airportCode IATA airport code (e.g., VLC, MAD)
     * @return list of flights found
     */
    List<Flight> scrapeArrivals(String airportCode);
    
    /**
     * Close the scraper and release resources
     */
    void close();
}
