package com.richi;

import com.richi.adsb.ADSBDataSource;
import com.richi.adsb.ADSBListener;
import com.richi.adsb.ADSBStats;
import com.richi.adsb.Dump1090DataSource;
import com.richi.analyzer.AircraftAnalyzerService;
import com.richi.analyzer.LocalAircraftAnalyzer;
import com.richi.config.ConfigManager;
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
    
    // Track when we last notified about each flight key, to enforce a real cooldown
    private final Map<String, Instant> notifiedFlights = new ConcurrentHashMap<>();
    private final Duration notificationCooldown;
    
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
                config.getInt("adsb.notification.cooldown.hours", 4)
        );
        
        // Register as listener for real-time events
        dataSource.addListener(this);
        
        setupShutdownHook();
    }
    
    /**
     * Start tracking ADS-B data.
     */
    public void start() throws Exception {
        if (running.compareAndSet(false, true)) {
            log.info("Starting ADSBFlightTracker...");
            
            config.validate();
            repository.initialize();
            
            // Start the ADS-B data source
            dataSource.start();
            
            // Schedule periodic database persistence
            int saveInterval = config.getInt("adsb.save.interval.minutes", 5);
            executor.scheduleAtFixedRate(
                    this::persistFlights,
                    saveInterval,
                    saveInterval,
                    TimeUnit.MINUTES
            );
            
            // Schedule periodic stats logging
            executor.scheduleAtFixedRate(
                    this::logStats,
                    30,
                    30,
                    TimeUnit.SECONDS
            );
            
            // Schedule notification cooldown cleanup
            executor.scheduleAtFixedRate(
                    this::cleanupNotifiedFlights,
                    1,
                    1,
                    TimeUnit.HOURS
            );
            
            log.info("ADSBFlightTracker started. Listening for aircraft...");
        }
    }
    
    /**
     * Stop tracking.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping ADSBFlightTracker...");
            
            dataSource.stop();
            
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            
            repository.close();
            
            if (notifier instanceof AutoCloseable ac) {
                try {
                    ac.close();
                } catch (Exception e) {
                    log.error("Error closing notifier: {}", e.getMessage());
                }
            }
            
            log.info("ADSBFlightTracker stopped");
        }
    }
    
    @Override
    public void close() {
        stop();
    }
    
    /**
     * Check if tracker is running.
     */
    public boolean isRunning() {
        return running.get();
    }
    
    /**
     * Get current statistics.
     */
    public ADSBStats getStats() {
        return dataSource.getStats();
    }
    
    /**
     * Get currently tracked flights.
     */
    public List<Flight> getCurrentFlights() {
        return dataSource.getCurrentFlights();
    }
    
    // ADSBListener callbacks
    
    @Override
    public void onAircraftDetected(Flight flight) {
        log.info("🛬 New aircraft detected: {} ({})", flight.flightNumber(), flight.aircraft());
        
        // Notify if interesting (widebody/military/bizjet), or if notify-all mode is enabled
        if (config.isDiscordNotifyAll() || analyzer.isInteresting(flight.aircraft())) {
            String reason = config.isDiscordNotifyAll() ? "Aircraft detected (all mode)" : "Interesting aircraft detected";
            maybeNotify(flight, reason);
        }
    }
    
    @Override
    public void onAircraftUpdated(Flight flight) {
        log.debug("Aircraft updated: {}", flight.flightNumber());
        // Could implement landing detection here based on altitude changes
    }
    
    @Override
    public void onAircraftLost(Flight flight) {
        log.info("Aircraft lost: {} (probably landed or out of range)", flight.flightNumber());
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
    
    private void maybeNotify(Flight flight, String reason) {
        String key = flight.getUniqueKey();
        Instant now = Instant.now();

        // Enforce a real per-key cooldown: skip if we notified within the window
        Instant last = notifiedFlights.get(key);
        if (last != null && Duration.between(last, now).compareTo(notificationCooldown) < 0) {
            log.debug("Skipping notification for {} (cooldown active)", flight.flightNumber());
            return;
        }

        // Send notification and record the timestamp
        notifier.sendAlert(flight);
        notifiedFlights.put(key, now);

        log.info("📱 Notification sent for {}: {}", flight.flightNumber(), reason);
    }

    private void cleanupNotifiedFlights() {
        // Prune entries whose cooldown has fully expired so the map doesn't grow unbounded.
        // The cooldown decision itself lives in maybeNotify; this is just memory hygiene.
        Instant cutoff = Instant.now().minus(notificationCooldown);
        int before = notifiedFlights.size();
        notifiedFlights.values().removeIf(last -> last.isBefore(cutoff));
        int removed = before - notifiedFlights.size();
        if (removed > 0) {
            log.debug("Pruned {} expired entries from notification cooldown cache", removed);
        }
    }
    
    private void logStats() {
        ADSBStats stats = dataSource.getStats();
        log.info("📡 ADS-B Stats: {} aircraft tracked, {} msgs/sec, connected={}",
                stats.aircraftCount(),
                stats.messagesPerSecond(),
                stats.connected()
        );
    }
    
    private void setupShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown hook triggered");
            stop();
        }));
    }
    
    /**
     * Main entry point for ADS-B mode.
     */
    public static void main(String[] args) {
        try {
            ADSBFlightTracker tracker = new ADSBFlightTracker();
            tracker.start();
            
            // Keep running
            while (tracker.isRunning()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Exception e) {
            log.error("Fatal error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
