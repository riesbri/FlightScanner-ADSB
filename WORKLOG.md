# Worklog — FlightScanner

> Java 21 ADS-B flight tracker. Listens to dump1090-fa SBS stream, enriches via adsb.lol API, notifies Discord for interesting aircraft. Also has legacy FlightRadar24 web scraper mode.

---

## Active

- [ ] Merge `fix/four-defects` to main — 31 commits ready (WebServer, alert tiers, rate limiting, airport filter, 4 defect fixes) (2026-06-16)

---

## Done

---

## Decisions

- 2026-06-07: Added WebServer (port 3006) with /metrics (Prometheus), /api/flights (JSON), and HTML dashboard. Chose built-in HTTP server over Spring Boot — lighter weight for Pi.
- 2026-06-05: Three-tier notification system (ALERT > NOTEWORTHY > ROUTINE) replaces the old binary on/off toggle. ALERT always fires; other tiers are gated by `discord.notify.level`.
- 2026-06-03: Airport proximity filter (default 100 NM radius) drops aircraft too far from configured airport before notify or persist. Reduces noise significantly.
- 2026-06-01: Replaced DeepSeek AI classifier with deterministic `AircraftCategory` classifier. Removed AI dependency entirely — faster, cheaper, deterministic.
- 2026-05-28: Discord rate limiter (default 10/min) with coalesced overflow summary every 60s. Prevents webhook rate-limit bans during peak traffic.
- 2026-04-27: Initial setup — forked from FlightRadar24 scraper, added ADS-B mode via dump1090-fa. ADS-B is now the primary runtime; web scraper is legacy.

---

## Backlog

- [ ] Add Prometheus alerting rules for no-data / low-flight-rate conditions
- [ ] Web dashboard: add filtering by tier, airport, time range
- [ ] Persist enriched aircraft type info across restarts (currently only in-memory cache + aircraft_cache.db)
- [ ] Support multiple airport configurations (proximity filter per airport)
- [ ] Grafana dashboard integration — scrape /metrics endpoint from a monitoring host
- [ ] Add `fix/discord-emoji-corrections` branch content (already merged to this branch or needs separate merge?)
- [ ] Archive or remove legacy FlightRadar24 web scraper mode if no longer used
- [ ] Consider switching from SQLite to InfluxDB for time-series flight data
