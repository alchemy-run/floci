package io.floci.aws.services.mediaconnect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reserved outbound bandwidth: the offerings a Region has (12-month reservations of outbound bandwidth, by the Mbps),
 * and reservations, which are {@code PROCESSING} until they start, {@code ACTIVE} until they end and then
 * {@code EXPIRED}. Also tagging, of flows and what they hold, bridges, gateways and reservations, and (as global
 * resources) router inputs, outputs and network interfaces.
 */
@ApplicationScoped
public class MediaConnectOfferings {

    private static final int[][] OFFERINGS = {{10, 12}, {25, 12}, {50, 12}, {100, 12}};
    private static final String PRICE = "0.04";
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private final MediaConnectStore store;
    private final ObjectMapper mapper;
    private final Provider<MediaConnectFlows> flows;
    private final Provider<MediaConnectBridges> bridges;
    private final Provider<MediaConnectRouters> routers;

    @Inject
    public MediaConnectOfferings(MediaConnectStore store, Provider<MediaConnectFlows> flows,
                                 Provider<MediaConnectBridges> bridges, Provider<MediaConnectRouters> routers) {
        this.store = store;
        this.mapper = store.mapper();
        this.flows = flows;
        this.bridges = bridges;
        this.routers = routers;
    }

    // ================================================================ offerings

    private List<ObjectNode> offerings(String region) {
        List<ObjectNode> offerings = new ArrayList<>();
        for (int[] offering : OFFERINGS) {
            ObjectNode item = mapper.createObjectNode();
            item.put("OfferingArn", store.arn(region, "offering:" + String.format("%016x",
                    (region + offering[0]).hashCode() & 0xffffffffL) + "-" + offering[0] + "mbps"));
            item.put("OfferingDescription", offering[1] + "-month reservation for " + offering[0]
                    + " Mbps outbound bandwidth in " + region);
            item.put("CurrencyCode", "USD");
            item.put("Duration", offering[1]);
            item.put("DurationUnits", "MONTHS");
            item.put("PricePerUnit", PRICE);
            item.put("PriceUnits", "HOURLY");
            item.putObject("ResourceSpecification").put("ReservedBitrate", offering[0])
                    .put("ResourceType", "Mbps_Outbound_Bandwidth");
            offerings.add(item);
        }
        return offerings;
    }

    private ObjectNode offering(String region, String arn) {
        for (ObjectNode offering : offerings(region)) {
            if (arn.equals(offering.path("OfferingArn").asText())) {
                return offering;
            }
        }
        throw MediaConnectStore.notFound("The offering " + arn + " was not found.");
    }

    public ObjectNode listOfferings(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        MediaConnectStore.page(offerings(region), in, out, "Offerings", 10);
        return out;
    }

    public ObjectNode describeOffering(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("Offering", offering(region, in.path("OfferingArn").asText()));
        return out;
    }

    public ObjectNode purchaseOffering(JsonNode in, String region) {
        ObjectNode offering = offering(region, in.path("OfferingArn").asText());
        String name = in.path("ReservationName").asText("");
        if (name.isBlank()) {
            throw MediaConnectStore.badRequest("A reservation needs a ReservationName.");
        }
        OffsetDateTime start;
        try {
            start = OffsetDateTime.parse(in.path("Start").asText());
        } catch (DateTimeParseException e) {
            throw MediaConnectStore.badRequest("Start is a UTC time, YYYY-MM-DDTHH:mm:ssZ.");
        }
        String id = MediaConnectStore.flowId();
        ObjectNode reservation = store.record();
        reservation.setAll(offering.deepCopy());
        reservation.put("ReservationArn", store.arn(region, "reservation:" + id));
        reservation.put("ReservationName", name);
        reservation.put("Start", UTC.format(start.withOffsetSameInstant(ZoneOffset.UTC)));
        reservation.put("End", UTC.format(start.withOffsetSameInstant(ZoneOffset.UTC).plusMonths(offering
                .path("Duration").asInt())));
        store.put(region, MediaConnectStore.RESERVATION, id, reservation);
        ObjectNode out = mapper.createObjectNode();
        out.set("Reservation", reservationView(reservation));
        return out;
    }

    private static ObjectNode reservationView(ObjectNode reservation) {
        ObjectNode view = MediaConnectStore.view(reservation);
        Instant now = Instant.now();
        Instant start = OffsetDateTime.parse(view.path("Start").asText()).toInstant();
        Instant end = OffsetDateTime.parse(view.path("End").asText()).toInstant();
        view.put("ReservationState", now.isBefore(start) ? "PROCESSING" : now.isBefore(end) ? "ACTIVE" : "EXPIRED");
        return view;
    }

