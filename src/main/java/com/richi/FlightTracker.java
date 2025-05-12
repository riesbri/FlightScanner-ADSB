package com.richi;

import com.richi.model.Flight;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;

@Slf4j
public class FlightTracker {
    private final FlightScraper scraper = new FlightScraper();
    private final AircraftAnalyzer analyzer = new AircraftAnalyzer();
    private final FlightDatabase database = new FlightDatabase();
    private final TelegramNotifier notifier = new TelegramNotifier();

    public void startTracking(String airportCode) throws Exception {
        database.initialize();

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                List<Flight> arrivals = scraper.scrapeArrivals(airportCode);
                // List<String> interestingFlights = analyzer.findWidebodyFlightsUsingAI(arrivals);

                 arrivals.forEach(f -> {
                    try {
                        if (database.saveFlight(f)) {
                            //notifier.sendAlert(f);
                            System.out.println("Flight " + f.flightNumber() +" saved!");
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
                // arrivals.stream()
                    /*    .filter(f -> interestingFlights.contains(f.flightNumber()))
                        .forEach(f -> {
                            try {
                                if (database.saveFlight(f)) {
                                    notifier.sendAlert(f);
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        });
                        */

            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 1, TimeUnit.HOURS);
    }

    public static void main(String[] args) throws Exception {
        new FlightTracker().startTracking("VLC");
    }
}
