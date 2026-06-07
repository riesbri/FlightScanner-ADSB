package com.richi.model;

import java.time.LocalDateTime;

public record Flight(
        String flightNumber,
        String origin,
        String aircraft,
        LocalDateTime scheduledTime,
        Integer altitude,
        Integer speed,
        String squawk,     // transponder code (e.g. "7700")
        String hexIdent,   // ICAO 24-bit hex address (e.g. "4CA2D6")
        String operator,   // airline/operator from enrichment (e.g. "Ryanair")
        Double latitude,   // WGS-84 latitude from ADS-B position message (null if unknown)
        Double longitude   // WGS-84 longitude from ADS-B position message (null if unknown)
) {

    /** Convenience constructor for scraped flights without ADS-B data */
    public Flight(String flightNumber, String origin, String aircraft, LocalDateTime scheduledTime) {
        this(flightNumber, origin, aircraft, scheduledTime, null, null, null, null, null, null, null);
    }

    /** Convenience constructor for ADS-B flights before alert fields were added */
    public Flight(String flightNumber, String origin, String aircraft, LocalDateTime scheduledTime,
                  Integer altitude, Integer speed) {
        this(flightNumber, origin, aircraft, scheduledTime, altitude, speed, null, null, null, null, null);
    }

    /** Convenience constructor without lat/lon (DB-restored flights, legacy scraped data) */
    public Flight(String flightNumber, String origin, String aircraft, LocalDateTime scheduledTime,
                  Integer altitude, Integer speed, String squawk, String hexIdent, String operator) {
        this(flightNumber, origin, aircraft, scheduledTime, altitude, speed, squawk, hexIdent, operator, null, null);
    }

    /** Deduplication key for NOTEWORTHY cooldown (per flight+time slot). */
    public String getUniqueKey() {
        return flightNumber + "_" + scheduledTime.toString();
    }

    /** Deduplication key for ALERT cooldown — per physical aircraft when hex is known. */
    public String getAlertKey() {
        return (hexIdent != null && !hexIdent.isBlank()) ? hexIdent : getUniqueKey();
    }

    @Override
    public String toString() {
        return String.format("Flight[%s from %s, %s at %s]",
                flightNumber, origin, aircraft, scheduledTime);
    }
}
