package com.richi;

import com.richi.adsb.ADSBDataSource;
import com.richi.adsb.ADSBListener;
import com.richi.adsb.ADSBStats;
import com.richi.adsb.Dump1090DataSource;
import com.richi.analyzer.AircraftAnalyzerService;
import com.richi.analyzer.LocalAircraftAnalyzer;
import com.richi.config.ConfigManager;
import com.richi.config.NotifyLevel;
import com.richi.model.Flight;
import com.richi.notification.DiscordFlightNotifier;
import com.richi.notification.FlightNotifier;
import com.richi.repository.FlightRepository;
import com.richi.repository.SqlFlightRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Flight tracker that uses ADS-B data from a local receiver (dump1090-fa).
 *
 * Unlike the web scraper which polls periodically, this listens to real-time
 * ADS-B messages and can trigger notifications immediately when aircraft are detected.
 */
@Slf4j
public class ADSBFlightTracker implements ADSBListener, AutoCloseable {

    private final ConfigManager config;
    private final ADSBDataSource dataSource;
    private final FlightRepository repository;
    private final AircraftAnalyzerService analyzer;
    private final FlightNotifier notifier;

    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // NOTEWORTHY cooldown — per flight key (flightNumber+scheduledTime)
    private final Map<String, Instant> notifiedFlights = new ConcurrentHashMap<>();
    private final Duration notificationCooldown;

    // ALERT cooldown — per aircraft hex (or flight key if hex unavailable)
    private final Map<String, Instant> alertedFlights = new ConcurrentHashMap<>();
    private final Duration alertCooldown;

    public ADSBFlightTracker() {
        this(ConfigManager.getInstance());
    }

