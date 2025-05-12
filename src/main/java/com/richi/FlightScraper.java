package com.richi;

import com.microsoft.playwright.*;
import com.richi.model.Flight;
import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class FlightScraper {

    public List<Flight> scrapeArrivals(String airportCode) {
        List<Flight> arrivals = new ArrayList<>();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            Page page = browser.newPage();

            page.navigate("https://www.flightradar24.com/airport/" + airportCode + "/arrivals");
            page.waitForSelector(
                    "[data-testid='airport-panel__header__name']",
                    new Page.WaitForSelectorOptions().setTimeout(15000)
            );
            List<ElementHandle> flights = page.querySelectorAll("li.airport__flight-list-item");

            for (ElementHandle flight : flights) {
                // 1. Flight Info Container
                ElementHandle flightInfo = flight.querySelector("span.text-gray-900:has(span.bg-blue-200)");
                if (flightInfo == null) {
                    System.out.println("Flight info container not found, skipping...");
                    continue;
                }

                // 2. Flight Number (safer text extraction)
                String flightNumber = (String) flightInfo.evaluate("""
                    el => el.firstChild.textContent.replace('·', '').trim()
                """);

                // 3. Aircraft Type with null check
                ElementHandle aircraftElement = flightInfo.querySelector("span.bg-blue-200");
                String aircraft = aircraftElement != null
                        ? aircraftElement.innerText().trim()
                        : "UNKNOWN";

                // 4. Origin Airport
                ElementHandle originElement = flight.querySelector("span.max-w-28");
                String origin = originElement != null
                        ? originElement.innerText().trim()
                        : "UNKNOWN";

                // 5. Time with fallback
                ElementHandle timeElement = flight.querySelector("[data-testid='base-day-period-formatter']");
                String time = timeElement != null
                        ? timeElement.innerText().replaceAll("\\s+", " ").trim()
                        : "NO TIME";

                arrivals.add(new Flight(flightNumber, origin, aircraft, time));
            }

            browser.close();
        }
        return arrivals;
    }

}