# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Summary

FlightScanner is a Java 21 / Maven application that ingests real-time ADS-B aircraft data from a local `dump1090-fa` receiver (SBS stream on TCP 30003), enriches it via the adsb.lol API, persists flights to SQLite, and sends Discord webhook notifications for "interesting" aircraft (widebody / military / bizjet).

The repo also contains a legacy FlightRadar24 web-scraping mode (`FlightTrackerApp` / Playwright). The deployed system uses ADS-B mode only — keep both build paths working but assume ADS-B is the active runtime.

## Build, Run, Deploy

The project uses Java 21 (Temurin at `/opt/java-21`) and Maven (use the wrapper `./mvnw` if no system Maven). The fat JAR's manifest `Main-Class` is `com.richi.FlightTrackerApp` (web scraper) — for ADS-B mode the systemd unit invokes `com.richi.ADSBFlightTracker` explicitly.

```bash
# Build (skip tests for a fast compile/package)
JAVA_HOME=/opt/java-21 mvn package -DskipTests -q

# Run the JUnit 5 test suite
JAVA_HOME=/opt/java-21 mvn test -q

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

There is a JUnit 5 test suite under `src/test/java` (run with `mvn test`) covering the classifier (`AircraftTypesTest`), analyzer (`LocalAircraftAnalyzerTest`), SBS parsing (`SBSMessageParseTest`), and the SQLite round-trip (`SqlFlightRepositoryTest`). `test-build.sh` and `test-scraper.sh` only smoke-launch the JAR for a few seconds.

## Notification Tiers

Three tiers, configured via `discord.notify.level`:

| Tier | Trigger | Emoji | Color | Fires when level is… |
|------|---------|-------|-------|----------------------|
| **ALERT** | emergency squawk (7500/7600/7700), altitude < threshold, ICAO hex in a government range, operator matches a military keyword | 🚨 | red (0xFF0000) | always (ALERT / NOTEWORTHY / ALL) |
| **NOTEWORTHY** | widebody / military-type / bizjet | 🛫🪖🛩 | varies | NOTEWORTHY or ALL |
| **ROUTINE** | everything else | — | — | ALL only |

ALERT always wins: a flight that triggers ALERT is sent via `sendCriticalAlert()` with a distinct embed (squawk + hex fields shown). NOTEWORTHY flights go through the regular `sendAlert()` embed.

## Configuration

Single source of truth: `src/main/resources/application.properties`, loaded by `ConfigManager` (singleton). Any property can be overridden by an environment variable: convert the key to UPPER_SNAKE_CASE (e.g. `discord.webhook.url` → `DISCORD_WEBHOOK_URL`, `adsb.dump1090.host` → `ADSB_DUMP1090_HOST`). The deployed unit reads secrets from `flightscanner.env`.

Notable runtime toggles:
- `discord.notify.level` — `noteworthy` (default) / `all` (every flight, testing) / `alert` (ALERT tier only). See Notification Tiers above.
- `discord.notify.all` — **deprecated**; still honored as an OR override on top of the level. Logs a warning at startup if present. Prefer `discord.notify.level=all`.
- `discord.startup.test.message` — `false` (default) suppresses the "✅ connected" post on startup; `true` re-enables it.
- `adsb.notification.cooldown.hours` — per-key NOTEWORTHY cooldown. `ADSBFlightTracker` keeps a `Map<flightNumber+scheduledTime → lastNotifiedInstant>`; `maybeNotify` skips a key only while `now − lastNotified < cooldown`. A periodic task prunes expired entries (it does **not** wipe the whole map).
- `adsb.alert.cooldown.minutes` — per-aircraft ALERT cooldown (default 5 min). Keyed on ICAO hex if available, else flightNumber+scheduledTime.
- `adsb.alert.emergency.squawks` — comma-separated squawk codes that trigger ALERT (default `7500,7600,7700`).
- `adsb.alert.low.altitude.feet` — altitude threshold in feet (default 1500); airborne aircraft below this trigger ALERT.
- `adsb.alert.gov.hex.ranges` — comma-separated ICAO hex ranges for government/state aircraft (e.g. `0x348000-0x34FFFF`).
- `adsb.alert.military.operators` — comma-separated substrings matched case-insensitively against the enriched operator field.
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
     └── every 1h                          → prunes expired entries from notifiedFlights cooldown map
```

Key components:

- **`adsb/`** — `ADSBDataSource` interface + `Dump1090DataSource` socket client; `SBSMessage` parses the BaseStation CSV format; `ADSBListener` is the callback contract (`onAircraftDetected/Updated/Lost`); `ADSBStats` is a stats record.
- **`service/AircraftEnrichmentService`** — owns its own SQLite DB (`aircraft_cache.db`, separate from `flights.db`). Cache is permanent; stale entries are not invalidated. Rate-limits API calls to one per 30s; backs off 60s on HTTP 429.
- **`analyzer/LocalAircraftAnalyzer`** — classifies aircraft by ICAO type code into widebody / military / bizjet via `AircraftTypes.AircraftCategory` (single source of truth in `AircraftTypes.classify(...)`, used by both the analyzer and the Discord notifier).
- **`analyzer/AircraftAlerter`** — evaluates ALERT-tier triggers (emergency squawk, low altitude, government hex range, military operator keyword). All four triggers are config-driven; thresholds loaded from `ConfigManager` at construction.
- **`config/NotifyLevel`** — enum for `discord.notify.level` (ALL / NOTEWORTHY / ALERT), parsed at startup with NOTEWORTHY as the default.
- **`notification/DiscordFlightNotifier`** — sends webhook embeds. Category (and therefore emoji + color) is taken from the shared `AircraftTypes.AircraftCategory` enum, so the "is this interesting?" filter and the embed emoji can never disagree. ALERT pings get a distinct 🚨 red embed via `sendCriticalAlert(...)`.
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
- When changing ICAO type-based classification, edit only `AircraftTypes.java` — both `LocalAircraftAnalyzer` and `DiscordFlightNotifier` read from the shared `AircraftCategory` enum.
- When changing ALERT thresholds, edit only `application.properties` — all four trigger types (squawk, altitude, hex range, operator keyword) are config-driven and read by `AircraftAlerter` at construction time. To add a new trigger type, extend `AircraftAlerter`.
- Notification cooldown: NOTEWORTHY uses `notifiedFlights` (`Map<flightNumber+scheduledTime, Instant>`) in `maybeNotify`; ALERT uses `alertedFlights` (`Map<hexIdent-or-uniqueKey, Instant>`) in `maybeAlert`. `cleanupNotifiedFlights` prunes both maps hourly.
- `config.getNotifyLevel()` returns a `NotifyLevel` enum (ALL / NOTEWORTHY / ALERT). The legacy `config.isDiscordNotifyAll()` is still honored as an OR override.
- Lombok is used (`@Slf4j`, `@Getter`). The compiler plugin is configured with the annotation processor; ensure your IDE has Lombok support enabled.
