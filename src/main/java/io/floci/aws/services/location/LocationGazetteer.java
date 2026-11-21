package io.floci.aws.services.location;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.VendoredResources;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Floci's gazetteer of world cities and landmarks ({@code location/places.json}), searched the
 * way a geocoder is: every word of the query must match a place's name, its city, region or
 * country, or a category word ({@code coffee}, {@code museum}, …), and matches near the bias
 * position rank first. A query that names a known city after a street address ({@code 100 Main St,
 * Seattle}) is answered with an interpolated address in that city.
 */
final class LocationGazetteer {

    /** A gazetteer entry, or an interpolated address built from one. */
    record Place(int id, String name, List<String> categories, double[] position, String addressNumber,
                 String street, String neighborhood, String municipality, String region, String regionCode,
                 String country, String countryName, String postalCode, String timeZone, int offset,
                 boolean interpolated) {

        boolean isCity() {
            return categories.contains("MunicipalityType");
        }

        /** {@code Space Needle, 400 Broad St, Seattle, WA 98109, USA}, as Esri labels a place. */
        String label() {
            List<String> parts = new ArrayList<>();
            if (!isCity() && !interpolated) {
                parts.add(name);
            }
            String address = ((addressNumber == null ? "" : addressNumber + " ") + (street == null ? "" : street)).trim();
            if (!address.isEmpty()) {
                parts.add(address);
            }
            parts.add(municipality);
            parts.add(regionCode + (postalCode == null || isCity() ? "" : " " + postalCode));
            parts.add(country);
            return String.join(", ", parts);
        }
    }

    /** A place that matched, with its relevance (0 to 1) and distance from the bias position. */
    record Match(Place place, double relevance, Double distance) {}

    static final Map<String, String> ISO2 = Map.ofEntries(
            Map.entry("USA", "US"), Map.entry("CAN", "CA"), Map.entry("MEX", "MX"), Map.entry("BRA", "BR"),
            Map.entry("ARG", "AR"), Map.entry("CHL", "CL"), Map.entry("COL", "CO"), Map.entry("PER", "PE"),
            Map.entry("GBR", "GB"), Map.entry("IRL", "IE"), Map.entry("FRA", "FR"), Map.entry("DEU", "DE"),
            Map.entry("NLD", "NL"), Map.entry("BEL", "BE"), Map.entry("ESP", "ES"), Map.entry("PRT", "PT"),
            Map.entry("ITA", "IT"), Map.entry("CHE", "CH"), Map.entry("AUT", "AT"), Map.entry("CZE", "CZ"),
            Map.entry("POL", "PL"), Map.entry("SWE", "SE"), Map.entry("NOR", "NO"), Map.entry("DNK", "DK"),
            Map.entry("FIN", "FI"), Map.entry("GRC", "GR"), Map.entry("TUR", "TR"), Map.entry("RUS", "RU"),
            Map.entry("EGY", "EG"), Map.entry("NGA", "NG"), Map.entry("KEN", "KE"), Map.entry("ZAF", "ZA"),
            Map.entry("ARE", "AE"), Map.entry("SAU", "SA"), Map.entry("ISR", "IL"), Map.entry("IND", "IN"),
            Map.entry("SGP", "SG"), Map.entry("THA", "TH"), Map.entry("IDN", "ID"), Map.entry("MYS", "MY"),
            Map.entry("PHL", "PH"), Map.entry("HKG", "HK"), Map.entry("CHN", "CN"), Map.entry("TWN", "TW"),
            Map.entry("KOR", "KR"), Map.entry("JPN", "JP"), Map.entry("AUS", "AU"), Map.entry("NZL", "NZ"));

    /** Query words that ask for a category rather than a name. */
    private static final Map<String, String> CATEGORY_WORDS = Map.ofEntries(
            Map.entry("coffee", "CoffeeShopType"), Map.entry("cafe", "CoffeeShopType"),
            Map.entry("cafes", "CoffeeShopType"), Map.entry("museum", "MuseumType"),
            Map.entry("museums", "MuseumType"), Map.entry("park", "ParkType"), Map.entry("parks", "ParkType"),
            Map.entry("airport", "AirportType"), Map.entry("hotel", "HotelType"), Map.entry("hotels", "HotelType"),
            Map.entry("landmark", "LandmarkType"), Map.entry("attraction", "LandmarkType"),
            Map.entry("shopping", "ShoppingType"), Map.entry("market", "ShoppingType"),
            Map.entry("city", "MunicipalityType"));

