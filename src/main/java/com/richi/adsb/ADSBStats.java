package com.richi.adsb;

import java.time.Instant;

/**
 * Statistics for ADS-B data source.
 */
public record ADSBStats(
        int aircraftCount,
        int messagesReceived,
        int messagesPerSecond,
        Instant lastMessageTime,
        boolean connected,
        String connectionInfo
) {}
