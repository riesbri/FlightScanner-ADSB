# CLAUDE.md

> Developer context for AI assistants. Deployment notes assume a Linux host running the app as a systemd user service. See README.md for general usage.

> **PUBLIC REPO — never commit secrets.** `flightscanner.env`, `*.db`, Discord webhook URLs, API keys, IP addresses, and any personal data must stay out of git. They are covered by `.gitignore` but double-check before every commit. Do not add new files containing secrets without updating `.gitignore` first.

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Summary

FlightScanner is a Java 21 / Maven application that ingests real-time ADS-B aircraft data from a local `dump1090-fa` receiver (SBS stream on TCP 30003), enriches it via the adsb.lol API, persists flights to SQLite, and sends Discord webhook notifications for "interesting" aircraft (widebody / military / bizjet) plus an always-on ALERT tier. It also serves a built-in web dashboard (live Leaflet map, JSON API, Prometheus metrics, health check) and posts a daily Discord digest.

## Build, Run, Deploy

The project uses Java 21 (Temurin recommended) and Maven. The fat JAR's manifest `Main-Class` is `com.flightscanner.ADSBFlightTracker`.

```bash
# Build (skip tests for a fast compile/package)
JAVA_HOME=/path/to/java-21 mvn package -DskipTests -q

# Run the JUnit 5 test suite
JAVA_HOME=/path/to/java-21 mvn test -q

# Run ADS-B mode directly
mvn exec:java -Dexec.mainClass="com.flightscanner.ADSBFlightTracker"

# Run the fat JAR
java -jar target/flightscanner-0.0.1-SNAPSHOT-jar-with-dependencies.jar

# Smoke-test without Discord: listen and print flights to stdout, then exit
# (duration defaults to adsb.dry-run.duration-seconds, or 60)
java -jar target/flightscanner-0.0.1-SNAPSHOT-jar-with-dependencies.jar --dry-run 30

# Run as a systemd user service (if deployed)
systemctl --user restart flightscanner
journalctl --user -u flightscanner -f          # live logs
journalctl --user -u flightscanner -n 50       # recent
```

There is a JUnit 5 test suite under `src/test/java` (run with `mvn test`, 111 tests) covering the classifier (`AircraftTypesTest`), analyzer (`LocalAircraftAnalyzerTest`), ALERT tier + watchlist (`AircraftAlerterTest`), SBS parsing (`SBSMessageParseTest`), rate limiter (`DiscordRateLimiterTest`), airport proximity filter (`AirportFilterTest`), country-flag lookup (`ICAOCountryTest`), config (`ConfigManagerTest`), the SQLite round-trip (`SqlFlightRepositoryTest`), the HTTP server incl. `/health` and `/api/live` (`WebServerTest`), and a dry-run smoke test (`ADSBFlightTrackerDryRunTest`). `test-build.sh` smoke-launches the JAR for a few seconds.

## Notification Tiers

Three tiers, configured via `discord.notify.level`:

| Tier | Trigger | Emoji | Color | Fires when level is… |
|------|---------|-------|-------|----------------------|
| **ALERT** | emergency squawk (7500/7600/7700), altitude < threshold, ICAO hex in a government range, operator matches a military keyword, or ICAO hex on the watchlist | 🚨 | red (0xFF0000) | always (ALERT / NOTEWORTHY / ALL) |
| **NOTEWORTHY** | widebody / military-type / bizjet | 🛫🪖🛩 | varies | NOTEWORTHY or ALL |
| **ALL** | everything else | — | — | ALL only (`discord.notify.level=all`) |

ALERT always wins: a flight that triggers ALERT is sent via `sendCriticalAlert()` with a distinct embed (squawk + hex fields shown). NOTEWORTHY flights go through the regular `sendAlert()` embed.

## Configuration

