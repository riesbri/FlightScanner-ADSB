package com.flightscanner.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

@Slf4j
@Getter
public class ConfigManager {
    private static final String CONFIG_FILE = "application.properties";
    private static ConfigManager instance;
    
    private final Properties properties;
    
    // Airport settings
    private final String airportCode;
    private final int scrapeIntervalMinutes;
    
    // Scraper settings
    private final int maxRetries;
    private final int retryDelaySeconds;
    
    // Database settings
    private final String dbType;
    private final String dbUrl;
    private final String dbUser;
    private final String dbPassword;
    private final int dbPoolMaxSize;
    private final int dbPoolMinSize;
    private final long dbConnectionTimeoutMs;
    
    // Discord settings
    private final boolean discordEnabled;
    private final String discordWebhookUrl;
    private final boolean discordNotifyAll;
    private final NotifyLevel notifyLevel;
    private final boolean startupTestMessage;
    
    // Web UI settings
    private final boolean webuiEnabled;
    private final String webuiHost;
    private final int webuiPort;

    // ADS-B settings
    private final boolean adsbEnabled;
    private final String adsbSourceType;
    private final String adsbHost;
    private final int adsbPort;
    private final int adsbAircraftTimeoutSeconds;
    private final int adsbReconnectDelaySeconds;
    private final int adsbSaveIntervalMinutes;
    private final int adsbNotificationCooldownHours;
    
    private ConfigManager() {
        this.properties = loadProperties();
        
        // Airport
        this.airportCode = getString("airport.code", "VLC");
        this.scrapeIntervalMinutes = getInt("scraper.interval.minutes", 60);
        
        // Scraper
        this.maxRetries = getInt("scraper.max.retries", 3);
        this.retryDelaySeconds = getInt("scraper.retry.delay.seconds", 5);
        
        // Database
        this.dbType = getString("db.type", "sqlite");
        if ("sqlite".equalsIgnoreCase(dbType)) {
            this.dbUrl = getString("db.sqlite.url", "jdbc:sqlite:flights.db");
            this.dbUser = null;
            this.dbPassword = null;
        } else {
            this.dbUrl = getString("db.mysql.url", "");
            this.dbUser = getString("db.mysql.user", "");
            this.dbPassword = getString("db.mysql.password", "");
        }
        this.dbPoolMaxSize = getInt("db.pool.size.max", 10);
        this.dbPoolMinSize = getInt("db.pool.size.min", 2);
        this.dbConnectionTimeoutMs = getLong("db.connection.timeout.ms", 30000);
        
        // Discord
        this.discordEnabled = getBoolean("discord.enabled", false);
        this.discordWebhookUrl = getString("discord.webhook.url", "");
        this.discordNotifyAll = getBoolean("discord.notify.all", false);
        // Warn once at startup if the deprecated key is present in the file
        if (properties.containsKey("discord.notify.all")) {
            log.warn("discord.notify.all is deprecated; use discord.notify.level=all or discord.notify.level=noteworthy");
        }
        this.notifyLevel = NotifyLevel.parse(getString("discord.notify.level", "noteworthy"));
        this.startupTestMessage = getBoolean("discord.startup.test.message", false);
        
        // Web UI
        this.webuiEnabled = getBoolean("webui.enabled", true);
        this.webuiHost = getString("webui.host", "0.0.0.0");
        this.webuiPort = getInt("webui.port", 3006);

        // ADS-B
        this.adsbEnabled = getBoolean("adsb.enabled", false);
        this.adsbSourceType = getString("adsb.source.type", "dump1090");
        this.adsbHost = getString("adsb.dump1090.host", "localhost");
        this.adsbPort = getInt("adsb.dump1090.port", 30003);
        this.adsbAircraftTimeoutSeconds = getInt("adsb.aircraft.timeout.seconds", 60);
        this.adsbReconnectDelaySeconds = getInt("adsb.reconnect.delay.seconds", 5);
        this.adsbSaveIntervalMinutes = getInt("adsb.save.interval.minutes", 5);
        this.adsbNotificationCooldownHours = getInt("adsb.notification.cooldown.hours", 4);
        
        log.info("Configuration loaded successfully");
    }
    
