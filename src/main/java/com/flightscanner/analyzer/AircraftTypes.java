package com.flightscanner.analyzer;

import java.util.Set;

/**
 * Single source of truth for the ICAO type-code allowlists used to classify
 * aircraft as widebody / military / bizjet. Both LocalAircraftAnalyzer (for
 * "is this interesting?" filtering) and DiscordFlightNotifier (for embed
 * emoji + color) read from these sets.
 *
 * Add new type codes here — not in the consuming classes.
 */
public final class AircraftTypes {

    public static final Set<String> WIDEBODY = Set.of(
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

    public static final Set<String> MILITARY = Set.of(
            "A400", "C130", "C17", "C5M", "C5", "C141", "C160", "CN35", "CN95",
            "E3TF", "E737", "EUFI", "F15", "F16", "F18", "F22", "F35", "F4", "F5",
            "H47", "H53", "H60", "H64", "K35R", "KC10", "KC135", "KC46",
            "P3", "P8", "R135", "SU27", "SU30", "SU35", "SU57",
            "T38", "TOR", "TU95", "U2", "V22"
    );

    public static final Set<String> BIZJET = Set.of(
            "C25A", "C25B", "C25C", "C510", "C525", "C550", "C560",
            "C56X", "C680", "C700", "C750", "CL30", "CL35", "CL60",
            "E35L", "E50P", "E55P", "E545", "E550", "FA50", "FA7X",
            "FA8X", "F2TH", "F900", "G150", "G200", "G280", "GALX",
            "GL5T", "GL6T", "GL7T", "GLF4", "GLF5", "GLF6", "GLEX",
            "H25B", "H25C", "HA4T", "HDJT", "LJ35", "LJ40", "LJ45",
            "LJ55", "LJ60", "LJ70", "LJ75", "LJ85", "PRM1", "PC12",
            "PC24", "SF50", "TBM7", "TBM8", "TBM9", "BE40", "BE20",
            "BE9L", "BE9T", "P180", "PAY1", "PAY2", "PAY3", "PAY4"
    );

    private AircraftTypes() {}

    /**
     * Coarse classification of an aircraft.
     * ALERT is set by AircraftAlerter (squawk/altitude/hex/operator triggers),
     * not by ICAO type code — AircraftTypes.classify() never returns ALERT.
     */
    public enum AircraftCategory {
        ALERT, COMMERCIAL, WIDEBODY, MILITARY, BIZJET
    }

    /**
     * Classify a (possibly messy) aircraft type code into a single category.
     * This is the one place the normalize-then-prefix-match logic lives;
     * both LocalAircraftAnalyzer and DiscordFlightNotifier delegate here so
     * "is this interesting?" and the Discord embed emoji can never disagree.
     */
    public static AircraftCategory classify(String aircraftType) {
        if (aircraftType == null || aircraftType.isEmpty() || "UNKNOWN".equalsIgnoreCase(aircraftType)) {
            return AircraftCategory.COMMERCIAL;
        }
        String normalized = normalize(aircraftType);
        if (matches(MILITARY, normalized)) return AircraftCategory.MILITARY;
        if (matches(WIDEBODY, normalized)) return AircraftCategory.WIDEBODY;
        if (matches(BIZJET, normalized)) return AircraftCategory.BIZJET;
        return AircraftCategory.COMMERCIAL;
    }

    private static boolean matches(Set<String> set, String normalized) {
        if (set.contains(normalized)) {
            return true;
        }
        // Prefix match in either direction (e.g. "B777-300ER" → "B773", "F1" → "F16")
        return set.stream().anyMatch(t -> normalized.startsWith(t) || t.startsWith(normalized));
    }

    private static String normalize(String code) {
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
