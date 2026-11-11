package io.floci.aws.services.iotsitewise;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

import static io.floci.aws.services.iotsitewise.SiteWiseStore.GATEWAY;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.invalid;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.meta;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.notFound;

/**
 * SiteWise Edge gateways on Greengrass V2 (or V1, or Siemens Industrial Edge), and their capability configurations.
 * A core device or IoT thing hosts at most one gateway. A capability configuration is OUT_OF_SYNC from the moment it's
 * changed until a gateway reports it applied it, which no gateway running here does.
 */
@ApplicationScoped
public class SiteWiseGateways {

    private final SiteWiseStore store;
    private final ObjectMapper mapper;

    @Inject
    public SiteWiseGateways(SiteWiseStore store) {
        this.store = store;
        this.mapper = store.mapper();
    }

    private ObjectNode require(String account, String region, String id) {
        return store.get(account, region, GATEWAY, id).orElseThrow(() -> notFound("Gateway " + id + " not found."));
    }

    private static String host(JsonNode platform) {
        if (platform.has("greengrassV2")) {
            return "greengrassV2:" + platform.get("greengrassV2").get("coreDeviceThingName").asText();
        }
        if (platform.has("siemensIE")) {
            return "siemensIE:" + platform.get("siemensIE").get("iotCoreThingName").asText();
        }
        return "greengrass:" + platform.get("greengrass").get("groupArn").asText();
    }

    ObjectNode createGateway(ObjectNode in, String region) {
        String account = store.account();
        JsonNode platform = in.get("gatewayPlatform");
        if (platform.size() != 1) {
            throw invalid("A gateway platform is exactly one of greengrass, greengrassV2 and siemensIE.");
        }
        String version = in.path("gatewayVersion").asText(null);
        if (version != null && platform.has("greengrassV2") && !List.of("2", "3").contains(version)) {
            throw invalid("A Greengrass V2 gateway's version is 2 or 3.");
        }
        if (version != null && !platform.has("greengrassV2")) {
            throw invalid("Only Greengrass V2 gateways have a version.");
        }
        String host = host(platform);
        for (ObjectNode other : store.list(account, region, GATEWAY)) {
            if (host.equals(host(other.get("gatewayPlatform")))) {
                throw SiteWiseStore.exists("Gateway " + other.get("gatewayId").asText() + " already runs on "
                        + host.substring(host.indexOf(':') + 1) + ".", other.get("gatewayId").asText(),
                        other.get("gatewayArn").asText());
            }
        }
        String id = SiteWiseStore.uuid();
        double now = SiteWiseStore.now();
        ObjectNode gateway = store.record();
        gateway.put("gatewayId", id);
        gateway.put("gatewayName", in.get("gatewayName").asText());
        gateway.put("gatewayArn", store.arn(account, region, GATEWAY, id));
        gateway.set("gatewayPlatform", platform.deepCopy());
        if (version != null) {
            gateway.put("gatewayVersion", version);
        }
        gateway.putArray("gatewayCapabilitySummaries");
        gateway.put("creationDate", now);
        gateway.put("lastUpdateDate", now);
        meta(gateway).set("tags", SiteWiseStore.tagsOf(mapper, in.get("tags")));
        meta(gateway).putObject("capabilities");
        store.put(account, region, GATEWAY, id, gateway);
        ObjectNode out = mapper.createObjectNode();
        out.put("gatewayId", id);
        out.put("gatewayArn", gateway.get("gatewayArn").asText());
        return out;
    }

    ObjectNode describeGateway(ObjectNode in, String region) {
        return SiteWiseStore.view(require(store.account(), region, in.get("gatewayId").asText()));
    }

    ObjectNode listGateways(ObjectNode in, String region) {
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode gateway : store.list(store.account(), region, GATEWAY)) {
            ObjectNode summary = SiteWiseStore.view(gateway);
            summary.remove("gatewayArn");
            summaries.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "gatewaySummaries");
        return out;
    }

    ObjectNode updateGateway(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode gateway = require(account, region, in.get("gatewayId").asText());
        gateway.put("gatewayName", in.get("gatewayName").asText());
        gateway.put("lastUpdateDate", SiteWiseStore.now());
        store.put(account, region, GATEWAY, gateway.get("gatewayId").asText(), gateway);
        return mapper.createObjectNode();
    }

    ObjectNode deleteGateway(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode gateway = require(account, region, in.get("gatewayId").asText());
        store.delete(account, region, GATEWAY, gateway.get("gatewayId").asText());
        return mapper.createObjectNode();
    }

    ObjectNode updateGatewayCapabilityConfiguration(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode gateway = require(account, region, in.get("gatewayId").asText());
        String namespace = in.get("capabilityNamespace").asText();
        String configuration = in.get("capabilityConfiguration").asText();
        if (namespace.startsWith("iotsitewise:")) {
            try {
                JsonNode parsed = mapper.readTree(configuration);
                if (parsed == null || !parsed.isObject()) {
                    throw invalid("The capability configuration of " + namespace + " must be a JSON object.");
                }
            } catch (JsonProcessingException e) {
                throw invalid("The capability configuration of " + namespace + " isn't valid JSON: "
                        + e.getOriginalMessage());
            }
        }
        ((ObjectNode) meta(gateway).with("capabilities")).put(namespace, configuration);
        ArrayNode summaries = (ArrayNode) gateway.withArray("gatewayCapabilitySummaries");
        ObjectNode summary = null;
        for (JsonNode existing : summaries) {
            if (namespace.equals(existing.get("capabilityNamespace").asText())) {
                summary = (ObjectNode) existing;
            }
        }
        if (summary == null) {
            summary = summaries.addObject().put("capabilityNamespace", namespace);
        }
        summary.put("capabilitySyncStatus", "OUT_OF_SYNC");
        gateway.put("lastUpdateDate", SiteWiseStore.now());
        store.put(account, region, GATEWAY, gateway.get("gatewayId").asText(), gateway);
        ObjectNode out = mapper.createObjectNode();
        out.put("capabilityNamespace", namespace);
        out.put("capabilitySyncStatus", "OUT_OF_SYNC");
        return out;
    }

    ObjectNode describeGatewayCapabilityConfiguration(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode gateway = require(account, region, in.get("gatewayId").asText());
        String namespace = in.get("capabilityNamespace").asText();
        JsonNode configuration = meta(gateway).path("capabilities").get(namespace);
        if (configuration == null) {
            throw notFound("Gateway " + gateway.get("gatewayId").asText() + " has no " + namespace
                    + " configuration.");
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("gatewayId", gateway.get("gatewayId").asText());
        out.put("capabilityNamespace", namespace);
        out.put("capabilityConfiguration", configuration.asText());
        String status = "OUT_OF_SYNC";
        for (JsonNode summary : gateway.path("gatewayCapabilitySummaries")) {
            if (namespace.equals(summary.get("capabilityNamespace").asText())) {
                status = summary.get("capabilitySyncStatus").asText();
            }
        }
        out.put("capabilitySyncStatus", status);
        return out;
    }
}
