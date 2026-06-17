package com.flightscanner.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigManagerTest {

    @BeforeEach
    void resetSingleton() {
        ConfigManager.reset();
    }

    @AfterEach
    void resetAfter() {
        ConfigManager.reset();
    }

    // ── NotifyLevel.parse() ──────────────────────────────────────────

    @Test
    void parsesAll() {
        assertEquals(NotifyLevel.ALL, NotifyLevel.parse("all"));
        assertEquals(NotifyLevel.ALL, NotifyLevel.parse("ALL"));
    }

    @Test
    void parsesAlert() {
        assertEquals(NotifyLevel.ALERT, NotifyLevel.parse("alert"));
        assertEquals(NotifyLevel.ALERT, NotifyLevel.parse("ALERT"));
    }

    @Test
    void parsesNoteworthy() {
        assertEquals(NotifyLevel.NOTEWORTHY, NotifyLevel.parse("noteworthy"));
    }

    @Test
    void nullFallsToNoteworthy() {
        assertEquals(NotifyLevel.NOTEWORTHY, NotifyLevel.parse(null));
    }

    @Test
    void unknownValueFallsToNoteworthy() {
        assertEquals(NotifyLevel.NOTEWORTHY, NotifyLevel.parse("unknown_value"));
        assertEquals(NotifyLevel.NOTEWORTHY, NotifyLevel.parse(""));
    }

    // ── ConfigManager defaults from application.properties ───────────

    @Test
    void notifyLevelDefaultsToNoteworthy() {
        // application.properties has discord.notify.level=noteworthy
        assertEquals(NotifyLevel.NOTEWORTHY, ConfigManager.getInstance().getNotifyLevel());
    }

    @Test
    void startupTestMessageDefaultsFalse() {
        // application.properties has discord.startup.test.message=false
        assertFalse(ConfigManager.getInstance().isStartupTestMessage());
    }

    @Test
    void isDiscordNotifyAllDefaultsFalse() {
        // discord.notify.all is commented out in application.properties
        assertFalse(ConfigManager.getInstance().isDiscordNotifyAll());
    }
}
