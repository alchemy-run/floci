package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes {@code aws.codeartifact} "CodeArtifact Package Version State Change" events to the
 * domain owner's default EventBridge bus, which is where CodeArtifact delivers them.
 *
 * @see <a href="https://docs.aws.amazon.com/codeartifact/latest/ug/service-event-format-example.html">CodeArtifact event format</a>
 */
@ApplicationScoped
public class CodeArtifactEventPublisher {

    private static final Logger LOG = Logger.getLogger(CodeArtifactEventPublisher.class);
    static final String SOURCE = "aws.codeartifact";
    static final String DETAIL_TYPE = "CodeArtifact Package Version State Change";

    private final EventBridgeService eventBridgeService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public CodeArtifactEventPublisher(EventBridgeService eventBridgeService, RegionResolver regionResolver,
                                      ObjectMapper objectMapper) {
        this.eventBridgeService = eventBridgeService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    /** One change to one package version, in the terms of the event's {@code detail}. */
    public record PackageVersionChange(String region, String domain, String domainOwner, String repository,
                                       String format, String namespace, String packageName, String version,
                                       String state, String revision, String operationType,
                                       int assetsAdded, int assetsRemoved, int assetsUpdated,
                                       boolean metadataUpdated, boolean statusChanged) {}

    /**
     * Emits one change. A publishing failure is logged rather than thrown: the package operation it
     * reports has already been applied and cannot be rolled back.
     */
    public void publish(PackageVersionChange change) {
        try {
            Map<String, Object> entry = new HashMap<>();
            entry.put("Source", SOURCE);
            entry.put("DetailType", DETAIL_TYPE);
            entry.put("Detail", objectMapper.writeValueAsString(detail(change)));
            // EventBridgeService reads Resources as an ArrayNode.
            entry.put("Resources", objectMapper.createArrayNode().add(packageArn(change)));
            String callerAccount = regionResolver.getAccountId();
            String targetAccount = change.domainOwner() == null || change.domainOwner().equals(callerAccount)
                    ? null : change.domainOwner();
            eventBridgeService.putEvents(List.of(entry), change.region(), targetAccount);
        } catch (Exception e) {
            LOG.warnv("Failed to publish CodeArtifact event for {0}/{1}@{2}: {3}",
                    change.repository(), change.packageName(), change.version(), e.getMessage());
        }
    }

    ObjectNode detail(PackageVersionChange change) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("domainName", change.domain());
        detail.put("domainOwner", change.domainOwner());
        detail.put("repositoryName", change.repository());
        detail.put("repositoryAdministrator", change.domainOwner());
        detail.put("packageFormat", change.format());
        if (change.namespace() == null) {
            detail.putNull("packageNamespace");
        } else {
            detail.put("packageNamespace", change.namespace());
        }
        detail.put("packageName", change.packageName());
        detail.put("packageVersion", change.version());
        detail.put("packageVersionState", change.state());
        detail.put("packageVersionRevision", change.revision());
        ObjectNode changes = detail.putObject("changes");
        changes.put("assetsAdded", change.assetsAdded());
        changes.put("assetsRemoved", change.assetsRemoved());
        changes.put("metadataUpdated", change.metadataUpdated());
        changes.put("assetsUpdated", change.assetsUpdated());
        changes.put("statusChanged", change.statusChanged());
        detail.put("operationType", change.operationType());
        detail.put("eventDeduplicationId", deduplicationId(change));
        return detail;
    }

    String packageArn(PackageVersionChange change) {
        return "arn:aws:codeartifact:" + change.region() + ":" + change.domainOwner() + ":package/"
                + change.domain() + "/" + change.repository() + "/" + change.format() + "/"
                + (change.namespace() == null ? "" : change.namespace()) + "/" + change.packageName();
    }

    private static String deduplicationId(PackageVersionChange change) {
        String identity = String.join("|", change.region(), change.domainOwner(), change.domain(),
                change.repository(), change.format(), String.valueOf(change.namespace()), change.packageName(),
                change.version(), String.valueOf(change.revision()), change.state(), change.operationType(),
                UUID.randomUUID().toString());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
