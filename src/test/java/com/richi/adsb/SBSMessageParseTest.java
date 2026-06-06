package com.richi.adsb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SBSMessageParseTest {

    @Test
    void parsesKnownGoodLine() {
        String line = "MSG,3,1,1,4CA2D6,1,2024/01/01,12:00:00,2024/01/01,12:00:00,"
                + "RYR123,35000,450,180,51.5,-0.1,0,7000,0,0,0,0";

        SBSMessage msg = SBSMessage.parse(line);

        assertNotNull(msg);
        assertEquals("4CA2D6", msg.hexIdent());
        assertEquals("RYR123", msg.callsign());
        assertEquals(35000, msg.altitude());
        assertEquals(450, msg.speed());
        assertEquals("7000", msg.squawk());
        assertEquals(51.5, msg.latitude());
        assertEquals(-0.1, msg.longitude());
    }

    @Test
    void malformedLineReturnsNull() {
        assertNull(SBSMessage.parse("GARBAGE"));
        assertNull(SBSMessage.parse(""));
        assertNull(SBSMessage.parse(null));
    }
}
