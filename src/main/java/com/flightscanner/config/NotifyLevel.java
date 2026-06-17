package com.flightscanner.config;

/**
 * Notification tier for the ADS-B tracker.
 *
 * ALL        — notify every detected flight (testing / demo)
 * NOTEWORTHY — notify widebody / military / bizjet (default)
 * ALERT      — notify only ALERT-tier events (squawk / low-alt / gov-hex / mil-operator)
 *
 * Configured via {@code discord.notify.level} in application.properties.
 */
public enum NotifyLevel {
    ALL, NOTEWORTHY, ALERT;

    public static NotifyLevel parse(String value) {
        if (value == null || value.isBlank()) return NOTEWORTHY;
        return switch (value.trim().toLowerCase()) {
            case "all"   -> ALL;
            case "alert" -> ALERT;
            default      -> NOTEWORTHY;
        };
    }
}
