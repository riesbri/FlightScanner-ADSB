package com.flightscanner.geo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ICAOCountryTest {

    @Test
    void ukHexReturnsUkFlag() {
        assertEquals("🇬🇧", ICAOCountry.flagFromHex("400000"));
    }

    @Test
    void usHexReturnsUsFlag() {
        assertEquals("🇺🇸", ICAOCountry.flagFromHex("A00000"));
        assertEquals("🇺🇸", ICAOCountry.flagFromHex("AFFFFF"));
    }

    @Test
    void germanyHexReturnsDeFlag() {
        assertEquals("🇩🇪", ICAOCountry.flagFromHex("3C0000"));
    }

    @Test
    void franceHexReturnsFrFlag() {
        assertEquals("🇫🇷", ICAOCountry.flagFromHex("380000"));
    }

    @Test
    void countryFromHexReturnsName() {
        assertEquals("United Kingdom", ICAOCountry.countryFromHex("400000"));
    }

    @Test
    void unknownHexReturnsEmptyFlag() {
        assertEquals("", ICAOCountry.flagFromHex("000001"));
    }

    @Test
    void nullHexReturnsEmptyFlag() {
        assertEquals("", ICAOCountry.flagFromHex(null));
    }

    @Test
    void blankHexReturnsEmptyFlag() {
        assertEquals("", ICAOCountry.flagFromHex("   "));
    }

    @Test
    void caseInsensitiveHex() {
        assertEquals(ICAOCountry.flagFromHex("400000"), ICAOCountry.flagFromHex("400000".toLowerCase()));
    }
}