Single source of truth: `src/main/resources/application.properties`, loaded by `ConfigManager` (singleton). Any property can be overridden by an environment variable: convert the key to UPPER_SNAKE_CASE (e.g. `discord.webhook.url` → `DISCORD_WEBHOOK_URL`, `adsb.dump1090.host` → `ADSB_DUMP1090_HOST`). Secrets (e.g. webhook URL) are loaded from `flightscanner.env` when using the systemd unit.

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
- `adsb.watchlist.hex` — comma-separated ICAO hex codes to always ALERT on, regardless of type (default empty). Evaluated by `AircraftAlerter.isWatchlisted`.
- `discord.digest.hour` — hour (24h, local time) at which the previous day's summary digest is posted to Discord (default `20`). Scheduled in `ADSBFlightTracker`; skipped if no flights were recorded for that day.
- `discord.rate.limit.per.minute` — max NOTEWORTHY/ALL notifications per 60s window (default `10`). ALERT pings always bypass this limit.
- `discord.rate.coalesce.enabled` — when `true` (default), overflow is coalesced into a single "📊 +N more…" embed posted every 60s; when `false`, overflow is dropped silently.
- `airport.coordinates.lat` / `airport.coordinates.lon` — override the hardcoded airport table. Both must be set to take effect.
- `airport.radius.nm` — proximity filter radius in nautical miles (default `100`). Only aircraft within this radius of the configured airport are notified/persisted.
- `airport.filter.require-position` — when `true`, aircraft with no position yet are dropped; when `false` (default), positionless aircraft pass through.
- `webui.enabled` — `true` (default) starts the built-in HTTP server; `false` disables it entirely.
- `webui.host` — bind address for the web server (default `0.0.0.0`; use `127.0.0.1` for localhost-only).
- `webui.port` — HTTP port (default `3006`). Endpoints: `GET /` (HTML dashboard + live map), `GET /api/live` (currently-tracked aircraft JSON), `GET /api/flights?since=&tier=` (historical JSON), `GET /metrics` (Prometheus), `GET /health` (JSON health), `GET /api/debug/digest` (manually trigger a digest post — for testing).

A `--dry-run [seconds]` CLI flag (parsed in `ADSBFlightTracker.main`) listens and prints flights to stdout without sending Discord notifications, then exits; the duration falls back to `adsb.dry-run.duration-seconds` (default 60). This property is not in `application.properties` — it only takes effect if added or passed via env var.

`ConfigManager.validate()` enforces required values when the corresponding feature is enabled (e.g. webhook URL when `discord.enabled=true`).

## dump1090-fa (external dependency)

The tracker assumes dump1090-fa is already running locally and exposing the SBS (BaseStation) feed on TCP 30003. Quick checks:

```bash
nc -zv localhost 30003          # is the port up?
nc localhost 30003 | head -3    # do messages flow? expect lines like: MSG,3,...
```

If SBS output isn't enabled, set `NET_SBS_OUTPUT_PORT=30003` in `/etc/default/dump1090-fa` and `sudo systemctl restart dump1090-fa`.

## Architecture

