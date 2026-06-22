<div align="center">

```
    __ _ _   ___   _ __ ___    _ __ ___   _____   _(_)_   _____ 
   / _` | | / __| | '_ ` _ \  | '_ ` _ \ / _ \ \ / / \ \ / / _ \
  | (_| | || (__  | | | | | | | | | | | |  __/\ V /| |\ V /  __/
   \__,_|_| \___| |_| |_| |_| |_| |_| |_|\___| \_/ |_| \_/ \___|
```

### Real-time ADS-B aircraft tracker. Java 21. Zero AI. Maximum signal.

**FlightScanner** listens to a `dump1090-fa` SDR receiver, enriches aircraft
via `adsb.lol`, persists to SQLite, and pings Discord when something
*interesting* flies overhead — widebodies, military, bizjets, and an
always-on ALERT tier for emergency squawks / low altitude / gov hex ranges.

<br>

![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-3.8%2B-C71A36?logo=apachemaven&logoColor=white)
![SQLite](https://img.shields.io/badge/storage-SQLite-003B57?logo=sqlite&logoColor=white)
![License](https://img.shields.io/badge/license-MIT-22c55e)
![No AI](https://img.shields.io/badge/AI-none-d946ef)
![Discord](https://img.shields.io/badge/Discord-webhook%20ready-5865F2?logo=discord&logoColor=white)

</div>

---

## ✺ What it does

You point a `dump1090-fa` SDR dongle at the sky. FlightScanner:

1. **Listens** to the SBS (BaseStation) stream on `localhost:30003`
2. **Enriches** every new ICAO hex once via `adsb.lol` (cached forever)
3. **Classifies** aircraft into categories — widebody / military / bizjet / commercial
4. **Filters** by airport proximity (default: 100 NM of a configured IATA code)
5. **Notifies** Discord in three tiers: 🚨 ALERT, 🛫 NOTEWORTHY, ✈️ ALL
6. **Persists** every in-range flight to SQLite every 5 minutes
7. **Serves** a built-in HTTP dashboard on `:3006` — HTML, JSON, Prometheus metrics

No LLM. No cloud. No account. Pure local signal.

---

## ⚡ Quick start

You need three things running locally: a `dump1090-fa` instance feeding
SBS, Java 21, and a Discord webhook URL.

```bash
git clone https://github.com/riesbri/FlightScanner.git
cd FlightScanner

# Build
JAVA_HOME=/path/to/java-21 mvn package -DskipTests -q

# Configure
echo 'DISCORD_WEBHOOK_URL="https://discord.com/api/webhooks/..."' > flightscanner.env
chmod 600 flightscanner.env

# Run
java -jar target/flightscanner-0.0.1-SNAPSHOT-jar-with-dependencies.jar
```

Open `http://localhost:3006/` for the dashboard.

> **Not in Spain?** The default airport is VLC (Valencia). Set `airport.code` to your nearest IATA code in `application.properties`, or override with exact coordinates using `airport.coordinates.lat` / `airport.coordinates.lon` — see [Airport proximity filter](#-airport-proximity-filter) below.

---

## 🔔 Notification tiers

Three tiers, configured via `discord.notify.level` in `application.properties`:

| Tier | Emoji | Color | Trigger | Bypasses rate limit? |
|---|---|---|---|---|
| **ALERT** | 🚨 | red | Emergency squawk (7500/7600/7700), altitude < threshold, gov hex range, military operator keyword | ✅ always |
| **NOTEWORTHY** | 🛫🪖🛩️ | varies | Widebody / military-type / bizjet | ❌ |
| **ALL** | ✈️ | gray | Everything else (testing only, `discord.notify.level=all`) | ❌ |

**Rate limit:** NOTEWORTHY/ALL pings are capped at 10/min by default.
When the cap is hit, overflow is **coalesced** into a single `📊 +N more…`
embed posted every 60s. **ALERT tier always bypasses** — a squawk 7700
goes through immediately, no coalescing, no queue.

This prevents notification spam during take-off rushes or airshow
practice windows while never silencing actual emergencies.

---

## ✈️ Aircraft classification

`AircraftTypes.classify()` is the single source of truth. Both the
filter and the Discord embed read from the same `AircraftCategory`
enum, so the "is this interesting?" decision and the embed emoji
can never disagree.

| Category | Emoji | Color | Examples |
|---|---|---|---|
| Widebody | 🛫 | red | A330, B777, B787, A350, A380 |
| Military type | 🪖 | purple | A400, C130, F16, E3TF |
| Business jet | 🛩️ | yellow | GLEX, C56X, E55P, CL60 |
| Commercial | ✈️ | blue | A320, B738, E190, CRJ2 |

The classifier is purely deterministic — no LLM, no heuristic guesswork.
Edit one file to add a type, the rest follows.

---

## 📍 Airport proximity filter

Flights outside `airport.radius.nm` (default: 100 NM) of the configured
airport are dropped **before** notification and **before** persistence.
Default `airport.code=VLC` (Valencia); a small Spanish airport table
(VLC / MAD / BCN / SVQ / ALC) ships in `geo/AirportCoords.java`. Override
with `airport.coordinates.lat` / `airport.coordinates.lon` for any
location — both must be set.

```properties
airport.code=VLC
airport.radius.nm=100
airport.filter.require-position=false
```

`require-position=true` also drops aircraft that haven't broadcast a
position yet (useful for low-altitude filtering in dense airspace).

---

## 🌐 Built-in HTTP server

Starts automatically on port **3006** (JDK `com.sun.net.httpserver` —
no extra dependency). Toggle with `webui.enabled=false`.

| Endpoint | Returns |
|---|---|
| `GET /` | Dark-mode HTML dashboard (auto-refresh 30s, last 50 noteworthy flights) |
| `GET /metrics` | Prometheus text format (`text/plain; version=0.0.4`) |
| `GET /api/flights?since=<ISO>&tier=all\|noteworthy\|alert` | JSON |

```bash
# Live metrics
curl -s http://localhost:3006/metrics

# Today's noteworthy flights as JSON
curl -s "http://localhost:3006/api/flights"

# All flights since midnight
curl -s "http://localhost:3006/api/flights?tier=all&since=$(date +%Y-%m-%dT00:00:00)"
```

The JSON tier filtering re-classifies at response time — no tier
column in the DB, so historical queries work even after you change
the config.

---

## 💾 Storage

Two SQLite databases, both local, both gitignored.

| File | Purpose |
|---|---|
| `aircraft_cache.db` | Enrichment cache — one row per ICAO hex, never expires |
| `flights.db` | Persisted flight records, written every 5 min from the live stream |

```bash
# Top 20 most-recently enriched aircraft
python3 -c "
import sqlite3
conn = sqlite3.connect('aircraft_cache.db')
for r in conn.execute('SELECT icao_hex, icao_type, operator FROM aircraft_types ORDER BY updated_at DESC LIMIT 20'):
    print(f'{r[0]} {r[1]:<6} {r[2]}')
"

# Today's flights
python3 -c "
import sqlite3
conn = sqlite3.connect('flights.db')
print('Total flights:', conn.execute('SELECT count(*) FROM flights').fetchone()[0])
for r in conn.execute('SELECT flight_number, aircraft, scheduled_time FROM flights ORDER BY id DESC LIMIT 20'):
    print(f'{r[0]:<10} {r[1]:<8} {r[2]}')
"
```

---

## 🛠 Architecture

Entry point: `com.flightscanner.ADSBFlightTracker` — listens to the ADS-B stream, dispatches notifications.

```
dump1090-fa :30003 (SBS CSV)
     │
     ▼
Dump1090DataSource ── parses SBS via SBSMessage, maintains aircraftMap
     │  (auto-reconnects, runs stale-cleanup, fires ADSBListener events)
     │
     │  on first sighting of a hex with no type → submits to enrichmentExecutor
     │       │
     │       ▼
     │  AircraftEnrichmentService → adsb.lol /v2/icao/{hex}
     │       │      (rate-limited, results cached forever in aircraft_cache.db)
     │       ▼
     │  Updates Flight.aircraft with the ICAO type code
     │
     ▼
ADSBFlightTracker (ADSBListener)
     ├── onAircraftDetected → AirportCoords proximity filter → LocalAircraftAnalyzer.isInteresting? → DiscordFlightNotifier
     │       (aircraft outside airport.radius.nm are dropped before notify OR persist)
     ├── every adsb.save.interval.minutes  → SqlFlightRepository.saveFlights (only in-range flights)
     ├── every 60s                         → DiscordFlightNotifier.flushCoalescedSummary (rate-limit overflow)
     ├── every 1h                          → prunes expired entries from notifiedFlights cooldown map
     └── (startup, if webui.enabled=true)  → WebServer on :3006 serving /, /metrics, /api/flights
```

Key components:

- **`adsb/`** — `ADSBDataSource` interface + `Dump1090DataSource` socket client; `SBSMessage` parses BaseStation CSV; `ADSBListener` callback contract.
- **`analyzer/LocalAircraftAnalyzer`** — classifies by ICAO type via the shared `AircraftTypes.AircraftCategory` enum.
- **`analyzer/AircraftAlerter`** — evaluates ALERT-tier triggers (squawk, altitude, hex range, operator keyword), all config-driven.
- **`notification/DiscordFlightNotifier`** — webhook embeds; category emoji + color from the same enum the analyzer uses.
- **`repository/SqlFlightRepository`** — JDBC + HikariCP. SQLite (default) and MySQL supported via `db.type`.
- **`web/WebServer`** — JDK `com.sun.net.httpserver`; serves `/`, `/metrics`, `/api/flights`. Zero extra deps.

---

## ⚙️ Configuration

Single source of truth: `src/main/resources/application.properties`,
loaded by `ConfigManager` (singleton). Any property can be overridden
by an env var — convert the key to UPPER_SNAKE_CASE
(`discord.webhook.url` → `DISCORD_WEBHOOK_URL`,
`adsb.dump1090.host` → `ADSB_DUMP1090_HOST`).

Notable settings:

| Setting | Default | Notes |
|---|---|---|
| `adsb.enabled` | `true` | Enable ADS-B mode |
| `adsb.dump1090.host` | `localhost` | dump1090-fa host |
| `adsb.dump1090.port` | `30003` | SBS stream port |
| `discord.enabled` | `true` | Enable Discord notifications |
| `discord.webhook.url` | env var | Set via `flightscanner.env` |
| `discord.notify.level` | `noteworthy` | `noteworthy` / `all` / `alert` |
| `discord.rate.limit.per.minute` | `10` | Max NOTEWORTHY/ALL pings per 60s |
| `discord.rate.coalesce.enabled` | `true` | Overflow → `📊 +N more…` embed per minute |
| `adsb.alert.emergency.squawks` | `7500,7600,7700` | Squawks that trigger ALERT |
| `adsb.alert.low.altitude.feet` | `1500` | Altitude threshold for ALERT |
| `adsb.alert.gov.hex.ranges` | `0x348000-0x34FFFF` | Government ICAO hex ranges |
| `adsb.alert.military.operators` | list | Substrings matched against enriched operator |
| `adsb.alert.cooldown.minutes` | `5` | Per-aircraft ALERT cooldown |
| `airport.code` | `VLC` | IATA code for the proximity filter |
| `airport.radius.nm` | `100` | Proximity filter radius |
| `webui.enabled` | `true` | Built-in HTTP server on :3006 |

`ConfigManager.validate()` enforces required values when the
corresponding feature is enabled (e.g. webhook URL when
`discord.enabled=true`).

---

## 🧪 Development

```bash
JAVA_HOME=/path/to/java-21 mvn test
```

JUnit 5 covers the classifier (`AircraftTypesTest`), analyzer
(`LocalAircraftAnalyzerTest`), SBS parsing (`SBSMessageParseTest`),
ALERT tier (`AircraftAlerterTest`), rate limiter
(`DiscordRateLimiterTest`), airport filter (`AirportFilterTest`),
config (`ConfigManagerTest`), repo round-trip
(`SqlFlightRepositoryTest`), and the HTTP server
(`WebServerTest`).

`test-build.sh` smoke-launches the JAR for a few seconds.

---

## 📋 Requirements

- Java 21 (Temurin recommended)
- Maven 3.8+
- A `dump1090-fa` instance exposing the SBS feed on `localhost:30003`
- A Discord webhook URL (only if `discord.enabled=true`)

To enable the SBS output on `dump1090-fa` if it's not already:

```bash
echo 'NET_SBS_OUTPUT_PORT=30003' | sudo tee -a /etc/default/dump1090-fa
sudo systemctl restart dump1090-fa

# Quick checks
nc -zv localhost 30003
nc localhost 30003 | head -3    # expect: MSG,3,...
```

---

## 🗂 Files

| File | Purpose |
|---|---|
| `src/main/resources/application.properties` | Single source of config truth |
| `flightscanner.env` | Discord webhook URL (chmod 600, gitignored) |
| `flights.db` | Persisted flight records (gitignored) |
| `aircraft_cache.db` | Enrichment cache (gitignored) |
| `target/flightscanner-*-jar-with-dependencies.jar` | Built fat JAR |
| `~/.config/systemd/user/flightscanner.service` | systemd unit (template in repo) |

---

## 📄 License

MIT — see `LICENSE`.

---

<div align="center">
<sub>Built so widebodies over your house don't go unnoticed.</sub>
</div>
