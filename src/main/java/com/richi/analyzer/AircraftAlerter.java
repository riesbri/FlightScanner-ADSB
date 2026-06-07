package com.richi.analyzer;

import com.richi.config.ConfigManager;
import com.richi.model.Flight;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Evaluates whether a Flight warrants an ALERT-tier notification.
 *
 * Triggers (any one is sufficient):
 *  - squawk matches a configured emergency code (7500 hijack, 7600 comms failure, 7700 emergency)
 *  - altitude is below the configured low-altitude threshold
 *  - ICAO hex address falls in a government/state hex range
 *  - operator name contains a military keyword (case-insensitive substring)
 *
 * Thresholds are read from ConfigManager at construction time.
 */
@Slf4j
public class AircraftAlerter {

    private final Set<String> emergencySquawks;
    private final int lowAltitudeFeet;
    private final List<int[]> govHexRanges;          // pairs of [low, high] inclusive
    private final List<String> militaryKeywords;     // lowercase, for contains() check

    public AircraftAlerter(ConfigManager config) {
        String squawksStr = config.getString("adsb.alert.emergency.squawks", "7500,7600,7700");
        this.emergencySquawks = Arrays.stream(squawksStr.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());

        this.lowAltitudeFeet = config.getInt("adsb.alert.low.altitude.feet", 1500);

        String hexRangesStr = config.getString("adsb.alert.gov.hex.ranges", "0x348000-0x34FFFF");
        this.govHexRanges = parseHexRanges(hexRangesStr);

        String operatorsStr = config.getString("adsb.alert.military.operators",
                "Ejercito,Fuerza Aerea,RAAF,USAF,RAF,Luftwaffe,Royal Air Force,Royal Navy,Armee de l Air,Aeronautica Militare");
        this.militaryKeywords = Arrays.stream(operatorsStr.split(","))
                .map(String::trim).map(String::toLowerCase)
                .filter(s -> !s.isEmpty()).collect(Collectors.toList());

        log.info("AircraftAlerter initialized: {} squawks, low-alt={}ft, {} gov ranges, {} mil keywords",
                emergencySquawks.size(), lowAltitudeFeet, govHexRanges.size(), militaryKeywords.size());
    }

    /** Package-private constructor for tests — bypasses config parsing. */
    AircraftAlerter(Set<String> emergencySquawks, int lowAltitudeFeet,
                    List<int[]> govHexRanges, List<String> militaryKeywords) {
        this.emergencySquawks = emergencySquawks;
        this.lowAltitudeFeet  = lowAltitudeFeet;
        this.govHexRanges     = govHexRanges;
        this.militaryKeywords = militaryKeywords;
    }

    public boolean isAlert(Flight flight) {
        if (isEmergencySquawk(flight))    return true;
        if (isLowAltitude(flight))        return true;
        if (isInGovHexRange(flight))      return true;
        if (isMilitaryOperator(flight))   return true;
        return false;
    }

    private boolean isEmergencySquawk(Flight flight) {
        if (flight.squawk() == null || flight.squawk().isBlank()) return false;
        return emergencySquawks.contains(flight.squawk().trim());
    }

    private boolean isLowAltitude(Flight flight) {
        // Only trigger for aircraft that are airborne and below threshold
        return flight.altitude() != null && flight.altitude() > 0
                && flight.altitude() < lowAltitudeFeet;
    }

    private boolean isInGovHexRange(Flight flight) {
        if (flight.hexIdent() == null || flight.hexIdent().isBlank()) return false;
        try {
            int val = Integer.parseInt(flight.hexIdent().trim(), 16);
            for (int[] range : govHexRanges) {
                if (val >= range[0] && val <= range[1]) return true;
            }
        } catch (NumberFormatException ignored) {
            // Non-hex hexIdent — not in any range
        }
        return false;
    }

    private boolean isMilitaryOperator(Flight flight) {
        if (flight.operator() == null || flight.operator().isBlank()) return false;
        String opLower = flight.operator().toLowerCase();
        for (String kw : militaryKeywords) {
            if (opLower.contains(kw)) return true;
        }
        return false;
    }

    // ── Config parsing helpers ───────────────────────────────────────────────

    private List<int[]> parseHexRanges(String rangesStr) {
        List<int[]> result = new ArrayList<>();
        if (rangesStr == null || rangesStr.isBlank()) return result;
        for (String part : rangesStr.split(",")) {
            String s = part.trim();
            int dashIdx = findRangeDash(s);
            if (dashIdx < 1) continue;
            try {
                int low  = parseHexLiteral(s.substring(0, dashIdx).trim());
                int high = parseHexLiteral(s.substring(dashIdx + 1).trim());
                result.add(new int[]{low, high});
            } catch (NumberFormatException e) {
                log.warn("Skipping invalid hex range '{}': {}", s, e.getMessage());
            }
        }
        return result;
    }

    /** Find the '-' that separates low from high in "0x348000-0x34FFFF". */
    private int findRangeDash(String s) {
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            char prev = s.charAt(i - 1);
            if (c == '-' && prev != 'x' && prev != 'X') return i;
        }
        return -1;
    }

    private int parseHexLiteral(String s) {
        if (s.startsWith("0x") || s.startsWith("0X")) return Integer.parseInt(s.substring(2), 16);
        return Integer.parseInt(s, 16);
    }
}
