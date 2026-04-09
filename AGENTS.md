# FlightTracker Project

## Overview

FlightTracker is a Java framework for monitoring aircraft. It supports two modes:

1. **Web Scraping Mode** - Scrapes flight data from FlightRadar24
2. **ADS-B Mode** - Receives real-time data from a local ADS-B receiver (dump1090-fa)

The ADS-B mode is recommended if you have a receiver, as it provides real-time data without rate limits or dependencies on external websites.

## Technology Stack

- **Java**: 21
- **Build Tool**: Maven
- **Web Scraping**: Microsoft Playwright 1.45.0
- **ADS-B**: TCP socket client for dump1090 SBS format
- **Database**: SQLite 3.45.2.0 / MySQL 8.0.33 with HikariCP
- **JSON Processing**: Jackson 2.17.1
- **HTTP Client**: Apache HttpClient 5.4.4
- **Logging**: SLF4J 2.0.17
- **Code Generation**: Lombok 1.18.38

## Project Structure

```
src/main/java/com/richi/
├── FlightTrackerApp.java           # Web scraping mode
├── ADSBFlightTracker.java          # ADS-B receiver mode
├── config/
│   └── ConfigManager.java          # Configuration management
├── model/
│   └── Flight.java                 # Flight data model
├── repository/
│   ├── FlightRepository.java       # Database interface
│   └── SqlFlightRepository.java    # SQL implementation
├── service/
│   ├── FlightScraperService.java   # Scraper interface
│   └── PlaywrightFlightScraper.java # FlightRadar24 scraper
├── notification/
│   ├── FlightNotifier.java         # Notifier interface
│   └── TelegramFlightNotifier.java # Telegram implementation
├── analyzer/
│   ├── AircraftAnalyzerService.java # Aircraft analysis
│   ├── LocalAircraftAnalyzer.java   # Local widebody detection
│   └── DeepSeekAircraftAnalyzer.java # AI fallback
└── adsb/                           # NEW: ADS-B support
    ├── ADSBDataSource.java         # ADS-B data source interface
    ├── ADSBListener.java           # Event listener interface
    ├── ADSBStats.java              # Statistics record
    ├── SBSMessage.java             # SBS format parser
    └── Dump1090DataSource.java     # dump1090 TCP client
```

## Two Operating Modes

### Mode 1: Web Scraping (FlightRadar24)

Polls FlightRadar24 website every N minutes. Good for specific airport monitoring but:
- Rate limited
- Can break if website changes
- Depends on external service

**Main class**: `com.richi.FlightTrackerApp`

```bash
mvn exec:java -Dexec.mainClass="com.richi.FlightTrackerApp"
```

### Mode 2: ADS-B Receiver (Recommended)

Connects to your local dump1090-fa instance via TCP socket (port 30003). Provides:
- Real-time data (no polling)
- All aircraft in receiver range
- No rate limits
- Works offline

**Main class**: `com.richi.ADSBFlightTracker`

```bash
mvn exec:java -Dexec.mainClass="com.richi.ADSBFlightTracker"
```

## Configuration

Configuration is managed via `application.properties` and environment variables.

### Example: ADS-B Mode

```properties
# Enable ADS-B mode
adsb.enabled=true

# dump1090-fa connection (default: localhost:30003)
adsb.dump1090.host=localhost
adsb.dump1090.port=30003

# Aircraft timeout (remove if not seen for 60 seconds)
adsb.aircraft.timeout.seconds=60

# Reconnect delay on connection failure
adsb.reconnect.delay.seconds=5

# How often to save to database
adsb.save.interval.minutes=5

# Notification cooldown (don't notify same flight for 4 hours)
adsb.notification.cooldown.hours=4

# Notifications
telegram.enabled=true
telegram.bot.token=your_bot_token
telegram.chat.id=your_chat_id

# Database
db.type=sqlite
db.sqlite.url=jdbc:sqlite:flights.db
```

### Environment Variables

Override any config value via environment variables:

```bash
export ADSB_ENABLED=true
export ADSB_DUMP1090_HOST=YOUR_DUMP1090_HOST
export TELEGRAM_BOT_TOKEN=xxx
export TELEGRAM_CHAT_ID=xxx
```

## Setting up dump1090-fa

### If you already have dump1090-fa running:

The SBS output is usually on port 30003. Verify it's working:

```bash
# Test connection
telnet localhost 30003

# Or use netcat
nc localhost 30003
```

You should see data like:
```
MSG,3,1,1,4CA1FA,1,2024/01/15,14:32:45,2024/01/15,14:32:45,BAW123,38000,450,45.5,12.3,,,,,,,0
```

