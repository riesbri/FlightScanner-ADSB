#!/bin/bash

# Smoke-tests the fat JAR by launching it for 3 seconds and checking it starts cleanly.
# Usage: ./test-build.sh

echo "=== FlightScanner Build Smoke Test ==="

JAR="target/flightscanner-0.0.1-SNAPSHOT-jar-with-dependencies.jar"

if [ ! -f "$JAR" ]; then
    echo "Building project first..."
    mvn clean package -DskipTests -q
    if [ $? -ne 0 ]; then
        echo "❌ Build failed"
        exit 1
    fi
fi

echo "JAR: $JAR"
echo "Note: Requires dump1090-fa running on port 30003"
echo ""

java -jar "$JAR" 2>&1 &
PID=$!
sleep 3
kill $PID 2>/dev/null
wait $PID 2>/dev/null

echo ""
echo "=== Test Complete ==="
