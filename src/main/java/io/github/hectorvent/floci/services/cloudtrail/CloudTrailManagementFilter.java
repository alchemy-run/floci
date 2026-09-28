package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ResolvedServiceCatalog;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.iam.IamActionRegistry;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION - 50)
public class CloudTrailManagementFilter implements ContainerRequestFilter, ContainerResponseFilter {
    private static final Logger LOG = Logger.getLogger(CloudTrailManagementFilter.class);
    private static final String CAPTURE = CloudTrailManagementFilter.class.getName();
    private static final Pattern SCOPE = Pattern.compile("Credential=([^/]+)/[0-9]{8}/([^/]+)/([^/]+)/aws4_request");
    private static final Set<String> CONTROL_SERVICES = Set.of("cloudtrail", "ssm", "ec2", "iam", "sts",
            "cloudformation", "rds", "elasticloadbalancing", "autoscaling", "organizations", "config");
    private static final Set<String> PARAMETER_NAMES = Set.of("Name", "Names", "TrailName", "ResourceId",
            "EventDataStore", "Bucket", "Type", "Overwrite", "FunctionName", "TableName", "QueueName",
            "RoleName", "UserName", "StackName", "DBInstanceIdentifier", "InstanceId");

    private final CloudTrailEventService events;
    private final IamActionRegistry actions;
    private final AccountResolver accounts;
    private final RegionResolver regions;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final CurrentVertxRequest currentRequest;
    private final ResolvedServiceCatalog catalog;

    @Inject
    public CloudTrailManagementFilter(CloudTrailEventService events, IamActionRegistry actions,
                                      AccountResolver accounts, RegionResolver regions, ObjectMapper mapper,
                                      EmulatorConfig config, CurrentVertxRequest currentRequest,
                                      ResolvedServiceCatalog catalog) {
        this.events = events;
        this.actions = actions;
        this.accounts = accounts;
        this.regions = regions;
        this.mapper = mapper;
        this.config = config;
        this.currentRequest = currentRequest;
        this.catalog = catalog;
    }

    @Override
    public void filter(ContainerRequestContext request) {
        if (!config.services().cloudtrail().enabled()) return;
        try {
            String auth = request.getHeaderString("Authorization");
            if (auth == null) {
                String credential = request.getUriInfo().getQueryParameters().getFirst("X-Amz-Credential");
                if (credential != null) auth = "Credential=" + credential;
            }
            var scope = SCOPE.matcher(auth == null ? "" : auth);
            if (!scope.find()) return;
            String signingService = scope.group(3);
            String service = catalog.canonicalCredentialScope(signingService);
            String target = request.getHeaderString("X-Amz-Target");
            if (target != null) {
                var match = catalog.matchTarget(target).orElse(null);
                if (match == null || !match.descriptor().credentialScopes().contains(signingService)) return;
            }
            if (!CONTROL_SERVICES.contains(service) && !"s3".equals(service) && !"lambda".equals(service)) return;
            ObjectNode parameters = mapper.createObjectNode();
            if (!"s3".equals(service) && request.hasEntity()) {
                // Only inspect bounded management payloads; leave larger streams untouched.
                var stream = request.getEntityStream();
                byte[] bytes = stream.readNBytes(256 * 1024 + 1);
                request.setEntityStream(new SequenceInputStream(new ByteArrayInputStream(bytes), stream));
                if (bytes.length > 256 * 1024) return;
                String body = new String(bytes, StandardCharsets.UTF_8);
                if (request.getHeaderString("X-Amz-Target") != null || body.stripLeading().startsWith("{")) {
                    JsonNode json = mapper.readTree(bytes);
                    for (String name : PARAMETER_NAMES) {
                        if (json.has(name)) parameters.set(Character.toLowerCase(name.charAt(0)) + name.substring(1), json.get(name));
                    }
                }
            }
            String action = actions.resolve(service, request);
            if (action == null) return;
            String operation = action.substring(action.indexOf(':') + 1);
            if (operation.contains("*") || "InvokeFunction".equals(operation)) return;
            if ("s3".equals(service)) {
                String path = request.getUriInfo().getPath().replaceFirst("^/", "");
                String[] segments = path.split("/", -1);
                if (segments.length > 1 && !segments[1].isEmpty()) return;
                if (Set.of("ListBucket", "ListBucketVersions", "ListBucketMultipartUploads").contains(operation)) return;
                if (!path.isEmpty()) parameters.put("bucketName", segments[0]);
                if ("ListAllMyBuckets".equals(operation)) operation = "ListBuckets";
            }
            boolean readOnly = operation.startsWith("Get") || operation.startsWith("List")
                    || operation.startsWith("Describe") || operation.startsWith("Lookup")
                    || operation.startsWith("Head");
            String sourceIp = null;
            var routing = currentRequest.getCurrent();
            if (routing != null && routing.request().remoteAddress() != null) {
                sourceIp = routing.request().remoteAddress().host();
            }
            request.setProperty(CAPTURE, new Capture(service, operation, regions.getRegion(),
                    accounts.extractAccessKeyId(auth), readOnly, parameters, sourceIp));
        } catch (Exception e) {
            LOG.warn("CloudTrail could not inspect management request", e);
        }
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (!(request.getProperty(CAPTURE) instanceof Capture capture)) return;
        try {
            String code = null;
            String message = null;
            if (response.getStatus() >= 400) {
                Object entity = response.getEntity();
                if (entity instanceof String xml && xml.stripLeading().startsWith("<")) {
                    code = XmlParser.extractFirst(xml, "Code", null);
                    message = XmlParser.extractFirst(xml, "Message", null);
                } else {
                    JsonNode error = entity instanceof String text && text.stripLeading().startsWith("{")
                            ? mapper.readTree(text) : mapper.valueToTree(entity);
                    if (error != null) {
                        code = error.path("__type").asText(error.path("code").asText(null));
                        message = error.path("message").asText(error.path("Message").asText(null));
                    }
                }
                if (code != null && code.contains("#")) code = code.substring(code.lastIndexOf('#') + 1);
                if (Set.of("InvalidAction", "UnknownOperationException").contains(code == null ? "" : code)) return;
                // Do not label an unparsed failure as a successful API call.
                if (code == null) code = "HTTP" + response.getStatus();
            }
            events.record(capture.service(), capture.operation(), capture.region(), capture.accessKey(),
                    capture.readOnly(), capture.parameters(), capture.sourceIp(), request.getHeaderString("User-Agent"),
                    response.getHeaderString("x-amzn-requestid"), code, message);
        } catch (Exception e) {
            LOG.warn("CloudTrail could not record management response", e);
        }
    }

    private record Capture(String service, String operation, String region, String accessKey,
                           boolean readOnly, ObjectNode parameters, String sourceIp) {}
}
