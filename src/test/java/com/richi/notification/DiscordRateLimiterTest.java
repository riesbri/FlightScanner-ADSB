package com.richi.notification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiscordRateLimiterTest {

    @Test
    void acquiresUnderCap() {
        DiscordRateLimiter limiter = new DiscordRateLimiter(3, true);
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
    }

    @Test
    void blocksAtCap() {
        DiscordRateLimiter limiter = new DiscordRateLimiter(3, true);
        limiter.tryAcquire();
        limiter.tryAcquire();
        limiter.tryAcquire();
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void singleSlotCapBlocksImmediately() {
        DiscordRateLimiter limiter = new DiscordRateLimiter(1, false);
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void recordCoalescedIncrementsCounter() {
        DiscordRateLimiter limiter = new DiscordRateLimiter(1, true);
        assertEquals(1, limiter.recordCoalesced());
        assertEquals(2, limiter.recordCoalesced());
        assertEquals(3, limiter.recordCoalesced());
    }

    @Test
    void takeCoalescedCountReturnsAndResetsCounter() {
        DiscordRateLimiter limiter = new DiscordRateLimiter(1, true);
        limiter.recordCoalesced();
        limiter.recordCoalesced();
        assertEquals(2, limiter.takeCoalescedCount());
        assertEquals(0, limiter.takeCoalescedCount()); // reset
    }

    @Test
    void coalesceEnabledFlagReflectsConfig() {
        assertTrue(new DiscordRateLimiter(10, true).isCoalesceEnabled());
        assertFalse(new DiscordRateLimiter(10, false).isCoalesceEnabled());
    }
}
