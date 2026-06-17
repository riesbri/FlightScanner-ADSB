package com.flightscanner.service;

import com.microsoft.playwright.*;
import com.flightscanner.config.ConfigManager;
import com.flightscanner.model.Flight;
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
            log.info("Navigating to {}", url);
            page.navigate(url, new Page.NavigateOptions().setTimeout(15000));
            
            // Wait for and accept cookies if popup appears
            try {
                // First, check for main consent modal and accept it
                // Try text-based selectors first
                String[] buttonTexts = {"Agree and close", "I agree", "Accept all", "Got it", "Accept cookies"};
                for (String text : buttonTexts) {
                    try {
                        String selector = "button:has-text('" + text + "')";
                        page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(2000));
                        page.click(selector, new Page.ClickOptions().setTimeout(2000));
                        log.info("Clicked consent button: {}", text);
                        page.waitForTimeout(1500);
                    } catch (Exception e) {
                        // Continue to next option
                    }
                }
                
                // Try common ID selectors
                String[] cssSelectors = {
                    "#onetrust-accept-btn-handler",
                    "#onetrust-reject-all-handler",
                    "#cookiesettingsbtn",
                    ".cookie-banner button",
                    ".onetrust-pc-dark-scope button",
                    "button[class*='accept']",
                    "button[id*='accept']"
                };
                
                for (String selector : cssSelectors) {
                    try {
                        page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(2000));
                        page.click(selector, new Page.ClickOptions().setTimeout(2000));
                        log.info("Clicked consent via selector: {}", selector);
                        page.waitForTimeout(1500);
                    } catch (Exception e) {
                        continue;
                    }
                }
            } catch (Exception e) {
                log.debug("No consent dialog handled: {}", e.getMessage());
            }
            
            // Handle welcome/upsell popup dialogs (Continue buttons, X buttons)
            try {
                // First try welcome dialog "Continue" buttons (free tier option)
                String[] continueTexts = {"Continue to Basic", "Continue", "Maybe later", "Not now", "Skip", "Close", "×"};
                for (String text : continueTexts) {
                    try {
                        String selector = "button:has-text('" + text + "')";
                        page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(2000));
                        page.click(selector, new Page.ClickOptions().setTimeout(2000));
                        log.info("Clicked welcome dialog button: {}", text);
                        page.waitForTimeout(1500);
                    } catch (Exception e) {
                        continue;
                    }
                }
                
                // Try close buttons
                String[] closeSelectors = {
                    "button[class*='close']",
                    "button[aria-label*='Close']",
                    "[aria-label='Close']",
                    "button[aria-label='Dismiss']",
                    "[data-testid='close-icon']"
                };
                
                for (String selector : closeSelectors) {
                    try {
                        page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(2000));
                        page.click(selector, new Page.ClickOptions().setTimeout(2000));
                        log.info("Closed popup with: {}", selector);
                        page.waitForTimeout(1000);
                    } catch (Exception e) {
                        continue;
                    }
                }
            } catch (Exception e) {
                log.debug("No welcome popup to close: {}", e.getMessage());
            }
            
            // Take a screenshot right after handling consent, before waiting for page elements
            saveScreenshot(page, "after_consent");
            
            // Additional wait for flight list to populate
            page.waitForTimeout(3000);
            
            // Take screenshot before parsing to see the structure
            saveScreenshot(page, "before_parse");
            
            // Parse flight elements - try multiple selectors to find flights
            List<ElementHandle> flightElements = new ArrayList<>();
            
            // Try different selectors for flight rows
            String[] flightSelectors = {
                "div[data-testid='arrivals-card']",
                "div[class*='flight']",
                "div[class*='arrival']",
                "li[class*='flight']",
                ".flight-row",
                "[class*='flight-card']"
            };
            
            for (String selector : flightSelectors) {
                try {
                    List<ElementHandle> elements = page.querySelectorAll(selector);
                    if (!elements.isEmpty()) {
                        log.info("Found {} flight elements with selector: {}", elements.size(), selector);
                        flightElements = elements;
                        break;
                    }
                } catch (Exception e) {
                    continue;
                }
            }
            
            // If no flights found with specific selectors, try to get all visible text
            if (flightElements.isEmpty()) {
                log.warn("No flight elements found with specific selectors, attempting generic extraction");
            }
            
            LocalDate today = LocalDate.now();
            int parsedCount = 0;
            
            for (ElementHandle flight : flightElements) {
                try {
                    Flight parsed = parseFlightElement(flight, today);
                    if (parsed != null) {
                        arrivals.add(parsed);
                        parsedCount++;
                        log.debug("Parsed flight: {} from {}", parsed.flightNumber(), parsed.origin());
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse flight element: {}", e.getMessage());
                }
            }
            
            log.info("Successfully parsed {} flights", parsedCount);
            
            // Take final screenshot after parsing
            saveScreenshot(page, "final");
            
            return arrivals;
        } catch (Exception e) {
            log.error("Scraping failed, saving screenshot for debugging: {}", e.getMessage(), e);
            // Try to save screenshot even on error
            if (page != null) {
                try {
                    saveScreenshot(page, "error");
                } catch (Exception screenshotException) {
                    log.error("Failed to save error screenshot: {}", screenshotException.getMessage());
                }
            }
            throw e;
        } finally {
            if (page != null) {
                page.close();
            }
        }
    }
    
    private Flight parseFlightElement(ElementHandle flight, LocalDate baseDate) {
        try {
            // Try multiple approaches to extract flight data
            String flightNumber = null;
            String aircraft = "UNKNOWN";
            String origin = "UNKNOWN";
            String timeStr = null;
            
            // Try to get all text content from the flight element
            String fullText = flight.innerText();
            if (fullText != null && !fullText.isEmpty()) {
                log.trace("Flight element text: {}", fullText.replace("\n", " | "));
                
                // Parse flight number - usually at the start (e.g., BAW123, IB3831)
                String[] lines = fullText.split("\n");
                for (String line : lines) {
                    line = line.trim();
                    // Match flight number pattern (2-3 letters + 1-4 digits)
                    if (line.matches("^[A-Z]{2,3}\\d{1,4}.*")) {
                        flightNumber = line.replaceAll("\\s+", " ").split(" ")[0];
                        break;
                    }
                }
                
                // Try to find aircraft model - look for common aircraft codes
                for (String line : lines) {
                    line = line.trim();
                    // Match common aircraft models (A320, B737, E195, etc.)
                    if (line.matches("^(A3[0-9]{2}|B7[0-9]{2}|E-[0-9]{3}|CRJ|ATR|ARJ|BAE|DA40|DA50|PC-).*")) {
                        aircraft = line.split("\\s+")[0];
                        break;
                    }
                    // Also try parentheses format (A320)
                    if (line.contains("(") && line.contains(")")) {
                        int start = line.indexOf("(");
                        int end = line.indexOf(")");
                        String candidate = line.substring(start + 1, end).trim();
                        if (candidate.matches("^(A3[0-9]{2}|B7[0-9]{2}|E-[0-9]{3}|CRJ|ATR).*")) {
                            aircraft = candidate.split("\\s+")[0];
                            break;
                        }
                    }
                }
                
                // If still unknown, try more aggressive patterns
                if ("UNKNOWN".equals(aircraft)) {
                    for (String line : lines) {
                        line = line.trim();
                        // Match anything that looks like an aircraft model
                        if (line.matches(".*(A320|B737|E195|CRJ9|ATR7|ARJ1).*")) {
                            if (line.contains("A320")) aircraft = "A320";
                            else if (line.contains("B737")) aircraft = "B737";
                            else if (line.contains("E195")) aircraft = "E195";
                            else if (line.contains("CRJ")) aircraft = "CRJ";
                            else if (line.contains("ATR")) aircraft = "ATR";
                            break;
                        }
                    }
                }
                
                // Try to find origin airport (usually 3-4 letter code)
                for (String line : lines) {
                    line = line.trim();
                    if (line.matches("^[A-Z]{3,4}$") && !line.equals(flightNumber)) {
                        origin = line;
                        break;
                    }
                }
            }
            
            // If no flight number found, try selectors
            if (flightNumber == null || flightNumber.isEmpty()) {
                ElementHandle callsignElement = flight.querySelector("[data-testid='aircraft-callsign']");
                if (callsignElement != null) {
                    flightNumber = callsignElement.innerText().trim();
                }
            }
            
            // Try to get aircraft model
            if ("UNKNOWN".equals(aircraft)) {
                ElementHandle aircraftElement = flight.querySelector("[data-testid='aircraft-model']");
                if (aircraftElement == null) {
                    aircraftElement = flight.querySelector("span[class*='model']");
                }
                if (aircraftElement == null) {
                    aircraftElement = flight.querySelector("[class*='type']");
                }
                if (aircraftElement != null) {
                    aircraft = aircraftElement.innerText().trim();
                }
            }
            
            // Try to get origin
            if ("UNKNOWN".equals(origin)) {
                ElementHandle originElement = flight.querySelector("[data-testid='origin-airport']");
                if (originElement == null) {
                    originElement = flight.querySelector("span[class*='origin']");
                }
                if (originElement != null) {
                    origin = originElement.innerText().trim();
                }
            }
            
            // Try to get scheduled time
            ElementHandle timeElement = flight.querySelector("[data-testid='scheduled-time']");
            if (timeElement == null) {
                timeElement = flight.querySelector("[class*='time']");
            }
            if (timeElement != null) {
                timeStr = timeElement.innerText().replaceAll("\\s+", " ").trim();
            }
            
            if (flightNumber == null || flightNumber.isEmpty()) {
                log.debug("No flight number found, skipping");
                return null;
            }
            
            LocalDateTime scheduledTime = parseTime(timeStr, baseDate);
            
            return new Flight(flightNumber, origin, aircraft, scheduledTime);
        } catch (Exception e) {
            log.debug("Failed to parse flight element: {}", e.getMessage());
            return null;
        }
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
    
    private void saveScreenshot(Page page, String step) {
        try {
            String filename = "screenshots/" + step + ".png";
            java.io.File screenshotDir = new java.io.File("screenshots");
            if (!screenshotDir.exists()) {
                screenshotDir.mkdirs();
            }
            page.screenshot(new Page.ScreenshotOptions().setPath(java.nio.file.Paths.get(filename)));
            log.info("Screenshot saved: {}", filename);
        } catch (Exception e) {
            log.error("Failed to save screenshot: {}", e.getMessage(), e);
        }
    }
}
