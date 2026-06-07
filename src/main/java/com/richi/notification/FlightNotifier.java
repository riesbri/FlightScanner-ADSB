package com.richi.notification;

import com.richi.model.Flight;

public interface FlightNotifier {

    /** Send a NOTEWORTHY-tier notification embed for one flight. */
    void sendAlert(Flight flight);

    /** Send an ALERT-tier notification — always one-per-aircraft, never batched. */
    default void sendCriticalAlert(Flight flight) {
        sendAlert(flight);  // fallback for non-Discord notifiers
    }

    /** Send a batch notification about multiple flights. */
    void sendBatchAlert(java.util.List<Flight> flights);

    /** Test the notification connection. */
    boolean testConnection();

    /**
     * Post a single "📊 +N more…" summary embed for any rate-limited overflow, then
     * reset the counter.  No-op when there is nothing coalesced or the notifier is
     * disabled.
     */
    default void flushCoalescedSummary() {}
}
