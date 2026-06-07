package com.richi.adsb;

import com.richi.config.ConfigManager;
import com.richi.geo.AirportCoords;
import com.richi.model.Flight;
import com.richi.service.AircraftEnrichmentService;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ADS-B data source that connects to dump1090-fa via TCP socket (SBS format).
 * 
 * dump1090-fa typically exposes SBS data on port 30003.
 * This class maintains a connection and builds a real-time picture of aircraft.
 */
@Slf4j
public class Dump1090DataSource implements ADSBDataSource, AutoCloseable {
    
    private final String host;
    private final int port;
    private final Duration aircraftTimeout;
    private final Duration reconnectDelay;
    
    private Socket socket;
    private BufferedReader reader;
    private Thread readerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    
    // Aircraft tracking state
    private final Map<String, AircraftState> aircraftMap = new ConcurrentHashMap<>();
    private final List<ADSBListener> listeners = new CopyOnWriteArrayList<>();
    
    // Statistics
    private final AtomicInteger messagesReceived = new AtomicInteger(0);
    private final AtomicLong lastMessageTime = new AtomicLong(0);
    private final AtomicInteger messagesInLastSecond = new AtomicInteger(0);
    private volatile String connectionInfo = "Not connected";
    
    // Cleanup task for stale aircraft
    private ScheduledExecutorService cleanupExecutor;
    private final ExecutorService enrichmentExecutor;
    
    // Aircraft enrichment
    private final AircraftEnrichmentService enrichment;
    
    public Dump1090DataSource() {
        this(ConfigManager.getInstance());
    }
    
    public Dump1090DataSource(ConfigManager config) {
        this(
            config.getString("adsb.dump1090.host", "localhost"),
            config.getInt("adsb.dump1090.port", 30003),
            config.getInt("adsb.aircraft.timeout.seconds", 60),
            config.getInt("adsb.reconnect.delay.seconds", 5)
        );
    }
    
