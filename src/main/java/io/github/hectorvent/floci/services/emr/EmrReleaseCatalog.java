package io.github.hectorvent.floci.services.emr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Read-only release metadata. This catalog does not provision or execute cluster software. */
@ApplicationScoped
public class EmrReleaseCatalog {

    // Curated release metadata, newest first; unknown releases are not synthesized.
    private static final List<Release> RELEASES = List.of(
            new Release("emr-7.5.0", List.of(
                    Map.of("Name", "Hadoop", "Version", "3.4.0-amzn-1"),
                    Map.of("Name", "Spark", "Version", "3.5.2-amzn-0"))),
            new Release("emr-6.15.0", List.of(
                    Map.of("Name", "Hadoop", "Version", "3.3.6-amzn-1"),
                    Map.of("Name", "Spark", "Version", "3.4.1-amzn-2"))));

    private static final List<Map<String, Object>> INSTANCE_TYPES = List.of(
            instanceType("m5.xlarge", "m5", 4, 16, "X86_64"),
            instanceType("m5.2xlarge", "m5", 8, 32, "X86_64"),
            instanceType("m6g.xlarge", "m6g", 4, 16, "ARM64"),
            instanceType("r5.xlarge", "r5", 4, 32, "X86_64"));

    private final ObjectMapper mapper;
    private final RegionResolver regionResolver;

    @Inject
    public EmrReleaseCatalog(ObjectMapper mapper, RegionResolver regionResolver) {
        this.mapper = mapper;
        this.regionResolver = regionResolver;
    }

    public ObjectNode listReleaseLabels(JsonNode request, String region) {
        JsonNode filters = request.path("Filters");
        if (!filters.isMissingNode() && !filters.isObject()) {
            throw invalid("Filters must be an object.");
        }
        String prefix = optionalText(filters, "Prefix");
        String application = optionalText(filters, "Application");
        List<String> labels = RELEASES.stream()
                .filter(release -> prefix == null || release.label().startsWith(prefix))
                .filter(release -> application == null || release.applications().stream()
                        .anyMatch(app -> application.equalsIgnoreCase(app.get("Name"))))
                .map(Release::label).toList();
        String query = mapper.createArrayNode().add(prefix).add(application).toString();
        return page(request, "NextToken", "ReleaseLabels", labels,
                scope(region, "ListReleaseLabels", query), 100);
    }

    public ObjectNode describeReleaseLabel(JsonNode request, String region) {
        Release release = requireRelease(request);
        ObjectNode response = page(request, "NextToken", "Applications", release.applications(),
                scope(region, "DescribeReleaseLabel", release.label()), 100);
        response.put("ReleaseLabel", release.label());
        return response;
    }

    public ObjectNode listSupportedInstanceTypes(JsonNode request, String region) {
        Release release = requireRelease(request);
        return page(request, "Marker", "SupportedInstanceTypes", INSTANCE_TYPES,
                scope(region, "ListSupportedInstanceTypes", release.label()), 100);
    }

    private Release requireRelease(JsonNode request) {
        String label = optionalText(request, "ReleaseLabel");
        if (label == null || label.isBlank()) {
            throw invalid("ReleaseLabel is required.");
        }
        return RELEASES.stream().filter(release -> release.label().equals(label)).findFirst()
                .orElseThrow(() -> invalid("Release label " + label + " is not supported."));
    }

    private String scope(String region, String operation, String query) {
        return mapper.createArrayNode().add(regionResolver.getAccountId()).add(region)
                .add(operation).add(query).toString();
    }

    private <T> ObjectNode page(JsonNode request, String tokenField, String resultField,
                                List<T> values, String scope, int defaultSize) {
        int size = defaultSize;
        if ("NextToken".equals(tokenField) && request.has("MaxResults")) {
            JsonNode max = request.get("MaxResults");
            if (!max.isIntegralNumber() || !max.canConvertToInt() || max.intValue() < 1 || max.intValue() > 100) {
                throw invalid("MaxResults must be an integer between 1 and 100.");
            }
            size = max.intValue();
        }
        String token = optionalText(request, tokenField);
        int start = 0;
        if (token != null) {
            try {
                String cursor = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
                String prefix = scope + "\n";
                if (!cursor.startsWith(prefix)) {
                    throw invalid("Invalid " + tokenField + ".");
                }
                start = Integer.parseInt(cursor.substring(prefix.length()));
                if (start < 1 || start >= values.size()) {
                    throw invalid("Invalid " + tokenField + ".");
                }
            } catch (IllegalArgumentException e) {
                throw invalid("Invalid " + tokenField + ".");
            }
        }
        int end = Math.min(start + size, values.size());
        ObjectNode response = mapper.createObjectNode();
        response.set(resultField, mapper.valueToTree(values.subList(start, end)));
        if (end < values.size()) {
            response.put(tokenField, Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((scope + "\n" + end).getBytes(StandardCharsets.UTF_8)));
        }
        return response;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(field + " must be a string.");
        }
        return value.textValue();
    }

    private static Map<String, Object> instanceType(String type, String family, int vcpu, int memory,
                                                   String architecture) {
        return Map.ofEntries(
                Map.entry("Type", type), Map.entry("InstanceFamilyId", family),
                Map.entry("VCPU", vcpu), Map.entry("MemoryGB", memory),
                Map.entry("StorageGB", 0), Map.entry("NumberOfDisks", 0),
                Map.entry("Is64BitsOnly", true), Map.entry("EbsStorageOnly", true),
                Map.entry("EbsOptimizedAvailable", true), Map.entry("EbsOptimizedByDefault", true),
                Map.entry("Architecture", architecture));
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    private record Release(String label, List<Map<String, String>> applications) {}
}
