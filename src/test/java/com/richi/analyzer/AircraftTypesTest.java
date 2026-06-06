package com.richi.analyzer;

import com.richi.analyzer.AircraftTypes.AircraftCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AircraftTypesTest {

    @Test
    void widebodyVariantWithSuffixClassifiesAsWidebody() {
        assertEquals(AircraftCategory.WIDEBODY, AircraftTypes.classify("B777-300ER"));
        assertEquals(AircraftCategory.WIDEBODY, AircraftTypes.classify("A350-941"));
    }

    @Test
    void narrowbodyClassifiesAsCommercial() {
        assertEquals(AircraftCategory.COMMERCIAL, AircraftTypes.classify("B738"));
    }

    @Test
    void nullEmptyAndUnknownClassifyAsCommercial() {
        assertEquals(AircraftCategory.COMMERCIAL, AircraftTypes.classify(null));
        assertEquals(AircraftCategory.COMMERCIAL, AircraftTypes.classify(""));
        assertEquals(AircraftCategory.COMMERCIAL, AircraftTypes.classify("UNKNOWN"));
    }

    @Test
    void militaryTypeClassifiesAsMilitary() {
        assertEquals(AircraftCategory.MILITARY, AircraftTypes.classify("F16"));
    }

    @Test
    void bizjetTypeClassifiesAsBizjet() {
        assertEquals(AircraftCategory.BIZJET, AircraftTypes.classify("GLEX"));
    }
}
