# FlightScanner — ADS-B Aircraft Tracker

Real-time aircraft monitoring from dump1090-fa SDR receiver with Discord notifications.

## Architecture

```
dump1090-fa (port 30003) → SBS stream → FlightScanner (Java 21)
                                            ├── Enrichment: adsb.lol API → aircraft_cache.db
                                            ├── Persistence: flights.db (SQLite)
                                            └── Notifications: Discord webhook (embeds)
```

No AI/LLM involved. Enrichment is purely adsb.lol API lookups, cached forever per ICAO hex.

## Quick Reference

| Command | What it does |
|---------|-------------|
| `systemctl --user status flightscanner` | Check if running |
| `systemctl --user restart flightscanner` | Restart after code changes |
| `journalctl --user -u flightscanner -f` | Live logs |
| `journalctl --user -u flightscanner -n 50` | Last 50 log lines |

## Configuration

All in `src/main/resources/application.properties`:

| Setting | Default | Notes |
|---------|---------|-------|
| `adsb.enabled` | `true` | Enable ADS-B mode |
| `adsb.dump1090.host` | `localhost` | dump1090-fa host |
| `adsb.dump1090.port` | `30003` | SBS stream port |
| `discord.enabled` | `true` | Enable Discord notifications |
| `discord.webhook.url` | env var | Set via `flightscanner.env` |
| `discord.notify.all` | `false` | `true` = all flights, `false` = widebodies only |
| `ai.analysis.enabled` | `false` | DeepSeek AI (not currently used) |

## Building & Deploying

```bash
cd ~/FlightScanner
# Build
JAVA_HOME=/opt/java-21 mvn package -DskipTests -q

# Set webhook URL in env file
echo 'DISCORD_WEBHOOK_URL="https://discord.com/api/webhooks/..."' > flightscanner.env

# Start
systemctl --user restart flightscanner
```

## Toggle All-Flight Mode (Testing)

```bash
# Enable all flights
sed -i 's/discord.notify.all=false/discord.notify.all=true/' \
  src/main/resources/application.properties
cd ~/FlightScanner && JAVA_HOME=/opt/java-21 mvn package -DskipTests -q
systemctl --user restart flightscanner

# Disable (widebodies only)
sed -i 's/discord.notify.all=true/discord.notify.all=false/' \
  src/main/resources/application.properties
cd ~/FlightScanner && JAVA_HOME=/opt/java-21 mvn package -DskipTests -q
systemctl --user restart flightscanner
```

## Discord Embed Categories

| Emoji | Category | Examples |
|-------|----------|----------|
| ✈️ | Commercial | A320, B738, E190, CRJ2 |
| 🛩️ | Business jet | GLEX, C56X, E55P, CL60 |
| 🛬 | Widebody | A330, B777, B787, A350, A380 |
| 🪖 | Military | A400, C130, F16, E3TF |

## Databases

Two SQLite databases in `~/FlightScanner/`:

### aircraft_cache.db — Enrichment cache

ICAO hex → type/description/operator from adsb.lol. Never expires — each hex is looked up once.

```bash
python3 -c "
import sqlite3
conn = sqlite3.connect('aircraft_cache.db')
rows = conn.execute('SELECT icao_hex, icao_type, operator FROM aircraft_types ORDER BY updated_at DESC LIMIT 20')
for r in rows: print(f'{r[0]} {r[1]:<6} {r[2]}')
"
```

### flights.db — Flight records

Persisted every 5 minutes from the real-time stream.

```bash
python3 -c "
import sqlite3
conn = sqlite3.connect('flights.db')
count = conn.execute('SELECT count(*) FROM flights').fetchone()[0]
print(f'Total flights: {count}')
rows = conn.execute('SELECT flight_number, aircraft, scheduled_time FROM flights ORDER BY id DESC LIMIT 20')
for r in rows: print(f'{r[0]:<10} {r[1]:<8} {r[2]}')
"
```

## Files

| File | Purpose |
|------|---------|
| `~/.config/systemd/user/flightscanner.service` | systemd unit |
| `~/FlightScanner/flightscanner.env` | Discord webhook URL (chmod 600) |
| `~/FlightScanner/target/FlightScraper-*-jar-with-dependencies.jar` | Built artifact |
| `~/FlightScanner/src/main/java/com/richi/` | Source code (18 classes) |

## Requirements

- Java 21 (Temurin at `/opt/java-21`)
- Maven 3.8+
- dump1090-fa running on port 30003
- Discord webhook URL
