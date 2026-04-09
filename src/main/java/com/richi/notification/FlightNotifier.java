package com.richi.notification;

import com.richi.model.Flight;

public interface FlightNotifier {
    
    /**
     * Send a notification about an interesting flight
     */
    void sendAlert(Flight flight);
    
    /**
     * Send a batch notification about multiple flights
     */
    void sendBatchAlert(java.util.List<Flight> flights);
    
    /**
     * Test the notification connection
     */
    boolean testConnection();
}
