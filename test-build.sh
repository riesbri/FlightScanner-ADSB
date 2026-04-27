#!/bin/bash

# Test script for FlightTracker
# Usage: ./test-build.sh [adsb|scraper]

MODE="${1:-scraper}"

echo "=== Testing FlightTracker Build ==="
echo "Mode: $MODE"
echo ""

# Check if JAR exists
if [ ! -f "target/FlightScraper-0.0.1-SNAPSHOT-jar-with-dependencies.jar" ]; then
    echo "Building project first..."
    mvn clean package -DskipTests -q
    if [ $? -ne 0 ]; then
        echo "❌ Build failed"
        exit 1
    fi
fi

echo "JAR file exists: target/FlightScraper-0.0.1-SNAPSHOT-jar-with-dependencies.jar"
echo ""

if [ "$MODE" = "adsb" ]; then
    echo "Starting ADS-B mode..."
    echo "Note: Requires dump1090-fa running on port 30003"
    echo ""
    java -jar target/FlightScraper-0.0.1-SNAPSHOT-jar-with-dependencies.jar 2>&1 &
    PID=$!
    sleep 3
    kill $PID 2>/dev/null
    wait $PID 2>/dev/null
elif [ "$MODE" = "scraper" ]; then
    echo "Starting Web Scraper mode..."
    echo "Note: Will attempt to scrape FlightRadar24 for airport VLC"
    echo ""
    java -jar target/FlightScraper-0.0.1-SNAPSHOT-jar-with-dependencies.jar 2>&1 &
    PID=$!
    sleep 3
    kill $PID 2>/dev/null
    wait $PID 2>/dev/null
else
    echo "Usage: $0 [adsb|scraper]"
    exit 1
fi

echo ""
echo "=== Test Complete ==="
