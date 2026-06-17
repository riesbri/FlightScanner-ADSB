package com.flightscanner.notification;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sliding-window rate limiter for Discord webhook messages.
 *
 * ALERT-tier sends bypass this limiter entirely — only NOTEWORTHY/ROUTINE
 * traffic (sendAlert) is subject to the cap.
 */
@Slf4j
public class DiscordRateLimiter {

    private final int perMinute;
    private final boolean coalesceEnabled;
    // guarded by this
    private final Deque<Instant> sendTimestamps = new ArrayDeque<>();
    private final AtomicInteger coalescedCount = new AtomicInteger(0);

    public DiscordRateLimiter(int perMinute, boolean coalesceEnabled) {
        this.perMinute = perMinute;
        this.coalesceEnabled = coalesceEnabled;
        log.info("DiscordRateLimiter: {}/min, coalesce={}", perMinute, coalesceEnabled);
    }

    public boolean isCoalesceEnabled() {
        return coalesceEnabled;
    }

    /**
     * Try to acquire a send slot. Returns true if we are under the cap (slot recorded),
     * false if we have hit the per-minute limit.
     */
    public synchronized boolean tryAcquire() {
        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds(60);
        while (!sendTimestamps.isEmpty() && sendTimestamps.peekFirst().isBefore(cutoff)) {
            sendTimestamps.pollFirst();
        }
        if (sendTimestamps.size() < perMinute) {
            sendTimestamps.addLast(now);
            return true;
        }
        return false;
    }

    /** Record one overflow message. Returns the new total. */
    public int recordCoalesced() {
        return coalescedCount.incrementAndGet();
    }

    /** Return and reset the overflow counter. Used when posting the rollup summary. */
    public int takeCoalescedCount() {
        return coalescedCount.getAndSet(0);
    }

    /** Non-destructive read of the current overflow count (for /metrics). */
    public int getCoalescedCount() {
        return coalescedCount.get();
    }
}