    public static synchronized ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }
    
    private Properties loadProperties() {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (is != null) {
                props.load(is);
                log.info("Loaded configuration from {}", CONFIG_FILE);
            } else {
                log.warn("Configuration file {} not found, using defaults", CONFIG_FILE);
            }
        } catch (IOException e) {
            log.error("Failed to load configuration: {}", e.getMessage());
        }
        return props;
    }
    
    public String getString(String key, String defaultValue) {
        String value = System.getenv(key.toUpperCase().replace(".", "_"));
        if (value != null && !value.isEmpty()) {
            return value;
        }
        return properties.getProperty(key, defaultValue);
    }
    
    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(getString(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    public long getLong(String key, long defaultValue) {
        try {
            return Long.parseLong(getString(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = getString(key, String.valueOf(defaultValue));
        return Boolean.parseBoolean(value);
    }
    
    public String getAirportCode() {
        return airportCode;
    }
    
    public int getScrapeIntervalMinutes() {
        return scrapeIntervalMinutes;
    }
    
    public int getMaxRetries() {
        return maxRetries;
    }
    
    public int getRetryDelaySeconds() {
        return retryDelaySeconds;
    }
    
    public boolean isDiscordEnabled() {
        return discordEnabled;
    }
    
    public String getDiscordWebhookUrl() {
        return discordWebhookUrl;
    }
    
    public boolean isDiscordNotifyAll() {
        return discordNotifyAll;
    }

    public NotifyLevel getNotifyLevel() {
        return notifyLevel;
    }

    public boolean isStartupTestMessage() {
        return startupTestMessage;
    }
    
    public boolean isWebuiEnabled() { return webuiEnabled; }
    public String getWebuiHost()    { return webuiHost; }
    public int getWebuiPort()       { return webuiPort; }

    public boolean isAdsbEnabled() {
        return adsbEnabled;
    }
    
    public String getAdsbSourceType() {
        return adsbSourceType;
    }
    
    public String getAdsbHost() {
        return adsbHost;
    }
    
    public int getAdsbPort() {
        return adsbPort;
    }
    
    public int getAdsbAircraftTimeoutSeconds() {
        return adsbAircraftTimeoutSeconds;
    }
    
    public int getAdsbReconnectDelaySeconds() {
        return adsbReconnectDelaySeconds;
    }
    
    public int getAdsbSaveIntervalMinutes() {
        return adsbSaveIntervalMinutes;
    }
    
    public int getAdsbNotificationCooldownHours() {
        return adsbNotificationCooldownHours;
    }
    
    public String getDbType() {
        return dbType;
    }
    
    public String getDbUrl() {
        return dbUrl;
    }
    
    public String getDbUser() {
        return dbUser;
    }
    
    public String getDbPassword() {
        return dbPassword;
    }
    
    public int getDbPoolMaxSize() {
        return dbPoolMaxSize;
    }
    
    public int getDbPoolMinSize() {
        return dbPoolMinSize;
    }
    
    public long getDbConnectionTimeoutMs() {
        return dbConnectionTimeoutMs;
    }
    
    public void validate() throws IllegalStateException {
        if (airportCode == null || airportCode.isEmpty()) {
            throw new IllegalStateException("airport.code must be configured");
        }
        
        if ("mysql".equalsIgnoreCase(dbType)) {
            if (dbUser == null || dbUser.isEmpty()) {
                throw new IllegalStateException("db.mysql.user must be configured for MySQL");
            }
        }
        
        if (discordEnabled) {
            if (discordWebhookUrl.isEmpty() || discordWebhookUrl.startsWith("${")) {
                throw new IllegalStateException("discord.webhook.url must be set when discord.enabled=true");
            }
        }
        
        log.info("Configuration validation passed");
    }
    
    public static void reset() {
        instance = null;
    }
}
