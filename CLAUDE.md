# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Summary

FlightScanner is a Java 21 / Maven application that ingests real-time ADS-B aircraft data from a local `dump1090-fa` receiver (SBS stream on TCP 30003), enriches it via the adsb.lol API, persists flights to SQLite, and sends Discord webhook notifications for "interesting" aircraft (widebody / military / bizjet).

The repo also contains a legacy FlightRadar24 web-scraping mode (`FlightTrackerApp` / Playwright). The deployed system uses ADS-B mode only — keep both build paths working but assume ADS-B is the active runtime.

## Build, Run, Deploy

The project uses Java 21 (Temurin at `/opt/java-21`) and Maven (use the wrapper `./mvnw` if no system Maven). The fat JAR's manifest `Main-Class` is `com.richi.FlightTrackerApp` (web scraper) — for ADS-B mode the systemd unit invokes `com.richi.ADSBFlightTracker` explicitly.

```bash
# Build (skip tests — there are none configured)
JAVA_HOME=/opt/java-21 mvn package -DskipTests -q

# Run ADS-B mode directly
mvn exec:java -Dexec.mainClass="com.richi.ADSBFlightTracker"

# Run web scraper mode directly
mvn exec:java -Dexec.mainClass="com.richi.FlightTrackerApp"

# Run the fat JAR (defaults to FlightTrackerApp via manifest)
java -jar target/FlightScraper-0.0.1-SNAPSHOT-jar-with-dependencies.jar

# Run as the deployed service
systemctl --user restart flightscanner
journalctl --user -u flightscanner -f          # live logs
journalctl --user -u flightscanner -n 50       # recent
```

There is no test suite. `test-build.sh` and `test-scraper.sh` only smoke-launch the JAR for a few seconds.

## Configuration

Single source of truth: `src/main/resources/application.properties`, loaded by `ConfigManager` (singleton). Any property can be overridden by an environment variable: convert the key to UPPER_SNAKE_CASE (e.g. `discord.webhook.url` → `DISCORD_WEBHOOK_URL`, `adsb.dump1090.host` → `ADSB_DUMP1090_HOST`). The deployed unit reads secrets from `flightscanner.env`.

Notable runtime toggles:
- `discord.notify.all` — `false` (default) only notifies on widebody/military/bizjet; `true` notifies every detected flight (testing mode).
- `adsb.notification.cooldown.hours` — duplicate-suppression window per `flightNumber+scheduledTime` key.
- `ai.analysis.enabled` / `deepseek.api.key` — DeepSeek fallback path in `LocalAircraftAnalyzer`. Currently disabled; the AI path is exercised only when local detection finds zero widebodies.

`ConfigManager.validate()` enforces required values when the corresponding feature is enabled (e.g. webhook URL when `discord.enabled=true`).

## dump1090-fa (external dependency)

The tracker assumes dump1090-fa is already running locally and exposing the SBS (BaseStation) feed on TCP 30003. Quick checks:

```bash
nc -zv localhost 30003          # is the port up?
nc localhost 30003 | head -3    # do messages flow? expect lines like: MSG,3,...
```

If SBS output isn't enabled, set `NET_SBS_OUTPUT_PORT=30003` in `/etc/default/dump1090-fa` and `sudo systemctl restart dump1090-fa`.

## Architecture

Two top-level entry points, sharing the rest of the code:

- `com.richi.ADSBFlightTracker` (active) — listens to the ADS-B stream, dispatches notifications.
- `com.richi.FlightTrackerApp` (legacy) — schedules `PlaywrightFlightScraper` against FlightRadar24 on `scraper.interval.minutes`.

ADS-B data flow:

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
     ├── onAircraftDetected → LocalAircraftAnalyzer.isInteresting? → DiscordFlightNotifier
     ├── every adsb.save.interval.minutes  → SqlFlightRepository.saveFlights
     └── every 1h                          → clears notifiedFlights cooldown set
```

Key components:

- **`adsb/`** — `ADSBDataSource` interface + `Dump1090DataSource` socket client; `SBSMessage` parses the BaseStation CSV format; `ADSBListener` is the callback contract (`onAircraftDetected/Updated/Lost`); `ADSBStats` is a stats record.
- **`service/AircraftEnrichmentService`** — owns its own SQLite DB (`aircraft_cache.db`, separate from `flights.db`). Cache is permanent; stale entries are not invalidated. Rate-limits API calls to one per 30s; backs off 60s on HTTP 429.
- **`analyzer/LocalAircraftAnalyzer`** — classifies aircraft by ICAO type code into widebody / military / bizjet via hardcoded `Set<String>` allowlists. `normalizeAircraftCode` strips dashes/spaces and collapses common variants (`B777*` → `B77`, `B787*` → `B78`, `A350*` → `A35`).
- **`notification/DiscordFlightNotifier`** — sends webhook embeds. ⚠️ **The widebody/military/bizjet allowlists are duplicated here** (independently of `LocalAircraftAnalyzer`) to drive the embed emoji + color. When adding aircraft types, update **both** files.
- **`repository/SqlFlightRepository`** — JDBC + HikariCP. Supports SQLite (default) and MySQL via `db.type`. Uses `INSERT OR IGNORE` semantics keyed on `UNIQUE(flight_number, scheduled_time)`.
- **`config/ConfigManager`** — singleton; getters are Lombok-generated (`@Getter`). Tests can call `ConfigManager.reset()` to drop the cached instance.
- **`model/Flight`** — Java `record`. `getUniqueKey()` is `flightNumber + "_" + scheduledTime` and is what the cooldown set keys on.

## State and Side-Effect Surface

These exist outside the Maven build and are not in `target/`:

- `flights.db` — persisted flight records (5-min batch from the ADS-B stream).
- `aircraft_cache.db` — enrichment cache (one row per hex, never expires).
- `flightscanner.env` — Discord webhook URL, mode 600. Loaded by the systemd unit.
- `~/.config/systemd/user/flightscanner.service` — systemd unit that runs the deployed jar.

`*.db` and `*.env` are gitignored. Don't commit them, and don't delete them unless you intend to reset state — `aircraft_cache.db` in particular represents many adsb.lol API calls.

## Working in this Repo

- After editing code, you must rebuild and restart the service for changes to take effect: `mvn package -DskipTests -q && systemctl --user restart flightscanner`. Running via `mvn exec:java` does not affect the deployed unit.
- When changing aircraft classification, edit **both** `LocalAircraftAnalyzer.java` and `DiscordFlightNotifier.java` — the type sets are duplicated.
- `ADSBFlightTracker.cleanupNotifiedFlights` clears the entire cooldown set every hour rather than tracking per-entry timestamps. If you need true per-flight cooldowns, rework that method (and `notifiedFlights`'s value type).
- Lombok is used (`@Slf4j`, `@Getter`). The compiler plugin is configured with the annotation processor; ensure your IDE has Lombok support enabled.
- AGENTS.md documents an older project layout (e.g. `notification/TelegramFlightNotifier`, `service/FlightScraperService` paths) — trust the actual filesystem over AGENTS.md when they disagree.
