package com.flightscanner.geo;

/**
 * Maps ICAO 24-bit hex addresses to registration country and flag emoji.
 * Covers the most common countries seen in European/Atlantic airspace.
 * Unknown ranges return empty strings (fail silently).
 */
public final class ICAOCountry {

    private record Entry(int low, int high, String flag, String name) {
        boolean contains(int val) { return val >= low && val <= high; }
    }

    // Partial table — ICAO Annex 10 / Doc 9684 allocations for major countries
    private static final Entry[] TABLE = {
        // Europe
        new Entry(0x340000, 0x37FFFF, "🇪🇸", "Spain"),
        new Entry(0x380000, 0x3BFFFF, "🇫🇷", "France"),
        new Entry(0x3C0000, 0x3FFFFF, "🇩🇪", "Germany"),
        new Entry(0x400000, 0x43FFFF, "🇬🇧", "United Kingdom"),
        new Entry(0x440000, 0x447FFF, "🇦🇹", "Austria"),
        new Entry(0x448000, 0x44FFFF, "🇧🇪", "Belgium"),
        new Entry(0x450000, 0x457FFF, "🇧🇬", "Bulgaria"),
        new Entry(0x460000, 0x467FFF, "🇨🇿", "Czech Republic"),
        new Entry(0x468000, 0x46FFFF, "🇩🇰", "Denmark"),
        new Entry(0x470000, 0x477FFF, "🇪🇪", "Estonia"),
        new Entry(0x478000, 0x47FFFF, "🇫🇮", "Finland"),
        new Entry(0x480000, 0x483FFF, "🇬🇷", "Greece"),
        new Entry(0x484000, 0x487FFF, "🇭🇺", "Hungary"),
        new Entry(0x488000, 0x48BFFF, "🇱🇻", "Latvia"),
        new Entry(0x48C000, 0x48FFFF, "🇱🇹", "Lithuania"),
        new Entry(0x4A0000, 0x4AFFFF, "🇳🇱", "Netherlands"),
        new Entry(0x4B0000, 0x4B7FFF, "🇳🇴", "Norway"),
        new Entry(0x4B8000, 0x4BFFFF, "🇵🇱", "Poland"),
        new Entry(0x4C0000, 0x4C3FFF, "🇵🇹", "Portugal"),
        new Entry(0x4CA000, 0x4CAFFE, "🇮🇪", "Ireland"),
        new Entry(0x4D0000, 0x4D7FFF, "🇷🇴", "Romania"),
        new Entry(0x4E0000, 0x4E7FFF, "🇸🇪", "Sweden"),
        new Entry(0x4E8000, 0x4EFFF,  "🇨🇭", "Switzerland"),
        new Entry(0x500000, 0x53FFFF, "🇮🇹", "Italy"),
        new Entry(0x540000, 0x547FFF, "🇭🇷", "Croatia"),
        new Entry(0x580000, 0x5BFFFF, "🇺🇦", "Ukraine"),
        new Entry(0x600000, 0x6FFFFF, "🇷🇺", "Russia"),
        // North Africa / Middle East
        new Entry(0x020000, 0x027FFF, "🇲🇦", "Morocco"),
        new Entry(0x0A0000, 0x0A7FFF, "🇩🇿", "Algeria"),
        new Entry(0x016000, 0x016FFF, "🇹🇳", "Tunisia"),
        // Asia-Pacific
        new Entry(0x7C0000, 0x7FFFFF, "🇦🇺", "Australia"),
        new Entry(0x800000, 0x83FFFF, "🇨🇳", "China"),
        new Entry(0x840000, 0x87FFFF, "🇯🇵", "Japan"),
        // Americas
        new Entry(0xA00000, 0xAFFFFF, "🇺🇸", "United States"),
        new Entry(0xC00000, 0xC3FFFF, "🇨🇦", "Canada"),
        new Entry(0xE40000, 0xE7FFFF, "🇧🇷", "Brazil"),
    };

    private ICAOCountry() {}

    /** Returns the flag emoji for the country of registration, or {@code ""} if unknown. */
    public static String flagFromHex(String hexIdent) {
        if (hexIdent == null || hexIdent.isBlank()) return "";
        try {
            int val = Integer.parseInt(hexIdent.trim(), 16);
            for (Entry e : TABLE) {
                if (e.contains(val)) return e.flag();
            }
        } catch (NumberFormatException ignored) {}
        return "";
    }

    /** Returns the country name for the hex, or {@code ""} if unknown. */
    public static String countryFromHex(String hexIdent) {
        if (hexIdent == null || hexIdent.isBlank()) return "";
        try {
            int val = Integer.parseInt(hexIdent.trim(), 16);
            for (Entry e : TABLE) {
                if (e.contains(val)) return e.name();
            }
        } catch (NumberFormatException ignored) {}
        return "";
    }
}
