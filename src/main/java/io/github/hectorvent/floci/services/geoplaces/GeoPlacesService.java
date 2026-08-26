package io.github.hectorvent.floci.services.geoplaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Amazon Location Service Places API v2 stub (restJson1).
 *
 * <p>No live geocoding: a small catalog of well-known places is matched
 * against query text / coordinates so Alchemy GeoPlaces bindings can round-trip
 * Autocomplete, Geocode, GetPlace, ReverseGeocode, SearchNearby, SearchText
 * and Suggest against the emulator.
 */
@ApplicationScoped
public class GeoPlacesService implements Resettable {

    static final String PRICING_BUCKET = "0";

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;
    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int MAX_RESULTS_CAP = 20;

    private final ObjectMapper objectMapper;
    private final List<Place> catalog;

    @Inject
    public GeoPlacesService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.catalog = List.of(
                new Place(
                        "AQAAAFlociWhiteHouse",
                        "Address",
                        "1600 Pennsylvania Avenue NW",
                        "1600 Pennsylvania Avenue NW, Washington, DC, 20500, USA",
                        "Washington",
                        "DC",
                        "District of Columbia",
                        "US",
                        "USA",
                        "United States",
                        "20500",
                        "Pennsylvania Avenue NW",
                        "1600",
                        -77.036547,
                        38.897676,
                        List.of("pennsylvania", "white house", "washington", "1600")),
                new Place(
                        "AQAAAFlociSpaceNeedle",
                        "PointOfInterest",
                        "Space Needle",
                        "400 Broad Street, Seattle, WA, 98109, USA",
                        "Seattle",
                        "WA",
                        "Washington",
                        "US",
                        "USA",
                        "United States",
                        "98109",
                        "Broad Street",
                        "400",
                        -122.3493,
                        47.6205,
                        List.of("space needle", "seattle", "needle")),
                new Place(
                        "AQAAAFlociPikePlaceCoffee",
                        "PointOfInterest",
                        "Starbucks Reserve Roastery",
                        "1912 Pike Place, Seattle, WA, 98101, USA",
                        "Seattle",
                        "WA",
                        "Washington",
                        "US",
                        "USA",
                        "United States",
                        "98101",
                        "Pike Place",
                        "1912",
                        -122.3424,
                        47.6094,
                        List.of("coffee", "starbucks", "pike", "cafe")));
    }

    @Override
    public void clear() {
        // Catalog is immutable.
    }

    public ObjectNode autocomplete(JsonNode request) {
        String query = requireText(request, "QueryText");
        int maxResults = maxResults(request, DEFAULT_MAX_RESULTS);
        List<Place> matches = matchByQuery(query, maxResults);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("PricingBucket", PRICING_BUCKET);
        ArrayNode items = root.putArray("ResultItems");
        for (Place place : matches) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("PlaceId", place.placeId());
            item.put("PlaceType", place.placeType());
            item.put("Title", place.title());
            item.set("Address", address(place));
            items.add(item);
        }
        return root;
    }

    public ObjectNode geocode(JsonNode request) {
        String query = queryTextOrComponents(request);
        int maxResults = maxResults(request, 1);
        List<Place> matches = matchByQuery(query, maxResults);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("PricingBucket", PRICING_BUCKET);
        ArrayNode items = root.putArray("ResultItems");
        for (Place place : matches) {
            items.add(placeResult(place));
        }
        return root;
    }

    public ObjectNode getPlace(String placeId) {
        if (placeId == null || placeId.isBlank()) {
            throw new AwsException("ValidationException", "PlaceId is required.", 400);
        }
        Place place = findById(placeId)
                .orElseThrow(() -> new AwsException(
                        "ValidationException", "PlaceId is invalid.", 400));
        ObjectNode root = placeResult(place);
        root.put("PricingBucket", PRICING_BUCKET);
        return root;
    }

    public ObjectNode reverseGeocode(JsonNode request) {
        double[] position = requirePosition(request, "QueryPosition");
        int maxResults = maxResults(request, 1);
        List<Place> matches = matchByDistance(position[0], position[1], Double.MAX_VALUE, maxResults);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("PricingBucket", PRICING_BUCKET);
        ArrayNode items = root.putArray("ResultItems");
        for (Place place : matches) {
            ObjectNode item = placeResult(place);
            item.put("Distance", distanceMeters(
                    position[0], position[1], place.longitude(), place.latitude()));
            items.add(item);
        }
        return root;
    }

    public ObjectNode searchNearby(JsonNode request) {
        double[] position = requirePosition(request, "QueryPosition");
        double radius = request.path("QueryRadius").asDouble(50_000);
        if (radius <= 0) {
            throw new AwsException("ValidationException", "QueryRadius must be greater than 0.", 400);
        }
        int maxResults = maxResults(request, DEFAULT_MAX_RESULTS);
        List<Place> matches = matchByDistance(position[0], position[1], radius, maxResults);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("PricingBucket", PRICING_BUCKET);
        ArrayNode items = root.putArray("ResultItems");
        for (Place place : matches) {
            ObjectNode item = placeResult(place);
            item.put("Distance", distanceMeters(
                    position[0], position[1], place.longitude(), place.latitude()));
            items.add(item);
        }
        return root;
    }

    public ObjectNode searchText(JsonNode request) {
        String query = queryTextOrComponents(request);
        int maxResults = maxResults(request, DEFAULT_MAX_RESULTS);
        List<Place> matches = matchByQuery(query, maxResults);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("PricingBucket", PRICING_BUCKET);
        ArrayNode items = root.putArray("ResultItems");
        for (Place place : matches) {
            items.add(placeResult(place));
        }
        return root;
    }

    public ObjectNode suggest(JsonNode request) {
        String query = requireText(request, "QueryText");
        int maxResults = maxResults(request, DEFAULT_MAX_RESULTS);
        List<Place> matches = matchByQuery(query, maxResults);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("PricingBucket", PRICING_BUCKET);
        ArrayNode items = root.putArray("ResultItems");
        for (Place place : matches) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("Title", place.title());
            item.put("SuggestResultItemType", "Place");
            ObjectNode nested = objectMapper.createObjectNode();
            nested.put("PlaceId", place.placeId());
            nested.put("PlaceType", place.placeType());
            nested.set("Address", address(place));
            nested.set("Position", position(place));
            item.set("Place", nested);
            items.add(item);
        }
        return root;
    }

    private ObjectNode placeResult(Place place) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("PlaceId", place.placeId());
        item.put("PlaceType", place.placeType());
        item.put("Title", place.title());
        item.set("Address", address(place));
        item.set("Position", position(place));
        return item;
    }

    private ObjectNode address(Place place) {
        ObjectNode address = objectMapper.createObjectNode();
        address.put("Label", place.label());
        ObjectNode country = objectMapper.createObjectNode();
        country.put("Code2", place.countryCode2());
        country.put("Code3", place.countryCode3());
        country.put("Name", place.countryName());
        address.set("Country", country);
        ObjectNode region = objectMapper.createObjectNode();
        region.put("Code", place.regionCode());
        region.put("Name", place.regionName());
        address.set("Region", region);
        address.put("Locality", place.locality());
        address.put("PostalCode", place.postalCode());
        address.put("Street", place.street());
        if (place.addressNumber() != null) {
            address.put("AddressNumber", place.addressNumber());
        }
        return address;
    }

    private ArrayNode position(Place place) {
        ArrayNode pos = objectMapper.createArrayNode();
        pos.add(place.longitude());
        pos.add(place.latitude());
        return pos;
    }

    private List<Place> matchByQuery(String query, int maxResults) {
        String needle = query.toLowerCase(Locale.ROOT);
        List<Place> ranked = new ArrayList<>();
        for (Place place : catalog) {
            if (place.matches(needle)) {
                ranked.add(place);
            }
        }
        if (ranked.isEmpty()) {
            ranked.add(catalog.get(0));
        }
        if (ranked.size() > maxResults) {
            return List.copyOf(ranked.subList(0, maxResults));
        }
        return ranked;
    }

    private List<Place> matchByDistance(double longitude, double latitude, double radiusMeters, int maxResults) {
        List<Place> ranked = new ArrayList<>(catalog);
        ranked.sort(Comparator.comparingDouble(
                p -> distanceMeters(longitude, latitude, p.longitude(), p.latitude())));
        List<Place> inRadius = new ArrayList<>();
        for (Place place : ranked) {
            double distance = distanceMeters(longitude, latitude, place.longitude(), place.latitude());
            if (distance <= radiusMeters) {
                inRadius.add(place);
            }
            if (inRadius.size() >= maxResults) {
                break;
            }
        }
        if (inRadius.isEmpty()) {
            inRadius.add(ranked.get(0));
        }
        return inRadius;
    }

    private Optional<Place> findById(String placeId) {
        for (Place place : catalog) {
            if (place.placeId().equals(placeId)) {
                return Optional.of(place);
            }
        }
        return Optional.empty();
    }

    private String queryTextOrComponents(JsonNode request) {
        JsonNode queryText = request.get("QueryText");
        if (queryText != null && queryText.isTextual() && !queryText.asText().isBlank()) {
            return queryText.asText();
        }
        JsonNode components = request.get("QueryComponents");
        if (components != null && components.isObject()) {
            StringBuilder builder = new StringBuilder();
            for (String field : List.of(
                    "AddressNumber", "Street", "Locality", "Region", "PostalCode", "Country")) {
                JsonNode value = components.get(field);
                if (value != null && value.isTextual() && !value.asText().isBlank()) {
                    if (!builder.isEmpty()) {
                        builder.append(' ');
                    }
                    builder.append(value.asText());
                }
            }
            if (!builder.isEmpty()) {
                return builder.toString();
            }
        }
        throw new AwsException("ValidationException", "QueryText is required.", 400);
    }

    private String requireText(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new AwsException("ValidationException", field + " is required.", 400);
        }
        return value.asText();
    }

    private double[] requirePosition(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || !value.isArray() || value.size() < 2) {
            throw new AwsException(
                    "ValidationException", field + " must be [longitude, latitude].", 400);
        }
        return new double[] {value.get(0).asDouble(), value.get(1).asDouble()};
    }

    private int maxResults(JsonNode request, int defaultValue) {
        if (!request.has("MaxResults") || request.get("MaxResults").isNull()) {
            return defaultValue;
        }
        int maxResults = request.get("MaxResults").asInt(defaultValue);
        if (maxResults < 1) {
            throw new AwsException("ValidationException", "MaxResults must be at least 1.", 400);
        }
        return Math.min(maxResults, MAX_RESULTS_CAP);
    }

    static double distanceMeters(double lon1, double lat1, double lon2, double lat2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        return 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    record Place(
            String placeId,
            String placeType,
            String title,
            String label,
            String locality,
            String regionCode,
            String regionName,
            String countryCode2,
            String countryCode3,
            String countryName,
            String postalCode,
            String street,
            String addressNumber,
            double longitude,
            double latitude,
            List<String> keywords) {

        boolean matches(String needle) {
            if (title.toLowerCase(Locale.ROOT).contains(needle)
                    || label.toLowerCase(Locale.ROOT).contains(needle)
                    || locality.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
            for (String keyword : keywords) {
                if (needle.contains(keyword) || keyword.contains(needle)) {
                    return true;
                }
            }
            return false;
        }
    }
}
