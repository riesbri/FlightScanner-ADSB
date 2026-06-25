# Contributing to FlightScanner

Thanks for taking an interest. Contributions are welcome — bug fixes, new aircraft type codes, additional airport coordinates, and documentation improvements are all good starting points.

## Requirements

- Java 21 (Temurin recommended)
- Maven 3.8+
- A `dump1090-fa` instance on `localhost:30003` for manual integration testing (not required just to run the unit tests)

## Build and test

```bash
# Compile and run the full test suite (111 tests, no external services needed)
JAVA_HOME=/path/to/java-21 mvn test

# Build the fat JAR
JAVA_HOME=/path/to/java-21 mvn package -DskipTests -q
```

All 111 tests must pass before submitting a PR. CI will verify this automatically.

## Key files to know

| File | Purpose |
|---|---|
| `src/main/java/com/flightscanner/analyzer/AircraftTypes.java` | Single source of truth for ICAO type classification — edit here to add aircraft types |
| `src/main/java/com/flightscanner/geo/AirportCoords.java` | Hardcoded airport table — edit here to add airports |
| `src/main/resources/application.properties` | All runtime defaults |
| `src/main/java/com/flightscanner/analyzer/AircraftAlerter.java` | ALERT-tier trigger logic |

## Submitting a PR

1. Fork the repo and create a branch from `main`.
2. Make your change. If it touches behaviour, add or update a test.
3. Run `mvn test` — all tests must pass.
4. Open a pull request against `main` with a clear description of what changed and why.

Please keep PRs focused: one logical change per PR makes review faster.

## What not to submit

- Changes to `flightscanner.env` or `*.db` files (gitignored, local only)
- AI-generated content without manual review
- New runtime dependencies without prior discussion