Entry point: `com.flightscanner.ADSBFlightTracker` — listens to the ADS-B stream, dispatches notifications.

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
     ├── onAircraftDetected → AirportCoords proximity filter → AircraftAlerter (ALERT) / LocalAircraftAnalyzer.isInteresting? → DiscordFlightNotifier
     │       (aircraft outside airport.radius.nm are dropped before notify OR persist)
     │       (NOTEWORTHY embeds include country flag emoji from ICAOCountry + "last seen N days ago" via findLastSeen)
     ├── every adsb.save.interval.minutes  → SqlFlightRepository.saveFlights (only in-range flights); flightsPersistedCount += saved
     ├── every 60s                         → DiscordFlightNotifier.flushCoalescedSummary (rate-limit overflow)
     ├── every 1h                          → prunes expired entries from the notifiedFlights and alertedFlights cooldown maps
     ├── daily at discord.digest.hour      → sendDailyDigest (previous day's widebody/military/bizjet counts)
     └── (startup, if webui.enabled=true)  → WebServer on :3006 serving /, /api/live, /api/flights, /metrics, /health, /api/debug/digest
```

Key components:

- **`adsb/`** — `ADSBDataSource` interface + `Dump1090DataSource` socket client; `SBSMessage` parses the BaseStation CSV format; `ADSBListener` is the callback contract (`onAircraftDetected/Updated/Lost`); `ADSBStats` is a stats record.
- **`service/AircraftEnrichmentService`** — owns its own SQLite DB (`aircraft_cache.db`, separate from `flights.db`). Cache is permanent; stale entries are not invalidated. Rate-limits API calls to one per 30s; backs off 60s on HTTP 429.
- **`analyzer/LocalAircraftAnalyzer`** — classifies aircraft by ICAO type code into widebody / military / bizjet via `AircraftTypes.AircraftCategory` (single source of truth in `AircraftTypes.classify(...)`, used by both the analyzer and the Discord notifier).
- **`analyzer/AircraftAlerter`** — evaluates ALERT-tier triggers (emergency squawk, low altitude, government hex range, military operator keyword, watchlist hex). All triggers are config-driven; thresholds loaded from `ConfigManager` at construction.
- **`geo/ICAOCountry`** — static lookup mapping an ICAO hex prefix to a country flag emoji + name (`flagFromHex`). Used in Discord embeds and the `/api/live` JSON.
- **`config/NotifyLevel`** — enum for `discord.notify.level` (ALL / NOTEWORTHY / ALERT), parsed at startup with NOTEWORTHY as the default.
- **`notification/DiscordFlightNotifier`** — sends webhook embeds. Category (and therefore emoji + color) is taken from the shared `AircraftTypes.AircraftCategory` enum, so the "is this interesting?" filter and the embed emoji can never disagree. NOTEWORTHY embeds add a country flag (via `ICAOCountry`) and a "last seen" note. ALERT pings get a distinct 🚨 red embed via `sendCriticalAlert(...)`. `sendDailyDigest(...)` posts the daily summary.
- **`repository/SqlFlightRepository`** — JDBC + HikariCP. Supports SQLite (default) and MySQL via `db.type`. Uses `INSERT OR IGNORE` semantics keyed on `UNIQUE(flight_number, scheduled_time)`. `findLastSeen(...)` powers the "last seen N days ago" embed note.
- **`config/ConfigManager`** — singleton; getters are Lombok-generated (`@Getter`). Tests can call `ConfigManager.reset()` to drop the cached instance.
- **`model/Flight`** — Java `record`. `getUniqueKey()` is `flightNumber + "_" + scheduledTime` and is what the cooldown set keys on.
- **`web/WebServer`** — JDK `com.sun.net.httpserver.HttpServer` (no extra dep). Routes: `GET /` HTML dashboard (dark mode, live Leaflet map, auto-refresh 30s, last 50 noteworthy); `GET /api/live` JSON of currently-tracked aircraft (lat/lon/flag, powers the map); `GET /api/flights` historical JSON with `?since=<ISO-datetime>` and `?tier=all|noteworthy|alert`; `GET /metrics` Prometheus text; `GET /health` JSON health/uptime; `GET /api/debug/digest` triggers a digest post (testing, wired via `setDigestTrigger`). Tier filtering re-classifies at response time via `AircraftTypes.classify` + `AircraftAlerter` — no tier column in the DB.
- **`adsb/ADSBStatsProvider`** — `@FunctionalInterface` passed from `ADSBFlightTracker` to `WebServer` so the HTTP layer reads stats without knowing about `Dump1090DataSource`.
- **`notification/DiscordStats`** — immutable record (notificationsSent, alertsSent, coalescedCount, lastSendAt) produced by `DiscordFlightNotifier.getDiscordStats()` for `/metrics`.

## State and Side-Effect Surface

These exist outside the Maven build and are not in `target/`:

- `flights.db` — persisted flight records (5-min batch from the ADS-B stream).
- `aircraft_cache.db` — enrichment cache (one row per hex, never expires).
- `flightscanner.env` — Discord webhook URL, mode 600.
- `~/.config/systemd/user/flightscanner.service` — systemd unit template (copy from repo).

`*.db` and `*.env` are gitignored. Don't commit them, and don't delete them unless you intend to reset state — `aircraft_cache.db` in particular represents many adsb.lol API calls.

## Working in this Repo

- After editing code, rebuild: `mvn package -DskipTests -q` and restart the service if running under systemd.
- When changing ICAO type-based classification, edit only `AircraftTypes.java` — both `LocalAircraftAnalyzer` and `DiscordFlightNotifier` read from the shared `AircraftCategory` enum.
- When changing ALERT thresholds, edit only `application.properties` — all trigger types (squawk, altitude, hex range, operator keyword, watchlist hex) are config-driven and read by `AircraftAlerter` at construction time. To add a new trigger type, extend `AircraftAlerter`.
- Notification cooldown: NOTEWORTHY uses `notifiedFlights` (`Map<flightNumber+scheduledTime, Instant>`) in `maybeNotify`; ALERT uses `alertedFlights` (`Map<hexIdent-or-uniqueKey, Instant>`) in `maybeAlert`. `cleanupNotifiedFlights` prunes both maps hourly.
- `config.getNotifyLevel()` returns a `NotifyLevel` enum (ALL / NOTEWORTHY / ALERT). `config.isDiscordNotifyAll()` is still honored as an OR override (deprecated).
- Lombok is used (`@Slf4j`, `@Getter`). The compiler plugin is configured with the annotation processor; ensure your IDE has Lombok support enabled.