### If you need to enable SBS output:

Edit `/etc/default/dump1090-fa`:

```bash
# Add or uncomment:
NET_SBS_OUTPUT_PORT=30003
```

Then restart:
```bash
sudo systemctl restart dump1090-fa
```

### Installing dump1090-fa (if needed):

```bash
# On Linux host with an ADS-B feeder
sudo apt-get install dump1090-fa

# Or compile from source
git clone https://github.com/flightaware/dump1090.git
cd dump1090
make
```

## Build Commands

```bash
# Compile
mvn compile

# Package
mvn package

# Run ADS-B mode
mvn exec:java -Dexec.mainClass="com.richi.ADSBFlightTracker"

# Run Web scraping mode
mvn exec:java -Dexec.mainClass="com.richi.FlightTrackerApp"

# Run fat JAR
java -jar target/FlightScraper-0.0.1-SNAPSHOT-jar-with-dependencies.jar
```

## Key Features

### ADS-B Mode Features

| Feature | Description |
|---------|-------------|
| **Real-time** | Instant aircraft detection |
| **Auto-reconnect** | Handles connection drops |
| **Deduplication** | Won't notify same flight twice within cooldown |
| **Aircraft timeout** | Removes stale aircraft from tracking |
| **Statistics** | Message rate, aircraft count |
| **Event listeners** | Callbacks for detect/update/loss events |

### Message Format (SBS/BaseStation)

dump1090 outputs CSV format:
```
MSG,<type>,<sess>,<aircraft>,<hex>,<flight>,<date_gen>,<time_gen>,<date_log>,<time_log>,<callsign>,<alt>,<speed>,<heading>,<lat>,<lon>,<vr>,<squawk>,...
```

Key fields:
- **hex**: ICAO 24-bit address (unique aircraft ID)
- **callsign**: Flight number (e.g., "BAW123")
- **alt**: Altitude in feet
- **speed**: Ground speed in knots
- **lat/lon**: Position
- **squawk**: Transponder code

## Database Schema

```sql
CREATE TABLE flights (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    flight_number VARCHAR(10) NOT NULL,
    origin VARCHAR(4) NOT NULL,
    aircraft VARCHAR(50) NOT NULL,
    scheduled_time DATETIME NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(flight_number, scheduled_time)
);
```

## Running the Application

### Quick Start (ADS-B Mode)

```bash
# 1. Ensure dump1090 is running and accessible
nc -zv localhost 30003

# 2. Run the tracker
mvn exec:java -Dexec.mainClass="com.richi.ADSBFlightTracker"
```

### With MySQL and Telegram

```bash
export DB_TYPE=mysql
export DB_MYSQL_USER=webadmin
export DB_MYSQL_PASSWORD=yourpassword
export TELEGRAM_ENABLED=true
export TELEGRAM_BOT_TOKEN=your_bot_token
export TELEGRAM_CHAT_ID=your_chat_id

mvn exec:java -Dexec.mainClass="com.richi.ADSBFlightTracker"
```

### Remote dump1090

```bash
export ADSB_DUMP1090_HOST=YOUR_DUMP1090_HOST
export ADSB_DUMP1090_PORT=30003

mvn exec:java -Dexec.mainClass="com.richi.ADSBFlightTracker"
```

## Troubleshooting

### "Cannot connect to dump1090"
- Verify dump1090 is running: `sudo systemctl status dump1090-fa`
- Check port 30003 is open: `netstat -tlnp | grep 30003`
- Test with telnet: `telnet localhost 30003`

### "No aircraft detected"
- Check antenna connection
- Verify receiver is working via web interface (usually http://localhost:8080)
- Check range: aircraft must be within ~100-200 miles

### "Telegram not working"
- Verify bot token and chat ID
- Test manually: `curl "https://api.telegram.org/bot<TOKEN>/getMe"`

### ADS-B vs Web Scraper

| Aspect | ADS-B Mode | Web Scraper |
|--------|-----------|-------------|
| **Data source** | Local receiver | FlightRadar24 |
| **Latency** | Real-time | 1-60 min (polling) |
| **Reliability** | High (local) | Medium (website) |
| **Coverage** | Your receiver range | Global |
| **Cost** | Free (after hardware) | Free |
| **Setup** | Requires receiver | Just software |

## Future Enhancements

- [ ] Airport proximity detection (using aircraft position)
- [ ] Landing/takeoff detection
- [ ] Flight path tracking and visualization
- [ ] Aircraft database lookup (registration, type from ICAO hex)
- [ ] ML-based aircraft type identification from flight characteristics
- [ ] Web dashboard for live aircraft map
