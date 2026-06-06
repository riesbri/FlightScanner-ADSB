package com.richi.analyzer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAircraftAnalyzerTest {

    private static LocalAircraftAnalyzer analyzer;

    @BeforeAll
    static void setUp() {
        // application.properties has ai.analysis.enabled=false, so no DeepSeek client is created.
        analyzer = new LocalAircraftAnalyzer();
    }

    @Test
    void detectsWidebody() {
        assertTrue(analyzer.isWidebody("B777-300ER"));
    }

    @Test
    void detectsMilitary() {
        assertTrue(analyzer.isMilitary("F16"));
    }

    @Test
    void detectsBizjet() {
        assertTrue(analyzer.isBizjet("GLEX"));
    }

    @Test
    void interestingForEachSpecialCategory() {
        assertTrue(analyzer.isInteresting("B777-300ER"));
        assertTrue(analyzer.isInteresting("F16"));
        assertTrue(analyzer.isInteresting("GLEX"));
    }

    @Test
    void narrowbodyIsNotInteresting() {
        assertFalse(analyzer.isInteresting("B738"));
    }
}