    private static final Set<String> NOISE = Set.of("the", "of", "in", "near", "at", "and", "shop", "shops", "usa");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern STREET_ADDRESS = Pattern.compile("^\\s*(\\d+[A-Za-z]?)\\s+([^,]+?)\\s*(?:,|$)");
    private static final String ID_PREFIX = "floci:place:";

    private static final List<Place> PLACES = load();

    private LocationGazetteer() {
    }

    private static List<Place> load() {
        JsonNode root = VendoredResources.read("location/places.json", "Location gazetteer",
                in -> new ObjectMapper().readTree(in));
        List<Place> places = new ArrayList<>();
        for (JsonNode p : root.path("places")) {
            List<String> categories = new ArrayList<>();
            p.path("categories").forEach(c -> categories.add(c.asText()));
            places.add(new Place(p.path("id").asInt(), p.path("name").asText(), List.copyOf(categories),
                    new double[]{p.path("lon").asDouble(), p.path("lat").asDouble()}, text(p, "addressNumber"),
                    text(p, "street"), text(p, "neighborhood"), p.path("municipality").asText(),
                    p.path("region").asText(), p.path("regionCode").asText(), p.path("country").asText(),
                    p.path("countryName").asText(), text(p, "postalCode"), p.path("timeZone").asText(),
                    p.path("offset").asInt(), false));
        }
        return List.copyOf(places);
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isEmpty() ? null : value;
    }

    // ── Identity ───────────────────────────────────────────────────────────

    static String placeId(Place place) {
        String id = place.interpolated()
                ? ID_PREFIX + place.id() + ":" + place.addressNumber() + ":" + place.street()
                : ID_PREFIX + place.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(id.getBytes(StandardCharsets.UTF_8));
    }

    static Optional<Place> byPlaceId(String placeId) {
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(placeId), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!decoded.startsWith(ID_PREFIX)) {
            return Optional.empty();
        }
        String[] parts = decoded.substring(ID_PREFIX.length()).split(":", 3);
        int id;
        try {
            id = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (id < 0 || id >= PLACES.size()) {
            return Optional.empty();
        }
        Place place = PLACES.get(id);
        return Optional.of(parts.length == 3 ? interpolate(place, parts[1], parts[2]) : place);
    }

    // ── Search ─────────────────────────────────────────────────────────────

    /** Places matching {@code text}, best first: relevance, then distance from {@code bias}. */
    static List<Match> search(String text, double[] bias, double[] bbox, List<String> countries,
                              List<String> categories, int max) {
        List<Match> matches = new ArrayList<>();
        List<String> words = words(text);
        if (words.isEmpty()) {
            return matches;
        }
        Matcher address = STREET_ADDRESS.matcher(text);
        for (Place place : PLACES) {
            double relevance = relevance(place, words);
            if (relevance > 0) {
                matches.add(new Match(place, relevance, bias == null ? null : LocationGeometry.distance(bias,
                        place.position())));
            }
        }
        if (address.find()) {
            String number = address.group(1);
            String street = address.group(2).trim();
            List<String> rest = words(text.substring(address.end()));
            Place city = PLACES.stream().filter(Place::isCity)
                    .filter(c -> !rest.isEmpty() && words(c.name()).stream().allMatch(rest::contains))
                    .min(Comparator.comparingDouble(c -> bias == null ? 0 : LocationGeometry.distance(bias,
                            c.position())))
                    .orElse(null);
            if (city != null) {
                Place interpolated = interpolate(city, number, street);
                matches.addFirst(new Match(interpolated, 1.0, bias == null ? null
                        : LocationGeometry.distance(bias, interpolated.position())));
            }
        }
        return filter(matches, bbox, countries, categories).stream()
                .sorted(Comparator.comparingDouble((Match m) -> -m.relevance())
                        .thenComparingDouble(m -> m.distance() == null ? 0 : m.distance()))
                .limit(max).toList();
    }

