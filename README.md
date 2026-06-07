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
| `discord.notify.level` | `noteworthy` | Notification tier — see table below |
| `discord.notify.all` | *(deprecated)* | Use `discord.notify.level=all` instead |
| `discord.startup.test.message` | `false` | Post "✅ connected" on startup |
| `adsb.alert.emergency.squawks` | `7500,7600,7700` | Squawks that trigger ALERT |
| `adsb.alert.low.altitude.feet` | `1500` | Altitude below which ALERT fires |
| `adsb.alert.gov.hex.ranges` | `0x348000-0x34FFFF` | Government ICAO hex ranges |
| `adsb.alert.military.operators` | *list* | Military operator substrings |
| `adsb.alert.cooldown.minutes` | `5` | Per-aircraft ALERT cooldown |
| `ai.analysis.enabled` | `false` | DeepSeek AI (not currently used) |

## Notification Levels

| Level | `discord.notify.level` | What fires |
|-------|------------------------|------------|
| ALERT only | `alert` | Emergency squawk, low altitude, gov hex range, military operator |
| Noteworthy | `noteworthy` (default) | ALERT + widebody / military-type / bizjet |
| All | `all` | Every detected flight (testing/demo) |

ALERT notifications are always sent regardless of the configured level and use a distinct red embed with squawk and hex fields. They have a separate 5-minute per-aircraft cooldown.

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
# Enable all flights (testing/demo) — set the notification level
sed -i 's/discord.notify.level=noteworthy/discord.notify.level=all/' \
  src/main/resources/application.properties
cd ~/FlightScanner && JAVA_HOME=/opt/java-21 mvn package -DskipTests -q
systemctl --user restart flightscanner

# Disable (back to default noteworthy tier)
sed -i 's/discord.notify.level=all/discord.notify.level=noteworthy/' \
  src/main/resources/application.properties
cd ~/FlightScanner && JAVA_HOME=/opt/java-21 mvn package -DskipTests -q
systemctl --user restart flightscanner

# ALERT-only mode (quiet — fires only on squawk / low-altitude / gov-hex / mil-operator)
sed -i 's/discord.notify.level=noteworthy/discord.notify.level=alert/' \
  src/main/resources/application.properties
```

## Discord Embed Categories

| Emoji | Category | Color | Examples / Triggers |
|-------|----------|-------|---------------------|
| 🚨 | ALERT | Red | Squawk 7500/7600/7700, altitude < 1500 ft, gov hex, military operator |
| 🛫 | Widebody | Orange | A330, B777, B787, A350, A380 |
| 🪖 | Military type | Gold | A400, C130, F16, E3TF |
| 🛩️ | Business jet | Amber | GLEX, C56X, E55P, CL60 |
| ✈️ | Commercial | Blue | A320, B738, E190, CRJ2 |

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
| `~/FlightScanner/src/main/java/com/richi/` | Source code (22 classes) |

## Requirements

- Java 21 (Temurin at `/opt/java-21`)
- Maven 3.8+
- dump1090-fa running on port 30003
- Discord webhook URL
