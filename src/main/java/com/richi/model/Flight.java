package com.richi.model;

import java.time.LocalDateTime;

public record Flight(
        String flightNumber,
        String origin,
        String aircraft,
        LocalDateTime scheduledTime,
        Integer altitude,
        Integer speed
) {
    
    /** Convenience constructor for scraped flights without ADS-B data */
    public Flight(String flightNumber, String origin, String aircraft, LocalDateTime scheduledTime) {
        this(flightNumber, origin, aircraft, scheduledTime, null, null);
    }
    
    /**
     * Get a unique identifier for deduplication
     */
    public String getUniqueKey() {
        return flightNumber + "_" + scheduledTime.toString();
    }
    
    @Override
    public String toString() {
        return String.format("Flight[%s from %s, %s at %s]",
                flightNumber, origin, aircraft, scheduledTime);
    }
}