    public ADSBFlightTracker(ConfigManager config) {
        this.config = config;
        this.dataSource = new Dump1090DataSource(config);
        this.repository = new SqlFlightRepository(config);
        this.analyzer = new LocalAircraftAnalyzer(config);
        this.notifier = new DiscordFlightNotifier(config);

        this.executor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "adsb-tracker");
            t.setDaemon(false);
            return t;
        });

        this.notificationCooldown = Duration.ofHours(
                config.getInt("adsb.notification.cooldown.hours", 4));
        this.alertCooldown = Duration.ofMinutes(
                config.getInt("adsb.alert.cooldown.minutes", 5));

        dataSource.addListener(this);
        setupShutdownHook();
    }

    public void start() throws Exception {
        if (running.compareAndSet(false, true)) {
            log.info("Starting ADSBFlightTracker...");

            config.validate();
            repository.initialize();
            dataSource.start();

            int saveInterval = config.getInt("adsb.save.interval.minutes", 5);
            executor.scheduleAtFixedRate(this::persistFlights, saveInterval, saveInterval, TimeUnit.MINUTES);
            executor.scheduleAtFixedRate(this::logStats, 30, 30, TimeUnit.SECONDS);
            executor.scheduleAtFixedRate(this::cleanupNotifiedFlights, 1, 1, TimeUnit.HOURS);

            log.info("ADSBFlightTracker started (notify level: {}). Listening for aircraft...",
                    config.getNotifyLevel());
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping ADSBFlightTracker...");
            dataSource.stop();
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            repository.close();
            if (notifier instanceof AutoCloseable ac) {
                try { ac.close(); } catch (Exception e) { log.error("Error closing notifier: {}", e.getMessage()); }
            }
            log.info("ADSBFlightTracker stopped");
        }
    }

    @Override
    public void close() { stop(); }

    public boolean isRunning() { return running.get(); }

    public ADSBStats getStats() { return dataSource.getStats(); }

    public List<Flight> getCurrentFlights() { return dataSource.getCurrentFlights(); }

    // ── ADSBListener callbacks ───────────────────────────────────────

    @Override
    public void onAircraftDetected(Flight flight) {
        log.info("New aircraft detected: {} ({})", flight.flightNumber(), flight.aircraft());

        boolean alert       = analyzer.isAlert(flight);
        boolean interesting = alert || analyzer.isInteresting(flight.aircraft());

        NotifyLevel level   = config.getNotifyLevel();
        boolean shouldFire  = switch (level) {
            case ALL       -> true;
            case ALERT     -> alert;
            case NOTEWORTHY -> interesting;
        };

        if (config.isDiscordNotifyAll() || shouldFire) {
            if (alert) {
                maybeAlert(flight);
            } else {
                String reason = config.isDiscordNotifyAll()
                        ? "Aircraft detected (all mode)" : "Interesting aircraft detected";
                maybeNotify(flight, reason);
            }
        }
    }

    @Override
    public void onAircraftUpdated(Flight flight) {
        log.debug("Aircraft updated: {}", flight.flightNumber());
    }

    @Override
    public void onAircraftLost(Flight flight) {
        log.info("Aircraft lost: {} (probably landed or out of range)", flight.flightNumber());
    }

    // ── Notification helpers ─────────────────────────────────────────

    private void maybeNotify(Flight flight, String reason) {
        String key = flight.getUniqueKey();
        Instant now = Instant.now();

        Instant last = notifiedFlights.get(key);
        if (last != null && Duration.between(last, now).compareTo(notificationCooldown) < 0) {
            log.debug("Skipping notification for {} (cooldown active)", flight.flightNumber());
            return;
        }

        notifier.sendAlert(flight);
        notifiedFlights.put(key, now);
        log.info("Notification sent for {}: {}", flight.flightNumber(), reason);
    }

    private void maybeAlert(Flight flight) {
        String key = flight.getAlertKey();
        Instant now = Instant.now();

        Instant last = alertedFlights.get(key);
        if (last != null && Duration.between(last, now).compareTo(alertCooldown) < 0) {
            log.debug("Skipping ALERT for {} (alert cooldown active)", flight.flightNumber());
            return;
        }

        notifier.sendCriticalAlert(flight);
        alertedFlights.put(key, now);
        log.info("ALERT notification sent for {} (hex={})", flight.flightNumber(), flight.hexIdent());
    }

    private void cleanupNotifiedFlights() {
        Instant notifyCutoff = Instant.now().minus(notificationCooldown);
        int before = notifiedFlights.size();
        notifiedFlights.values().removeIf(t -> t.isBefore(notifyCutoff));
        int removed = before - notifiedFlights.size();
        if (removed > 0) log.debug("Pruned {} expired entries from NOTEWORTHY cooldown cache", removed);

        Instant alertCutoff = Instant.now().minus(alertCooldown);
        int alertBefore = alertedFlights.size();
        alertedFlights.values().removeIf(t -> t.isBefore(alertCutoff));
        int alertRemoved = alertBefore - alertedFlights.size();
        if (alertRemoved > 0) log.debug("Pruned {} expired entries from ALERT cooldown cache", alertRemoved);
    }

    private void persistFlights() {
        try {
            List<Flight> flights = dataSource.getCurrentFlights();
            if (!flights.isEmpty()) {
                int saved = repository.saveFlights(flights);
                log.debug("Persisted {}/{} flights to database", saved, flights.size());
            }
        } catch (Exception e) {
            log.error("Error persisting flights: {}", e.getMessage());
        }
    }

    private void logStats() {
        ADSBStats stats = dataSource.getStats();
        log.info("ADS-B Stats: {} aircraft tracked, {} msgs/sec, connected={}",
                stats.aircraftCount(), stats.messagesPerSecond(), stats.connected());
    }

    private void setupShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown hook triggered");
            stop();
        }));
    }

    public static void main(String[] args) {
        try {
            ADSBFlightTracker tracker = new ADSBFlightTracker();
            tracker.start();
            while (tracker.isRunning()) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        } catch (Exception e) {
            log.error("Fatal error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
