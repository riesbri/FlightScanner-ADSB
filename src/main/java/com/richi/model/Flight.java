package com.richi.model;

public record Flight(
        String flightNumber,
        String origin,
        String aircraft,
        String time
) {}