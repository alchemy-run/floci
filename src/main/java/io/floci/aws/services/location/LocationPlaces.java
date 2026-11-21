package io.floci.aws.services.location;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.location.LocationGazetteer.Match;
import io.floci.aws.services.location.LocationGazetteer.Place;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The place APIs of Location (place indexes) and Geo Places over {@link LocationGazetteer}.
 * PlaceIds are only returned by HERE and Grab place indexes in v1, as on AWS; Geo Places always
 * returns them.
 */
@ApplicationScoped
public class LocationPlaces {
    private static final Map<String, String[]> CATEGORIES = Map.of(
            "CoffeeShopType", new String[]{"coffee_shop", "Coffee Shop"},
            "MuseumType", new String[]{"museum", "Museum"},
            "ParkType", new String[]{"park-recreation_area", "Park-Recreation Area"},
            "AirportType", new String[]{"airport", "Airport"},
            "HotelType", new String[]{"hotel", "Hotel"},
            "LandmarkType", new String[]{"landmark-attraction", "Landmark-Attraction"},
            "ShoppingType", new String[]{"shopping_mall", "Shopping Mall"});

    private final ObjectMapper mapper;

    @Inject
    public LocationPlaces(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ── Location v1 ────────────────────────────────────────────────────────

    public ObjectNode searchText(ObjectNode index, JsonNode request) {
        String text = text(request, "Text");
        double[] bias = optionalPosition(request.path("BiasPosition"), "BiasPosition");
        double[] bbox = optionalBox(request.path("FilterBBox"));
        if (bias != null && bbox != null) {
            throw LocationService.validation("BiasPosition and FilterBBox can't both be given.");
        }
        int max = request.path("MaxResults").asInt(50);
        if (max < 1 || max > 50) {
            throw LocationService.validation("MaxResults must be between 1 and 50.");
        }
        List<Match> matches = LocationGazetteer.search(text, bias, bbox, strings(request.path("FilterCountries")),
                strings(request.path("FilterCategories")), max);
        boolean ids = !"Esri".equals(index.path("DataSource").asText());
        ObjectNode response = mapper.createObjectNode();
        ArrayNode results = response.putArray("Results");
        List<double[]> points = new ArrayList<>();
        for (Match match : matches) {
            ObjectNode result = results.addObject();
            result.set("Place", placeV1(match.place()));
            if (match.distance() != null) {
                result.put("Distance", Math.round(match.distance() * 100) / 100.0);
            }
            result.put("Relevance", match.relevance());
            if (ids) {
                result.put("PlaceId", LocationGazetteer.placeId(match.place()));
            }
            points.add(match.place().position());
        }
        ObjectNode summary = response.putObject("Summary");
        summary.put("Text", text);
        copy(request, summary, "BiasPosition", "FilterBBox", "FilterCountries", "FilterCategories", "Language");
        summary.put("MaxResults", max);
        if (!points.isEmpty()) {
            summary.set("ResultBBox", mapper.valueToTree(LocationGeometry.bbox(points)));
        }
        summary.put("DataSource", index.path("DataSource").asText());
        return response;
    }

    public ObjectNode searchPosition(ObjectNode index, JsonNode request) {
        double[] position = LocationTrackingService.position(request.path("Position"), "Position");
        int max = request.path("MaxResults").asInt(50);
        if (max < 1 || max > 50) {
            throw LocationService.validation("MaxResults must be between 1 and 50.");
        }
        boolean ids = !"Esri".equals(index.path("DataSource").asText());
        ObjectNode response = mapper.createObjectNode();
        ArrayNode results = response.putArray("Results");
        List<Match> matches = new ArrayList<>();
        matches.add(LocationGazetteer.reverse(position));
        for (Match match : LocationGazetteer.nearest(position, 5_000, null, max)) {
            if (matches.size() < max && matches.stream().noneMatch(m -> m.place().id() == match.place().id())) {
                matches.add(match);
            }
        }
        for (Match match : matches) {
            ObjectNode result = results.addObject();
            result.set("Place", placeV1(match.place()));
            result.put("Distance", Math.round(match.distance() * 100) / 100.0);
            if (ids) {
                result.put("PlaceId", LocationGazetteer.placeId(match.place()));
            }
        }
        ObjectNode summary = response.putObject("Summary");
        summary.set("Position", request.path("Position").deepCopy());
        summary.put("MaxResults", max);
        summary.put("DataSource", index.path("DataSource").asText());
        copy(request, summary, "Language");
        return response;
    }

    public ObjectNode suggestions(ObjectNode index, JsonNode request) {
        String text = text(request, "Text");
        double[] bias = optionalPosition(request.path("BiasPosition"), "BiasPosition");
        int max = request.path("MaxResults").asInt(5);
        if (max < 1 || max > 15) {
            throw LocationService.validation("MaxResults must be between 1 and 15.");
        }
        boolean ids = !"Esri".equals(index.path("DataSource").asText());
        ObjectNode response = mapper.createObjectNode();
        ArrayNode results = response.putArray("Results");
        List<Match> matches = LocationGazetteer.search(text, bias, optionalBox(request.path("FilterBBox")),
                strings(request.path("FilterCountries")), strings(request.path("FilterCategories")), max);
        for (Match match : matches) {
            ObjectNode result = results.addObject();
            result.put("Text", match.place().label());
            if (ids) {
                result.put("PlaceId", LocationGazetteer.placeId(match.place()));
            }
            result.set("Categories", mapper.valueToTree(match.place().categories()));
        }
        if (matches.isEmpty()) {
            LocationGazetteer.suggestions(text, bias, max).forEach(s -> results.addObject().put("Text", s));
        }
        ObjectNode summary = response.putObject("Summary");
        summary.put("Text", text);
        copy(request, summary, "BiasPosition", "FilterBBox", "FilterCountries", "FilterCategories", "Language");
        summary.put("MaxResults", max);
        summary.put("DataSource", index.path("DataSource").asText());
        return response;
    }

    public ObjectNode getPlace(ObjectNode index, String placeId) {
        if ("Esri".equals(index.path("DataSource").asText())) {
            throw LocationService.validation("GetPlace is not available for place indexes that use Esri data.");
        }
        Place place = LocationGazetteer.byPlaceId(placeId).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "The place " + placeId + " was not found.", 404));
        ObjectNode response = mapper.createObjectNode();
        response.set("Place", placeV1(place));
        return response;
    }

    private ObjectNode placeV1(Place place) {
        ObjectNode node = mapper.createObjectNode();
        node.put("Label", place.label());
        node.putObject("Geometry").set("Point", point(place.position()));
        putIfPresent(node, "AddressNumber", place.addressNumber());
        putIfPresent(node, "Street", place.street());
        putIfPresent(node, "Neighborhood", place.neighborhood());
        node.put("Municipality", place.municipality());
        node.put("Region", place.region());
        node.put("Country", place.country());
        putIfPresent(node, "PostalCode", place.postalCode());
        node.put("Interpolated", place.interpolated());
        node.putObject("TimeZone").put("Name", place.timeZone()).put("Offset", place.offset());
        node.set("Categories", mapper.valueToTree(place.categories()));
        return node;
    }

    // ── Geo Places (v2) ────────────────────────────────────────────────────

    public ObjectNode geocode(JsonNode request) {
        String query = request.path("QueryText").asText(null);
        JsonNode components = request.path("QueryComponents");
        if (query == null && components.isObject()) {
            List<String> parts = new ArrayList<>();
            for (String field : List.of("AddressNumber", "Street", "District", "Locality", "SubRegion", "Region",
                    "PostalCode", "Country")) {
                if (components.hasNonNull(field)) {
                    parts.add(components.get(field).asText());
                }
            }
            query = String.join(", ", parts);
        }
        if (query == null || query.isBlank()) {
            throw LocationService.validation("QueryText or QueryComponents is required.");
        }
        int max = request.path("MaxResults").asInt(20);
        double[] bias = optionalPosition(request.path("BiasPosition"), "BiasPosition");
        List<String> countries = strings(request.path("Filter").path("IncludeCountries"));
        List<String> types = strings(request.path("Filter").path("IncludePlaceTypes"));
        ObjectNode response = mapper.createObjectNode();
        ArrayNode items = response.putArray("ResultItems");
        for (Match match : LocationGazetteer.search(query, bias, null, countries, null, max)) {
            if (types.isEmpty() || types.contains(placeType(match.place()))) {
                ObjectNode item = placeV2(match.place(), match.distance());
                item.putObject("MatchScores").put("Overall", match.relevance());
                items.add(item);
            }
        }
        return response;
    }

    public ObjectNode reverseGeocode(JsonNode request) {
        double[] position = LocationTrackingService.position(request.path("QueryPosition"), "QueryPosition");
        int max = request.path("MaxResults").asInt(1);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode items = response.putArray("ResultItems");
        Match match = LocationGazetteer.reverse(position);
        long radius = request.path("QueryRadius").asLong(Long.MAX_VALUE);
        if (match.distance() <= radius) {
            items.add(placeV2(match.place(), match.distance()));
        }
        for (Match near : LocationGazetteer.nearest(position, Math.min(radius, 5_000), null, max)) {
            if (items.size() >= max) {
                break;
            }
            if (near.place().id() != match.place().id()) {
                items.add(placeV2(near.place(), near.distance()));
            }
        }
        return response;
    }

    public ObjectNode searchText(JsonNode request) {
        String query = request.path("QueryText").asText(null);
        String queryId = request.path("QueryId").asText(null);
        if ((query == null) == (queryId == null)) {
            throw LocationService.validation("Specify exactly one of QueryText and QueryId.");
        }
        if (queryId != null) {
            query = decodeQueryId(queryId);
        }
        double[] bias = optionalPosition(request.path("BiasPosition"), "BiasPosition");
        JsonNode filter = request.path("Filter");
        double[] bbox = optionalBox(filter.path("BoundingBox"));
        if (filter.has("Circle")) {
            bias = LocationTrackingService.position(filter.path("Circle").path("Center"), "Filter.Circle.Center");
        }
        if (bias == null && bbox == null) {
            throw LocationService.validation("SearchText needs a BiasPosition, a Filter.BoundingBox or a Filter.Circle.");
        }
        int max = request.path("MaxResults").asInt(20);
        double circleRadius = filter.path("Circle").path("Radius").asDouble(Double.MAX_VALUE);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode items = response.putArray("ResultItems");
        for (Match match : LocationGazetteer.search(query, bias, bbox, strings(filter.path("IncludeCountries")), null,
                max)) {
            if (match.distance() == null || match.distance() <= circleRadius) {
                items.add(placeV2(match.place(), match.distance()));
            }
        }
        return response;
    }

    public ObjectNode searchNearby(JsonNode request) {
        double[] position = LocationTrackingService.position(request.path("QueryPosition"), "QueryPosition");
        long radius = request.path("QueryRadius").asLong(5_000);
        int max = request.path("MaxResults").asInt(20);
        JsonNode filter = request.path("Filter");
        List<String> include = strings(filter.path("IncludeCategories"));
        List<String> exclude = strings(filter.path("ExcludeCategories"));
        ObjectNode response = mapper.createObjectNode();
        ArrayNode items = response.putArray("ResultItems");
        for (Match match : LocationGazetteer.nearest(position, radius, null, 1000)) {
            if (match.place().isCity()) {
                continue;
            }
            List<String> ids = categoryIds(match.place());
            if (!include.isEmpty() && ids.stream().noneMatch(include::contains)
                    || ids.stream().anyMatch(exclude::contains)) {
                continue;
            }
            items.add(placeV2(match.place(), match.distance()));
            if (items.size() >= max) {
                break;
            }
        }
        return response;
    }

    public ObjectNode autocomplete(JsonNode request) {
        String query = text(request, "QueryText");
        double[] bias = optionalPosition(request.path("BiasPosition"), "BiasPosition");
        int max = request.path("MaxResults").asInt(5);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode items = response.putArray("ResultItems");
        for (Match match : LocationGazetteer.search(query, bias, optionalBox(request.path("Filter").path("BoundingBox")),
                strings(request.path("Filter").path("IncludeCountries")), null, max)) {
            ObjectNode item = items.addObject();
            item.put("PlaceId", LocationGazetteer.placeId(match.place()));
            item.put("PlaceType", placeType(match.place()));
            item.put("Title", match.place().label());
            item.set("Address", address(match.place()));
            if (match.distance() != null) {
                item.put("Distance", Math.round(match.distance()));
            }
        }
        return response;
    }

    public ObjectNode suggest(JsonNode request) {
        String query = text(request, "QueryText");
        double[] bias = optionalPosition(request.path("BiasPosition"), "BiasPosition");
        if (bias == null && !request.path("Filter").has("BoundingBox") && !request.path("Filter").has("Circle")) {
            throw LocationService.validation("Suggest needs a BiasPosition, a Filter.BoundingBox or a Filter.Circle.");
        }
        int max = request.path("MaxResults").asInt(20);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode items = response.putArray("ResultItems");
        List<Match> matches = LocationGazetteer.search(query, bias, optionalBox(request.path("Filter").path("BoundingBox")),
                strings(request.path("Filter").path("IncludeCountries")), null, max);
        for (Match match : matches) {
            ObjectNode item = items.addObject();
            item.put("Title", match.place().isCity() ? match.place().label() : match.place().name());
            item.put("SuggestResultItemType", "Place");
            ObjectNode place = placeV2(match.place(), match.distance());
            place.remove("Title");
            item.set("Place", place);
        }
        if (matches.isEmpty() || LocationGazetteer.words(query).stream().anyMatch(w -> w.length() >= 3)) {
            ObjectNode item = items.addObject();
            item.put("Title", query.trim());
            item.put("SuggestResultItemType", "Query");
            item.putObject("Query").put("QueryId", encodeQueryId(query.trim())).put("QueryType", "Category");
        }
        response.putArray("QueryRefinements");
        return response;
    }

    public ObjectNode getPlaceV2(String placeId) {
        Place place = LocationGazetteer.byPlaceId(placeId).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "The place " + placeId + " was not found.", 404));
        ObjectNode item = placeV2(place, null);
        item.remove("Distance");
        return item;
    }

    private ObjectNode placeV2(Place place, Double distance) {
        ObjectNode item = mapper.createObjectNode();
        item.put("PlaceId", LocationGazetteer.placeId(place));
        item.put("PlaceType", placeType(place));
        item.put("Title", place.isCity() || place.interpolated() ? place.label() : place.name());
        item.set("Address", address(place));
        item.set("Position", point(place.position()));
        if (distance != null) {
            item.put("Distance", Math.round(distance));
        }
        double span = place.isCity() ? 0.1 : 0.002;
        item.set("MapView", mapper.createArrayNode().add(LocationGeometry.round(place.position()[0] - span))
                .add(LocationGeometry.round(place.position()[1] - span))
                .add(LocationGeometry.round(place.position()[0] + span))
                .add(LocationGeometry.round(place.position()[1] + span)));
        ArrayNode categories = item.putArray("Categories");
        boolean primary = true;
        for (String type : place.categories()) {
            String[] category = CATEGORIES.get(type);
            if (category != null) {
                categories.addObject().put("Id", category[0]).put("Name", category[1]).put("Primary", primary);
                primary = false;
            }
        }
        if (categories.isEmpty()) {
            item.remove("Categories");
        }
        int hours = place.offset() / 3600;
        int minutes = Math.abs(place.offset() % 3600) / 60;
        item.putObject("TimeZone").put("Name", place.timeZone())
                .put("Offset", String.format(Locale.ROOT, "%s%02d:%02d", place.offset() < 0 ? "-" : "+",
                        Math.abs(hours), minutes))
                .put("OffsetSeconds", place.offset());
        return item;
    }

    private ObjectNode address(Place place) {
        ObjectNode address = mapper.createObjectNode();
        address.put("Label", place.label());
        ObjectNode country = address.putObject("Country");
        String code2 = LocationGazetteer.ISO2.get(place.country());
        if (code2 != null) {
            country.put("Code2", code2);
        }
        country.put("Code3", place.country());
        country.put("Name", place.countryName());
        address.putObject("Region").put("Code", place.regionCode()).put("Name", place.region());
        address.put("Locality", place.municipality());
        putIfPresent(address, "District", place.neighborhood());
        putIfPresent(address, "PostalCode", place.postalCode());
        putIfPresent(address, "Street", place.street());
        putIfPresent(address, "AddressNumber", place.addressNumber());
        return address;
    }

    private static String placeType(Place place) {
        if (place.interpolated()) {
            return "InterpolatedAddress";
        }
        return place.isCity() ? "Locality" : "PointOfInterest";
    }

    private static List<String> categoryIds(Place place) {
        List<String> ids = new ArrayList<>();
        for (String type : place.categories()) {
            String[] category = CATEGORIES.get(type);
            if (category != null) {
                ids.add(category[0]);
            }
        }
        return ids;
    }

    private static String encodeQueryId(String query) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("floci:query:" + query).getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeQueryId(String queryId) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(queryId),
                    StandardCharsets.UTF_8);
            if (decoded.startsWith("floci:query:")) {
                return decoded.substring("floci:query:".length());
            }
        } catch (IllegalArgumentException e) {
            // fall through
        }
        throw LocationService.validation("The QueryId " + queryId + " is not valid.");
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static String text(JsonNode request, String member) {
        String text = request.path(member).asText(null);
        if (text == null || text.isBlank()) {
            throw LocationService.validation(member + " is required.");
        }
        return text;
    }

    private static double[] optionalPosition(JsonNode node, String member) {
        return node.isMissingNode() || node.isNull() ? null : LocationTrackingService.position(node, member);
    }

    private static double[] optionalBox(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isArray() || node.size() != 4) {
            throw LocationService.validation("A bounding box is [west, south, east, north].");
        }
        return new double[]{node.get(0).asDouble(), node.get(1).asDouble(), node.get(2).asDouble(),
                node.get(3).asDouble()};
    }

    private static List<String> strings(JsonNode list) {
        List<String> values = new ArrayList<>();
        list.forEach(v -> values.add(v.asText()));
        return values;
    }

    private static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            if (from.hasNonNull(field)) {
                to.set(field, from.get(field).deepCopy());
            }
        }
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isEmpty()) {
            node.put(field, value);
        }
    }

    private ArrayNode point(double[] p) {
        return mapper.createArrayNode().add(p[0]).add(p[1]);
    }
}
