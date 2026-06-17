package com.flightscanner.notification;

import java.time.Instant;

/**
 * Immutable snapshot of Discord notification activity for monitoring endpoints.
 * Produced by {@link DiscordFlightNotifier#getDiscordStats()}.
 */
public record DiscordStats(
        long notificationsSent,
        long alertsSent,
        int coalescedCount,
        Instant lastSendAt
) {}