    public ObjectNode describeReservation(JsonNode in, String region) {
        String arn = in.path("ReservationArn").asText();
        ObjectNode reservation = store.get(region, MediaConnectStore.RESERVATION, MediaConnectStore.idOf(arn))
                .filter(found -> arn.equals(found.path("ReservationArn").asText()))
                .orElseThrow(() -> MediaConnectStore.notFound("The reservation " + arn + " was not found."));
        ObjectNode out = mapper.createObjectNode();
        out.set("Reservation", reservationView(reservation));
        return out;
    }

    public ObjectNode listReservations(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode reservation : store.list(region, MediaConnectStore.RESERVATION)) {
            items.add(reservationView(reservation));
        }
        ObjectNode out = mapper.createObjectNode();
        MediaConnectStore.page(items, in, out, "Reservations", 10);
        return out;
    }

    // ================================================================ tags

    /** Tags live in a record's meta, or (router resources) in its visible {@code Tags} member. */
    private interface Tagged {
        ObjectNode tags();

        void save();
    }

    private Tagged tagged(String region, String arn) {
        String resource = MediaConnectStore.resourceOf(arn);
        String type = resource.contains(":") ? resource.substring(0, resource.indexOf(':')) : resource;
        switch (type) {
            case "flow", "output", "source", "entitlement" -> {
                ObjectNode target = flows.get().taggable(region, arn);
                ObjectNode flow = "flow".equals(type) ? target : flows.get().flow(region, flowArnOf(region, arn));
                return new Tagged() {
                    @Override
                    public ObjectNode tags() {
                        return MediaConnectStore.tags(target);
                    }

                    @Override
                    public void save() {
                        flows.get().saveFlowOf(region, flow);
                    }
                };
            }
            case "bridge" -> {
                ObjectNode bridge = bridges.get().bridge(region, arn);
                return simple(region, MediaConnectStore.BRIDGE, bridge);
            }
            case "gateway" -> {
                ObjectNode gateway = bridges.get().gateway(region, arn);
                return simple(region, MediaConnectStore.GATEWAY, gateway);
            }
            case "reservation" -> {
                ObjectNode reservation = store.get(region, MediaConnectStore.RESERVATION, MediaConnectStore.idOf(arn))
                        .orElseThrow(() -> MediaConnectStore.notFound("The resource " + arn + " was not found."));
                return simple(region, MediaConnectStore.RESERVATION, reservation);
            }
            default -> {
                ObjectNode router = routers.get().taggable(region, arn);
                if (router == null) {
                    throw MediaConnectStore.notFound("The resource " + arn + " was not found.");
                }
                return new Tagged() {
                    @Override
                    public ObjectNode tags() {
                        return router.withObject("/Tags");
                    }

                    @Override
                    public void save() {
                        routers.get().saveTagged(region, router);
                    }
                };
            }
        }
    }

    private String flowArnOf(String region, String arn) {
        for (ObjectNode flow : store.list(region, MediaConnectStore.FLOW)) {
            if (MediaConnectStore.idOf(flow.path("FlowArn").asText()).equals(MediaConnectStore.idOf(arn))) {
                return flow.path("FlowArn").asText();
            }
        }
        throw MediaConnectStore.notFound("The resource " + arn + " was not found.");
    }

    private Tagged simple(String region, String kind, ObjectNode record) {
        return new Tagged() {
            @Override
            public ObjectNode tags() {
                return MediaConnectStore.tags(record);
            }

            @Override
            public void save() {
                store.put(region, kind, MediaConnectStore.meta(record).path("id").asText(), record);
            }
        };
    }

    public ObjectNode tagResource(JsonNode in, String region) {
        Tagged tagged = tagged(region, in.path("ResourceArn").asText());
        ObjectNode tags = tagged.tags();
        in.path("Tags").properties().forEach(tag -> tags.put(tag.getKey(), tag.getValue().asText("")));
        if (tags.size() > 50) {
            throw MediaConnectStore.badRequest("A resource can have at most 50 tags.");
        }
        tagged.save();
        return mapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode in, String region) {
        Tagged tagged = tagged(region, in.path("ResourceArn").asText());
        ObjectNode tags = tagged.tags();
        in.path("TagKeys").forEach(key -> tags.remove(key.asText()));
        tagged.save();
        return mapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("Tags", tagged(region, in.path("ResourceArn").asText()).tags().deepCopy());
        return out;
    }

    public ObjectNode tagGlobalResource(JsonNode in, String region) {
        return tagResource(in, region);
    }

    public ObjectNode untagGlobalResource(JsonNode in, String region) {
        return untagResource(in, region);
    }

    public ObjectNode listTagsForGlobalResource(JsonNode in, String region) {
        return listTagsForResource(in, region);
    }
}
