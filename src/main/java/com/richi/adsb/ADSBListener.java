package com.richi.adsb;

import com.richi.model.Flight;

/**
 * Listener interface for ADS-B events.
 */
public interface ADSBListener {
    
    /**
     * Called when a new aircraft is detected.
     */
    void onAircraftDetected(Flight flight);
    
    /**
     * Called when an existing aircraft updates its position.
     */
    void onAircraftUpdated(Flight flight);
    
    /**
     * Called when an aircraft is no longer being received (timeout).
     */
    void onAircraftLost(Flight flight);
}
