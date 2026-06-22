package com.flightscanner.notification;

import com.flightscanner.model.Flight;

public interface FlightNotifier {

    /** Send a NOTEWORTHY-tier notification embed for one flight. */
    void sendAlert(Flight flight);

    /** Like {@link #sendAlert(Flight)} but includes an optional extra note (e.g. "Last seen 3 days ago"). */
    default void sendAlert(Flight flight, String note) {
        sendAlert(flight);
    }

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

    /** Post a daily digest summary embed. No-op by default. */
    default void sendDailyDigest(java.util.List<Flight> flights, java.time.LocalDate date) {}
}
