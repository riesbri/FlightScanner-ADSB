#!/bin/bash

# Test web scraper mode
echo "Starting FlightTracker Web Scraper mode..."
java -jar target/FlightScraper-0.0.1-SNAPSHOT-jar-with-dependencies.jar 2>&1 &
PID=$!

# Wait 10 seconds then kill
sleep 10
kill $PID 2>/dev/null
wait $PID 2>/dev/null

echo "Test complete"
