package io.github.hectorvent.floci.services.dms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes {@code aws.dms} state-change events to the caller's default EventBridge bus, which is
 * where DMS delivers them. Only replication instances change state in Floci; it has no replication
 * tasks, so it never publishes a task state change.
 */
@ApplicationScoped
public class DmsEventPublisher {

    private static final Logger LOG = Logger.getLogger(DmsEventPublisher.class);
    private static final String SOURCE = "aws.dms";
    static final String INSTANCE_DETAIL_TYPE = "DMS Replication Instance State Change";

    private final EventBridgeService eventBridgeService;
    private final ObjectMapper objectMapper;

    @Inject
    public DmsEventPublisher(EventBridgeService eventBridgeService, ObjectMapper objectMapper) {
        this.eventBridgeService = eventBridgeService;
        this.objectMapper = objectMapper;
    }

    /**
     * Emits one replication-instance state change. A publishing failure is logged rather than
     * thrown: the instance operation it reports has already completed and cannot be rolled back.
     */
    public void publishInstanceEvent(DmsInstanceEvent event, String instanceArn, String instanceIdentifier,
                                     String region) {
        try {
            ObjectNode detail = objectMapper.createObjectNode();
            detail.put("type", "REPLICATION_INSTANCE");
            detail.put("category", event.eventBridgeCategory());
            detail.put("eventType", event.eventType());
            detail.put("eventId", event.eventId());
            detail.put("sourceId", instanceIdentifier);
            detail.put("detailMessage", event.message());
            Map<String, Object> entry = new HashMap<>();
            entry.put("Source", SOURCE);
            entry.put("DetailType", INSTANCE_DETAIL_TYPE);
            entry.put("Detail", objectMapper.writeValueAsString(detail));
            // EventBridgeService reads Resources as an ArrayNode.
            entry.put("Resources", objectMapper.createArrayNode().add(instanceArn));
            eventBridgeService.putEvents(List.of(entry), region);
        } catch (Exception e) {
            LOG.warnv("Failed to publish DMS event {0} for {1}: {2}", event.eventId(), instanceArn, e.getMessage());
        }
    }
}
