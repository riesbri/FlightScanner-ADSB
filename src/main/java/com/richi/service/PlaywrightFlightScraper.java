package com.richi.service;

import com.microsoft.playwright.*;
import com.richi.config.ConfigManager;
import com.richi.model.Flight;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
public class PlaywrightFlightScraper implements FlightScraperService {
    
    private final int maxRetries;
    private final int retryDelaySeconds;
    private Playwright playwright;
    private Browser browser;
    private boolean initialized = false;
    
    private static final String BASE_URL = "https://www.flightradar24.com/airport/%s/arrivals";
    private static final DateTimeFormatter TIME_FORMATTER = 
            DateTimeFormatter.ofPattern("h:mm a").withLocale(Locale.US);
    
    public PlaywrightFlightScraper() {
        this(ConfigManager.getInstance());
    }
    
    public PlaywrightFlightScraper(ConfigManager config) {
        this.maxRetries = config.getMaxRetries();
        this.retryDelaySeconds = config.getRetryDelaySeconds();
        initialize();
    }
    
    private synchronized void initialize() {
        if (initialized) return;
        
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setHeadless(true)
            );
            initialized = true;
            log.info("Playwright browser initialized");
        } catch (Exception e) {
            log.error("Failed to initialize Playwright: {}", e.getMessage());
            throw new RuntimeException("Could not initialize browser", e);
        }
    }
    
    @Override
    public List<Flight> scrapeArrivals(String airportCode) {
        if (!initialized) {
            throw new IllegalStateException("Scraper not initialized");
        }
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("Scraping arrivals for {} (attempt {}/{})", airportCode, attempt, maxRetries);
                List<Flight> flights = doScrape(airportCode);
                log.info("Successfully scraped {} flights for {}", flights.size(), airportCode);
                return flights;
            } catch (Exception e) {
                log.warn("Scrape attempt {} failed: {}", attempt, e.getMessage());
                if (attempt < maxRetries) {
                    sleep(retryDelaySeconds * 1000L);
                }
            }
        }
        
        log.error("All {} scrape attempts failed for {}", maxRetries, airportCode);
        return new ArrayList<>();
    }
    
    private List<Flight> doScrape(String airportCode) {
        List<Flight> arrivals = new ArrayList<>();
        Page page = null;
        
        try {
            page = browser.newPage();
            page.setViewportSize(1920, 1080);
            
            String url = String.format(BASE_URL, airportCode);
            page.navigate(url);
            
            // Wait for the page to load
            page.waitForSelector(
                    "[data-testid='airport-panel__header__name']",
                    new Page.WaitForSelectorOptions().setTimeout(20000)
            );
            
            // Additional wait for flight list to populate
            page.waitForTimeout(2000);
            
            List<ElementHandle> flightElements = page.querySelectorAll("li.airport__flight-list-item");
            log.debug("Found {} flight elements", flightElements.size());
            
            LocalDate today = LocalDate.now();
            
            for (ElementHandle flight : flightElements) {
                try {
                    Flight parsed = parseFlightElement(flight, today);
                    if (parsed != null) {
                        arrivals.add(parsed);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse flight element: {}", e.getMessage());
                }
            }
            
        } finally {
            if (page != null) {
                page.close();
            }
        }
        
        return arrivals;
    }
    
    private Flight parseFlightElement(ElementHandle flight, LocalDate baseDate) {
        // Flight Info Container
        ElementHandle flightInfo = flight.querySelector("span.text-gray-900:has(span.bg-blue-200)");
        if (flightInfo == null) {
            log.debug("Flight info container not found, skipping");
            return null;
        }
        
        // Flight Number
        String flightNumber = (String) flightInfo.evaluate("""
            el => el.firstChild.textContent.replace('·', '').trim()
        """);
        
        if (flightNumber == null || flightNumber.isEmpty()) {
            log.debug("Empty flight number, skipping");
            return null;
        }
        
        // Aircraft Type
        ElementHandle aircraftElement = flightInfo.querySelector("span.bg-blue-200");
        String aircraft = aircraftElement != null
                ? aircraftElement.innerText().trim()
                : "UNKNOWN";
        
        // Origin Airport
        ElementHandle originElement = flight.querySelector("span.max-w-28");
        String origin = originElement != null
                ? originElement.innerText().trim()
                : "UNKNOWN";
        
        // Scheduled Time
        ElementHandle timeElement = flight.querySelector("[data-testid='base-day-period-formatter']");
        String timeStr = timeElement != null
                ? timeElement.innerText().replaceAll("\\s+", " ").trim()
                : null;
        
        LocalDateTime scheduledTime = parseTime(timeStr, baseDate);
        
        return new Flight(flightNumber, origin, aircraft, scheduledTime);
    }
    
    private LocalDateTime parseTime(String timeStr, LocalDate baseDate) {
        if (timeStr == null || timeStr.isEmpty() || "NO TIME".equals(timeStr)) {
            // Default to current time if not specified
            return LocalDateTime.now();
        }
        
        try {
            LocalTime time = LocalTime.parse(timeStr.toUpperCase(), TIME_FORMATTER);
            return LocalDateTime.of(baseDate, time);
        } catch (Exception e) {
            log.warn("Failed to parse time '{}': {}", timeStr, e.getMessage());
            return LocalDateTime.now();
        }
    }
    
    @Override
    public void close() {
        if (browser != null) {
            browser.close();
            browser = null;
        }
        if (playwright != null) {
            playwright.close();
            playwright = null;
        }
        initialized = false;
        log.info("Playwright resources released");
    }
    
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
