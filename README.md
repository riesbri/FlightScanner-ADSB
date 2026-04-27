# FlightTracker

Java framework for monitoring aircraft with two operating modes:

## Modes

1. **ADS-B Mode** (Recommended) - Real-time tracking from local dump1090-fa receiver
2. **Web Scraper Mode** - Polls FlightRadar24 for specific airport flights

## Quick Start

### Build

```bash
mvn clean package -DskipTests
```

### Install Playwright Browsers (Web Scraper Mode Only)

```bash
java -cp "$(mvn dependency:build-classpath -q -DincludeScope=runtime -Dmdep.outputFile=/dev/stdout):target/classes" com.microsoft.playwright.CLI install chromium firefox
```

### Run ADS-B Mode

```bash
mvn exec:java -Dexec.mainClass="com.richi.ADSBFlightTracker"
```

### Run Web Scraper Mode

```bash
mvn exec:java -Dexec.mainClass="com.richi.FlightTrackerApp"
```

## Configuration

Edit `src/main/resources/application.properties`:

```properties
# Database
db.type=sqlite
db.sqlite.url=jdbc:sqlite:flights.db

# Telegram (optional)
telegram.enabled=true
telegram.bot.token=YOUR_TOKEN
telegram.chat.id=YOUR_CHAT_ID

# ADS-B Mode
adsb.enabled=true
adsb.dump1090.host=localhost
adsb.dump1090.port=30003

# Web Scraper Mode
scraper.interval.minutes=60
airport.code=VLC
```

## Environment Variables

```bash
export TELEGRAM_TOKEN=your_token
export TELEGRAM_CHAT_ID=your_chat_id
export ADSB_DUMP1090_HOST=YOUR_DUMP1090_HOST
```

## Requirements

- Java 21
- Maven
- For ADS-B: dump1090-fa running on port 30003
- For Web Scraper: Playwright browsers installed
