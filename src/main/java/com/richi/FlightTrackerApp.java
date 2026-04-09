package com.richi;

import com.richi.analyzer.AircraftAnalyzerService;
import com.richi.analyzer.LocalAircraftAnalyzer;
import com.richi.config.ConfigManager;
import com.richi.model.Flight;
import com.richi.notification.FlightNotifier;
import com.richi.notification.TelegramFlightNotifier;
import com.richi.repository.FlightRepository;
import com.richi.repository.SqlFlightRepository;
import com.richi.service.FlightScraperService;
import com.richi.service.PlaywrightFlightScraper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class FlightTrackerApp {
    
    private final ConfigManager config;
    private final FlightRepository repository;
    private final FlightScraperService scraper;
    private final AircraftAnalyzerService analyzer;
    private final FlightNotifier notifier;
    
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running;
    private ScheduledFuture<?> scheduledTask;
    
    public FlightTrackerApp() {
        this(ConfigManager.getInstance());
    }
    
    public FlightTrackerApp(ConfigManager config) {
        this.config = config;
        this.repository = new SqlFlightRepository(config);
        this.scraper = new PlaywrightFlightScraper(config);
        this.analyzer = new LocalAircraftAnalyzer(config);
        this.notifier = new TelegramFlightNotifier(config);
        
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "flight-tracker-scheduler");
            t.setDaemon(false);
            return t;
        });
        this.running = new AtomicBoolean(false);
        
        setupShutdownHook();
    }
    
    /**
     * Start the flight tracking service
     */
    public void start() throws Exception {
        if (running.compareAndSet(false, true)) {
            log.info("Starting FlightTracker for airport: {}", config.getAirportCode());
            
            // Validate configuration
            config.validate();
            
            // Initialize database
            repository.initialize();
            log.info("Database initialized");
            
            // Schedule the tracking task
            long intervalMinutes = config.getScrapeIntervalMinutes();
            scheduledTask = scheduler.scheduleAtFixedRate(
                    this::trackFlights,
                    0,  // Initial delay
                    intervalMinutes,
                    TimeUnit.MINUTES
            );
            
            log.info("FlightTracker started. Scraping every {} minutes.", intervalMinutes);
        } else {
            log.warn("FlightTracker is already running");
        }
    }
    
    /**
     * Stop the flight tracking service gracefully
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping FlightTracker...");
            
            // Cancel scheduled task
            if (scheduledTask != null && !scheduledTask.isCancelled()) {
                scheduledTask.cancel(false);
            }
            
            // Shutdown scheduler
            shutdownScheduler();
            
            // Close resources
            closeResources();
            
            log.info("FlightTracker stopped");
        }
    }
    
    /**
     * Check if the tracker is running
     */
    public boolean isRunning() {
        return running.get();
    }
    
    /**
     * Run a single tracking cycle (for testing or manual execution)
     */
    public void runOnce() {
        trackFlights();
    }
    
    private void trackFlights() {
        try {
            log.info("Starting tracking cycle for {}", config.getAirportCode());
            
            // Scrape flights
            List<Flight> arrivals = scraper.scrapeArrivals(config.getAirportCode());
            
            if (arrivals.isEmpty()) {
                log.warn("No flights scraped");
                return;
            }
            
            log.info("Scraped {} flights", arrivals.size());
            
            // Save flights (with duplicate detection)
            int saved = repository.saveFlights(arrivals);
            log.info("Saved {} new flights", saved);
            
            // Analyze for interesting flights
            List<Flight> interestingFlights = analyzer.analyzeFlights(arrivals);
            
            if (!interestingFlights.isEmpty()) {
                log.info("Found {} interesting flights", interestingFlights.size());
                
                // Send notifications
                for (Flight flight : interestingFlights) {
                    notifier.sendAlert(flight);
                }
            }
            
            log.info("Tracking cycle completed successfully");
            
        } catch (Exception e) {
            log.error("Error during tracking cycle: {}", e.getMessage(), e);
        }
    }
    
    private void shutdownScheduler() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                log.warn("Scheduler did not terminate in time, forcing shutdown");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    private void closeResources() {
        try {
            if (scraper != null) {
                scraper.close();
            }
        } catch (Exception e) {
            log.error("Error closing scraper: {}", e.getMessage());
        }
        
        try {
            if (repository != null) {
                repository.close();
            }
        } catch (Exception e) {
            log.error("Error closing repository: {}", e.getMessage());
        }
        
        try {
            if (notifier instanceof TelegramFlightNotifier telegram) {
                telegram.close();
            }
        } catch (Exception e) {
            log.error("Error closing notifier: {}", e.getMessage());
        }
    }
    
    private void setupShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown hook triggered");
            stop();
        }));
    }
    
    /**
     * Main entry point
     */
    public static void main(String[] args) {
        try {
            FlightTrackerApp app = new FlightTrackerApp();
            app.start();
            
            // Keep the main thread alive
            while (app.isRunning()) {
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
