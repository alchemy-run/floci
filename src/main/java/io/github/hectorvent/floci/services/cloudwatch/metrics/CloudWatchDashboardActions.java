package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dashboard;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON 1.0 / Query encoding for CloudWatch dashboard APIs.
 * Isolated so other CloudWatch TDD agents can rewrite the shared handlers.
 */
public final class CloudWatchDashboardActions {

    private CloudWatchDashboardActions() {}

    public static boolean handles(String action) {
        return switch (action) {
            case "PutDashboard", "GetDashboard", "ListDashboards", "DeleteDashboards" -> true;
            default -> false;
        };
    }

    public static Response handleJson(ObjectMapper mapper, String action, JsonNode request) {
        CloudWatchDashboardService service = CDI.current().select(CloudWatchDashboardService.class).get();
        return handleJson(service, mapper, action, request);
    }

    public static Response handleJson(CloudWatchDashboardService service, ObjectMapper mapper,
                                      String action, JsonNode request) {
        return switch (action) {
            case "PutDashboard" -> {
                service.putDashboard(text(request, "DashboardName"), text(request, "DashboardBody"));
                ObjectNode response = mapper.createObjectNode();
                response.putArray("DashboardValidationMessages");
                yield Response.ok(response).build();
            }
            case "GetDashboard" -> {
                Dashboard dashboard = service.getDashboard(text(request, "DashboardName"));
                ObjectNode node = mapper.createObjectNode();
                if (dashboard.getDashboardArn() != null) {
                    node.put("DashboardArn", dashboard.getDashboardArn());
                }
                if (dashboard.getDashboardBody() != null) {
                    node.put("DashboardBody", dashboard.getDashboardBody());
                }
                if (dashboard.getDashboardName() != null) {
                    node.put("DashboardName", dashboard.getDashboardName());
                }
                yield Response.ok(node).build();
            }
            case "ListDashboards" -> {
                List<Dashboard> dashboards = service.listDashboards(text(request, "DashboardNamePrefix"));
                ObjectNode response = mapper.createObjectNode();
                ArrayNode entries = response.putArray("DashboardEntries");
                for (Dashboard dashboard : dashboards) {
                    ObjectNode entry = entries.addObject();
                    if (dashboard.getDashboardName() != null) {
                        entry.put("DashboardName", dashboard.getDashboardName());
                    }
                    if (dashboard.getDashboardArn() != null) {
                        entry.put("DashboardArn", dashboard.getDashboardArn());
                    }
                    entry.put("LastModified", dashboard.getLastModified());
                    entry.put("Size", dashboard.getSize());
                }
                yield Response.ok(response).build();
            }
            case "DeleteDashboards" -> {
                List<String> names = new ArrayList<>();
                JsonNode namesNode = request.path("DashboardNames");
                if (namesNode.isArray()) {
                    namesNode.forEach(n -> names.add(n.asText()));
                }
                service.deleteDashboards(names);
                yield Response.ok(mapper.createObjectNode()).build();
            }
            default -> throw new IllegalArgumentException("Unsupported dashboard action: " + action);
        };
    }

    public static Response handleQuery(String action, MultivaluedMap<String, String> params) {
        CloudWatchDashboardService service = CDI.current().select(CloudWatchDashboardService.class).get();
        return handleQuery(service, action, params);
    }

    public static Response handleQuery(CloudWatchDashboardService service, String action,
                                       MultivaluedMap<String, String> params) {
        return switch (action) {
            case "PutDashboard" -> {
                service.putDashboard(params.getFirst("DashboardName"), params.getFirst("DashboardBody"));
                String result = new XmlBuilder().start("DashboardValidationMessages")
                        .end("DashboardValidationMessages").build();
                yield Response.ok(AwsQueryResponse.envelope("PutDashboard", AwsNamespaces.CW, result)).build();
            }
            case "GetDashboard" -> {
                Dashboard dashboard = service.getDashboard(params.getFirst("DashboardName"));
                String result = new XmlBuilder()
                        .elem("DashboardName", dashboard.getDashboardName())
                        .elem("DashboardArn", dashboard.getDashboardArn())
                        .elem("DashboardBody", dashboard.getDashboardBody())
                        .build();
                yield Response.ok(AwsQueryResponse.envelope("GetDashboard", AwsNamespaces.CW, result)).build();
            }
            case "ListDashboards" -> {
                XmlBuilder xml = new XmlBuilder().start("DashboardEntries");
                for (Dashboard dashboard : service.listDashboards(params.getFirst("DashboardNamePrefix"))) {
                    xml.start("member")
                            .elem("DashboardName", dashboard.getDashboardName())
                            .elem("DashboardArn", dashboard.getDashboardArn())
                            .end("member");
                }
                xml.end("DashboardEntries");
                yield Response.ok(AwsQueryResponse.envelope("ListDashboards", AwsNamespaces.CW, xml.build())).build();
            }
            case "DeleteDashboards" -> {
                List<String> names = new ArrayList<>();
                for (int i = 1; ; i++) {
                    String name = params.getFirst("DashboardNames.member." + i);
                    if (name == null) break;
                    names.add(name);
                }
                service.deleteDashboards(names);
                yield Response.ok(AwsQueryResponse.envelopeNoResult("DeleteDashboards", AwsNamespaces.CW)).build();
            }
            default -> throw new IllegalArgumentException("Unsupported dashboard action: " + action);
        };
    }

    private static String text(JsonNode request, String field) {
        JsonNode node = request.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asText();
    }
}
