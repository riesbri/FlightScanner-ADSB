package com.richi.adsb;

/**
 * Callback that returns a current snapshot of ADS-B stream statistics.
 * Passed to {@link com.richi.web.WebServer} so the HTTP layer can read
 * live metrics without depending on the concrete {@code Dump1090DataSource}.
 */
@FunctionalInterface
public interface ADSBStatsProvider {
    ADSBStats getStats();
}
