package com.richi.adsb;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Represents a single SBS/BaseStation message from dump1090.
 * 
 * SBS Format (CSV):
 * MSG,<transmission_type>,<session_id>,<aircraft_id>,<hex_ident>,<flight_id>,
 * <date_gen>,<time_gen>,<date_log>,<time_log>,<callsign>,<altitude>,<speed>,
 * <heading>,<latitude>,<longitude>,<vertical_rate>,<squawk>,<alert>,<emergency>,
 * <spi>,<is_on_ground>
 */
public record SBSMessage(
        String messageType,      // MSG, SEL, ID, AIR, STA, CLK
        int transmissionType,    // 1-8 (ES message types)
        String sessionId,
        String aircraftId,
        String hexIdent,         // ICAO 24-bit address
        String flightId,
        LocalDateTime generated,
        String callsign,         // Flight number
        Integer altitude,        // Feet
        Integer speed,           // Knots
        Integer heading,         // Degrees
        Double latitude,
        Double longitude,
        Integer verticalRate,    // Feet per minute
        String squawk,           // Transponder code
        Boolean alert,
        Boolean emergency,
        Boolean spi,
        Boolean isOnGround
) {
    
    private static final DateTimeFormatter DATE_FORMATTER = 
            DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final DateTimeFormatter TIME_FORMATTER = 
            DateTimeFormatter.ofPattern("HH:mm:ss");
    
    /**
     * Parse a line of SBS format data.
     */
    public static SBSMessage parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        
        String[] parts = line.split(",");
        if (parts.length < 4 || !"MSG".equals(parts[0])) {
            return null;  // Not a MSG type or too short
        }
        
        try {
            String messageType = parts[0];
            int transmissionType = parseInt(parts[1], 0);
            String sessionId = parts[2];
            String aircraftId = parts[3];
            String hexIdent = parts.length > 4 ? parts[4] : "";
            String flightId = parts.length > 5 ? parts[5] : "";
            
            // Parse timestamp
            LocalDateTime generated = null;
            if (parts.length > 7) {
                generated = parseDateTime(parts[6], parts[7]);
            }
            
            // Callsign is in different positions depending on transmission type
            String callsign = null;
            if (parts.length > 10) {
                callsign = parts[10].trim();
                if (callsign.isEmpty()) callsign = null;
            }
            
            // Parse numeric fields
            Integer altitude = parts.length > 11 ? parseInt(parts[11], null) : null;
            Integer speed = parts.length > 12 ? parseInt(parts[12], null) : null;
            Integer heading = parts.length > 13 ? parseInt(parts[13], null) : null;
            Double latitude = parts.length > 14 ? parseDouble(parts[14], null) : null;
            Double longitude = parts.length > 15 ? parseDouble(parts[15], null) : null;
            Integer verticalRate = parts.length > 16 ? parseInt(parts[16], null) : null;
            // squawk: 4-digit transponder code, empty → null
            String squawk = parts.length > 17 ? nullIfEmpty(parts[17]) : null;
            // alert/emergency/spi/isOnGround: SBS uses "1" for true, "0" or empty for false/unknown
            Boolean alert      = parts.length > 18 ? parseBool(parts[18]) : null;
            Boolean emergency  = parts.length > 19 ? parseBool(parts[19]) : null;
            Boolean spi        = parts.length > 20 ? parseBool(parts[20]) : null;
            Boolean isOnGround = parts.length > 21 ? parseBool(parts[21]) : null;

            return new SBSMessage(
                    messageType,
                    transmissionType,
                    sessionId,
                    aircraftId,
                    hexIdent,
                    flightId,
                    generated,
                    callsign,
                    altitude,
                    speed,
                    heading,
                    latitude,
                    longitude,
                    verticalRate,
                    squawk,
                    alert,
                    emergency,
                    spi,
                    isOnGround
            );
            
        } catch (Exception e) {
            // Malformed message
            return null;
        }
    }
    
    private static LocalDateTime parseDateTime(String dateStr, String timeStr) {
        try {
            LocalDate date = LocalDate.parse(dateStr, DATE_FORMATTER);
            LocalTime time = LocalTime.parse(timeStr, TIME_FORMATTER);
            return LocalDateTime.of(date, time);
        } catch (Exception e) {
            return null;
        }
    }
    
    private static Integer parseInt(String str, Integer defaultVal) {
        try {
            String trimmed = str.trim();
            if (trimmed.isEmpty()) return defaultVal;
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
    
    private static Double parseDouble(String str, Double defaultVal) {
        try {
            String trimmed = str.trim();
            if (trimmed.isEmpty()) return defaultVal;
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    /** Returns null if the field is empty/blank, otherwise the trimmed value. */
    private static String nullIfEmpty(String str) {
        if (str == null) return null;
        String t = str.trim();
        return t.isEmpty() ? null : t;
    }

    /** SBS boolean: "1" → true, "0" → false, empty/other → null (unknown). */
    private static Boolean parseBool(String str) {
        if (str == null || str.trim().isEmpty()) return null;
        return "1".equals(str.trim());
    }
    
    /**
     * Check if this message contains identification info (callsign).
     */
    public boolean hasIdentification() {
        return callsign != null && !callsign.isEmpty();
    }
    
    /**
     * Check if this message contains position info.
     */
    public boolean hasPosition() {
        return latitude != null && longitude != null;
    }
    
    /**
     * Check if this message contains altitude info.
     */
    public boolean hasAltitude() {
        return altitude != null;
    }
    
    @Override
    public String toString() {
        return String.format("SBS[hex=%s, cs=%s, alt=%s, pos=%.4f,%.4f]",
                hexIdent, callsign, altitude, latitude, longitude);
    }
}