    /** The places nearest {@code position}, nearest first. */
    static List<Match> nearest(double[] position, double radiusMeters, List<String> categories, int max) {
        return PLACES.stream()
                .map(place -> new Match(place, 1.0, LocationGeometry.distance(position, place.position())))
                .filter(m -> m.distance() <= radiusMeters)
                .filter(m -> categories == null || categories.isEmpty()
                        || m.place().categories().stream().anyMatch(categories::contains))
                .sorted(Comparator.comparingDouble(Match::distance))
                .limit(max).toList();
    }

    /** The place whose address best describes {@code position}: the nearest landmark within 250 m, else the nearest city. */
    static Match reverse(double[] position) {
        Match nearest = nearest(position, Double.MAX_VALUE, null, PLACES.size()).stream()
                .filter(m -> !m.place().isCity() && m.distance() <= 250).findFirst().orElse(null);
        if (nearest != null) {
            return nearest;
        }
        return nearest(position, Double.MAX_VALUE, List.of("MunicipalityType"), 1).getFirst();
    }

    private static List<Match> filter(List<Match> matches, double[] bbox, List<String> countries,
                                      List<String> categories) {
        return matches.stream()
                .filter(m -> bbox == null || m.place().position()[0] >= bbox[0] && m.place().position()[1] >= bbox[1]
                        && m.place().position()[0] <= bbox[2] && m.place().position()[1] <= bbox[3])
                .filter(m -> countries == null || countries.isEmpty() || countries.contains(m.place().country())
                        || countries.contains(ISO2.get(m.place().country())))
                .filter(m -> categories == null || categories.isEmpty()
                        || m.place().categories().stream().anyMatch(categories::contains))
                .toList();
    }

    /** The share of the query's words the place answers; 0 unless every word is answered. */
    private static double relevance(Place place, List<String> words) {
        List<String> name = words(place.name());
        List<String> context = new ArrayList<>(words(place.municipality()));
        context.addAll(words(place.region()));
        context.addAll(words(place.regionCode()));
        context.addAll(words(place.countryName()));
        context.addAll(words(place.country()));
        String isoTwo = ISO2.get(place.country());
        if (isoTwo != null) {
            context.add(isoTwo.toLowerCase(Locale.ROOT));
        }
        List<String> address = new ArrayList<>(words(place.addressNumber()));
        address.addAll(words(place.street()));
        int nameHits = 0;
        for (String word : words) {
            boolean inName = name.stream().anyMatch(n -> n.startsWith(word))
                    || address.stream().anyMatch(a -> a.startsWith(word));
            boolean inContext = context.contains(word);
            String category = CATEGORY_WORDS.get(word);
            boolean inCategory = category != null && place.categories().contains(category);
            if (!inName && !inContext && !inCategory) {
                return 0;
            }
            if (inName || inCategory) {
                nameHits++;
            }
        }
        if (nameHits == 0) {
            return 0;
        }
        double nameCoverage = (double) name.stream().filter(n -> words.stream().anyMatch(n::startsWith)).count()
                / Math.max(1, name.size());
        return Math.min(1.0, 0.5 + 0.5 * nameCoverage);
    }

    private static Place interpolate(Place city, String number, String street) {
        double offset = (Math.abs((number + street).hashCode()) % 1000) / 100000.0;
        return new Place(city.id(), number + " " + street, List.of("AddressType"),
                new double[]{LocationGeometry.round(city.position()[0] + offset),
                        LocationGeometry.round(city.position()[1] - offset)},
                number, street, null, city.municipality(), city.region(), city.regionCode(), city.country(),
                city.countryName(), city.postalCode(), city.timeZone(), city.offset(), true);
    }

    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        if (text == null) {
            return words;
        }
        Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            if (!NOISE.contains(matcher.group())) {
                words.add(matcher.group());
            }
        }
        return words;
    }

    /** Suggestions for partial {@code text}: matching place labels, or the category in the nearest city. */
    static List<String> suggestions(String text, double[] bias, int max) {
        List<String> found = new ArrayList<>();
        for (Match match : search(text, bias, null, null, null, max)) {
            found.add(match.place().label());
        }
        if (found.isEmpty() && !words(text).isEmpty()) {
            Place city = bias == null ? null
                    : nearest(bias, Double.MAX_VALUE, List.of("MunicipalityType"), 1).getFirst().place();
            found.add(text.trim() + (city == null ? "" : ", " + city.municipality() + ", " + city.regionCode()
                    + ", " + city.country()));
        }
        return found;
    }
}