    public Dump1090DataSource(String host, int port, int timeoutSeconds, int reconnectSeconds) {
        this.host = host;
        this.port = port;
        this.aircraftTimeout = Duration.ofSeconds(timeoutSeconds);
        this.reconnectDelay = Duration.ofSeconds(reconnectSeconds);
        this.enrichmentExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "adsb-enricher");
            t.setDaemon(true);
            return t;
        });
        this.enrichment = createEnrichmentService();
        log.info("Dump1090DataSource configured for {}:{}", host, port);
    }
    
    private static AircraftEnrichmentService createEnrichmentService() {
        try {
            return new AircraftEnrichmentService();
        } catch (Exception e) {
            log.warn("Failed to initialize enrichment service: {}", e.getMessage());
            return null;
        }
    }
    
    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting Dump1090DataSource...");
            startCleanupTask();
            connect();
        }
    }
    
    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping Dump1090DataSource...");
            disconnect();
            stopCleanupTask();
            stopEnrichmentExecutor();
            aircraftMap.clear();
        }
    }
    
    @Override
    public boolean isConnected() {
        return connected.get() && socket != null && socket.isConnected() && !socket.isClosed();
    }
    
    private void connect() {
        while (running.get() && !isConnected()) {
            try {
                log.info("Connecting to dump1090 at {}:{}...", host, port);
                socket = new Socket(host, port);
                socket.setSoTimeout(30000);  // 30 second read timeout
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                connected.set(true);
                connectionInfo = host + ":" + port;
                log.info("Connected to dump1090 successfully");
                
                // Start reading in current thread (or spawn new thread)
                readerThread = new Thread(this::readLoop, "dump1090-reader");
                readerThread.setDaemon(true);
                readerThread.start();
                
            } catch (IOException e) {
                log.warn("Failed to connect to dump1090: {}", e.getMessage());
                connectionInfo = "Error: " + e.getMessage();
                connected.set(false);
                
                if (running.get()) {
                    sleep(reconnectDelay.toMillis());
                }
            }
        }
    }
    
    private void disconnect() {
        connected.set(false);
        
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        
        try {
            if (reader != null) {
                reader.close();
                reader = null;
            }
        } catch (IOException e) {
            log.debug("Error closing reader: {}", e.getMessage());
        }
        
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
                socket = null;
            }
        } catch (IOException e) {
            log.debug("Error closing socket: {}", e.getMessage());
        }
        
        log.info("Disconnected from dump1090");
    }
    
    private void readLoop() {
        try {
            String line;
            while (running.get() && connected.get() && (line = reader.readLine()) != null) {
                processMessage(line);
                messagesReceived.incrementAndGet();
                messagesInLastSecond.incrementAndGet();
                lastMessageTime.set(System.currentTimeMillis());
            }
        } catch (SocketException e) {
            log.warn("Socket error: {}", e.getMessage());
        } catch (IOException e) {
            if (running.get()) {
                log.error("Read error: {}", e.getMessage());
            }
        } finally {
            log.info("Reader thread stopped");
            connected.set(false);
            
            // Auto-reconnect if still running
            if (running.get()) {
                log.info("Will attempt to reconnect...");
                sleep(reconnectDelay.toMillis());
                connect();
            }
        }
    }
    
    private void processMessage(String line) {
        SBSMessage msg = SBSMessage.parse(line);
        if (msg == null) return;
        
        // Use hex_ident as unique key
        String key = msg.hexIdent();
        boolean isNew = !aircraftMap.containsKey(key);
        
        AircraftState state = aircraftMap.computeIfAbsent(key, k -> new AircraftState(msg.hexIdent()));
        
        boolean hadCallsign = state.hasCallsign();
        boolean updated = state.update(msg);
        
        if (isNew || (!hadCallsign && state.hasCallsign())) {
            // New aircraft OR just got its callsign for the first time - enrich type info
            enrichAircraft(state);
            notifyAircraftDetected(state);
        } else if (updated) {
            notifyAircraftUpdated(state);
        }
    }
    
    private void enrichAircraft(AircraftState state) {
        if (enrichment == null) return;
        enrichmentExecutor.submit(() -> {
            try {
                var info = enrichment.lookup(state.hexIdent);
                if (info.isKnown()) {
                    state.setAircraftInfo(info);
                    // Re-notify with enriched data if it's now a known type
                    notifyAircraftUpdated(state);
                }
            } catch (Exception e) {
                log.debug("Enrichment failed for {}: {}", state.hexIdent, e.getMessage());
            }
        });
    }
    
    private void startCleanupTask() {
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "adsb-cleanup");
            t.setDaemon(true);
            return t;
        });
        
        // Cleanup stale aircraft every 10 seconds
        cleanupExecutor.scheduleAtFixedRate(this::cleanupStaleAircraft, 10, 10, TimeUnit.SECONDS);
        
        // Update message rate every second
        cleanupExecutor.scheduleAtFixedRate(() -> {
            // Message rate is tracked but we just reset the counter here
        }, 1, 1, TimeUnit.SECONDS);
    }
    
    private void stopCleanupTask() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    private void stopEnrichmentExecutor() {
        if (enrichmentExecutor != null) {
            enrichmentExecutor.shutdown();
            try {
                if (!enrichmentExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    enrichmentExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                enrichmentExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (enrichment != null) {
            enrichment.close();
        }
    }
    
    private void cleanupStaleAircraft() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, AircraftState>> it = aircraftMap.entrySet().iterator();
        
        while (it.hasNext()) {
            AircraftState state = it.next().getValue();
            if (Duration.between(state.getLastSeen(), now).compareTo(aircraftTimeout) > 0) {
                it.remove();
                notifyAircraftLost(state);
            }
        }
    }
    
    @Override
    public List<Flight> getCurrentFlights() {
        return aircraftMap.values().stream()
                .filter(AircraftState::hasCallsign)
                .map(AircraftState::toFlight)
                .filter(Objects::nonNull)
                .toList();
    }
    
    @Override
    public List<Flight> getFlightsNearAirport(String airportCode, double radiusNm) {
        AirportCoords coords = AirportCoords.SPANISH_AIRPORTS.get(airportCode.toUpperCase());
        if (coords == null) {
            log.warn("Airport {} not in table — returning all flights with positions", airportCode);
            return getCurrentFlights().stream()
                    .filter(f -> f.latitude() != null && f.longitude() != null)
                    .toList();
        }
        double aLat = coords.lat();
        double aLon = coords.lon();
        return getCurrentFlights().stream()
                .filter(f -> f.latitude() != null && f.longitude() != null)
                .filter(f -> AirportCoords.haversineNm(aLat, aLon, f.latitude(), f.longitude()) <= radiusNm)
                .toList();
    }
    
    @Override
    public Flight getFlightByCallsign(String callsign) {
        return aircraftMap.values().stream()
                .filter(state -> callsign.equalsIgnoreCase(state.getCallsign()))
                .map(AircraftState::toFlight)
                .findFirst()
                .orElse(null);
    }
    
    @Override
    public ADSBStats getStats() {
        return new ADSBStats(
                aircraftMap.size(),
                messagesReceived.get(),
                messagesInLastSecond.getAndSet(0),
                lastMessageTime.get() > 0 ? Instant.ofEpochMilli(lastMessageTime.get()) : null,
                isConnected(),
                connectionInfo
        );
    }
    
    @Override
    public void addListener(ADSBListener listener) {
        listeners.add(listener);
    }
    
    @Override
    public void removeListener(ADSBListener listener) {
        listeners.remove(listener);
    }
    
    private void notifyAircraftDetected(AircraftState state) {
        Flight flight = state.toFlight();
        if (flight != null) {
            listeners.forEach(l -> l.onAircraftDetected(flight));
        }
    }
    
    private void notifyAircraftUpdated(AircraftState state) {
        Flight flight = state.toFlight();
        if (flight != null) {
            listeners.forEach(l -> l.onAircraftUpdated(flight));
        }
    }
    
    private void notifyAircraftLost(AircraftState state) {
        Flight flight = state.toFlight();
        if (flight != null) {
            listeners.forEach(l -> l.onAircraftLost(flight));
        }
    }
    
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    @Override
    public void close() {
        stop();
    }
    
    /**
     * Internal class to track the state of an aircraft from multiple messages.
     */
    private static class AircraftState {
        final String hexIdent;
        private volatile String callsign;
        private volatile Integer altitude;
        private volatile Integer speed;
        private volatile Integer heading;
        private volatile Double latitude;
        private volatile Double longitude;
        private volatile String squawk;        // Most recent transponder code from SBS
        private volatile String aircraftType;  // From enrichment service
        private volatile String aircraftDesc;
        private volatile String registration;
        private volatile String operator;
        private volatile Instant firstSeen;
        private volatile Instant lastSeen;
        
        AircraftState(String hexIdent) {
            this.hexIdent = hexIdent;
            this.firstSeen = Instant.now();
            this.lastSeen = Instant.now();
        }
        
        synchronized boolean update(SBSMessage msg) {
            boolean changed = false;
            
            if (msg.callsign() != null && !msg.callsign().isEmpty() && !msg.callsign().equals(callsign)) {
                callsign = msg.callsign();
                changed = true;
            }
            if (msg.altitude() != null && !msg.altitude().equals(altitude)) {
                altitude = msg.altitude();
                changed = true;
            }
            if (msg.speed() != null && !msg.speed().equals(speed)) {
                speed = msg.speed();
                changed = true;
            }
            if (msg.heading() != null && !msg.heading().equals(heading)) {
                heading = msg.heading();
                changed = true;
            }
            if (msg.latitude() != null && !msg.latitude().equals(latitude)) {
                latitude = msg.latitude();
                changed = true;
            }
            if (msg.longitude() != null && !msg.longitude().equals(longitude)) {
                longitude = msg.longitude();
                changed = true;
            }
            if (msg.squawk() != null && !msg.squawk().equals(squawk)) {
                squawk = msg.squawk();
                changed = true;
            }

            lastSeen = Instant.now();
            return changed;
        }
        
        boolean hasCallsign() {
            return callsign != null && !callsign.isEmpty();
        }
        
        String getCallsign() {
            return callsign;
        }
        
        Instant getLastSeen() {
            return lastSeen;
        }
        
        Flight toFlight() {
            if (callsign == null) return null;

            String acType = aircraftType != null && !aircraftType.isEmpty() ? aircraftType :
                    (aircraftDesc != null && !aircraftDesc.isEmpty() ? aircraftDesc : "UNKNOWN");

            LocalDateTime scheduledTime = LocalDateTime.now();
            return new Flight(callsign, hexIdent, acType, scheduledTime,
                    altitude, speed, squawk, hexIdent, operator, latitude, longitude);
        }
        
        void setAircraftInfo(AircraftEnrichmentService.AircraftInfo info) {
            if (info != null) {
                if (info.icaoType() != null && !info.icaoType().isEmpty()) {
                    this.aircraftType = info.icaoType();
                }
                if (info.description() != null && !info.description().isEmpty()) {
                    this.aircraftDesc = info.description();
                }
                if (info.registration() != null && !info.registration().isEmpty()) {
                    this.registration = info.registration();
                }
                if (info.operator() != null && !info.operator().isEmpty()) {
                    this.operator = info.operator();
                }
            }
        }
        
        @Override
        public String toString() {
            return String.format("Aircraft[%s, %s, alt=%s, pos=%s,%s]", 
                    hexIdent, callsign, altitude, latitude, longitude);
        }
    }
}
