package io.github.hectorvent.floci.services.s3tables;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.s3tables.model.Table;
import io.github.hectorvent.floci.services.s3tables.model.TableBucket;
import io.github.hectorvent.floci.services.s3tables.model.TableNamespace;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Amazon S3 Tables restJson1. Public AWS paths are {@code /buckets}, {@code /namespaces},
 * {@code /tables} and {@code /get-table}; {@link S3TablesRoutingFilter} prefixes them so
 * they do not collide with S3 path-style routes. Requests are signed as {@code s3tables}.
 */
@Path(S3TablesRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class S3TablesController {

    private final S3TablesService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public S3TablesController(S3TablesService service, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @RegisterForReflection
    public record CreateTableBucketRequest(
            String name,
            Object encryptionConfiguration,
            Object storageClassConfiguration,
            Object tags
    ) {}

    @RegisterForReflection
    public record CreateTableBucketResponse(String arn) {}

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TableBucketRepresentation(
            String arn,
            String name,
            String ownerAccountId,
            String createdAt,
            String tableBucketId,
            String type
    ) {}

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ListTableBucketsResponse(
            List<TableBucketRepresentation> tableBuckets,
            String continuationToken
    ) {}

    @RegisterForReflection
    public record CreateNamespaceRequest(List<String> namespace) {}

    @RegisterForReflection
    public record CreateNamespaceResponse(String tableBucketARN, List<String> namespace) {}

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NamespaceRepresentation(
            List<String> namespace,
            String createdAt,
            String createdBy,
            String ownerAccountId,
            String namespaceId,
            String tableBucketId
    ) {}

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ListNamespacesResponse(
            List<NamespaceRepresentation> namespaces,
            String continuationToken
    ) {}

    @RegisterForReflection
    public record CreateTableResponse(String tableARN, String versionToken) {}

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TableRepresentation(
            String name,
            String type,
            String tableARN,
            List<String> namespace,
            String namespaceId,
            String versionToken,
            String metadataLocation,
            String warehouseLocation,
            String createdAt,
            String createdBy,
            String modifiedAt,
            String modifiedBy,
            String ownerAccountId,
            String format,
            String tableBucketId
    ) {}

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TableSummaryRepresentation(
            List<String> namespace,
            String name,
            String type,
            String tableARN,
            String createdAt,
            String modifiedAt,
            String namespaceId,
            String tableBucketId
    ) {}

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ListTablesResponse(
            List<TableSummaryRepresentation> tables,
            String continuationToken
    ) {}

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TableMetadataLocationResponse(
            String versionToken,
            String metadataLocation,
            String warehouseLocation
    ) {}

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UpdateTableMetadataLocationResponse(
            String name,
            String tableARN,
            List<String> namespace,
            String versionToken,
            String metadataLocation
    ) {}

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MaintenanceJobStatusValue(String status) {}

    @RegisterForReflection
    public record GetTableMaintenanceJobStatusResponse(
            String tableARN,
            java.util.Map<String, MaintenanceJobStatusValue> status
    ) {}

    @PUT
    @Path("/buckets")
    public Response createTableBucket(@Context HttpHeaders headers, CreateTableBucketRequest request) {
        String region = regionResolver.resolveRegion(headers);
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new AwsException("BadRequestException", "name is required.", 400);
        }
        TableBucket bucket = service.createTableBucket(
                request.name(), request.encryptionConfiguration(), region);
        return Response.ok(new CreateTableBucketResponse(bucket.getArn())).build();
    }

    @GET
    @Path("/buckets")
    @Consumes(MediaType.WILDCARD)
    public Response listTableBuckets(
            @Context HttpHeaders headers,
            @QueryParam("prefix") String prefix) {
        String region = regionResolver.resolveRegion(headers);
        List<TableBucketRepresentation> buckets = service.listTableBuckets(region, prefix).stream()
                .map(this::toBucket)
                .toList();
        return Response.ok(new ListTableBucketsResponse(buckets, null)).build();
    }

    @GET
    @Path("/buckets/{rest:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response getTableBucket(@Context HttpHeaders headers, @PathParam("rest") String rest) {
        String region = regionResolver.resolveRegion(headers);
        S3TablesService.BucketPath path = service.parseBucketPath(rest);
        TableBucket bucket = service.getTableBucket(path.bucketArn(), region);
        return Response.ok(toBucket(bucket)).build();
    }

    @DELETE
    @Path("/buckets/{rest:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteTableBucket(@Context HttpHeaders headers, @PathParam("rest") String rest) {
        String region = regionResolver.resolveRegion(headers);
        S3TablesService.BucketPath path = service.parseBucketPath(rest);
        service.deleteTableBucket(path.bucketArn(), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @PUT
    @Path("/namespaces/{rest:.+}")
    public Response createNamespace(
            @Context HttpHeaders headers,
            @PathParam("rest") String rest,
            CreateNamespaceRequest request) {
        String region = regionResolver.resolveRegion(headers);
        S3TablesService.BucketPath path = service.parseBucketPath(rest);
        List<String> namespace = request == null ? List.of() : request.namespace();
        TableNamespace created = service.createNamespace(path.bucketArn(), namespace, region);
        return Response.ok(new CreateNamespaceResponse(path.bucketArn(), List.of(created.getName()))).build();
    }

    @GET
    @Path("/namespaces/{rest:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response getOrListNamespaces(@Context HttpHeaders headers, @PathParam("rest") String rest) {
        String region = regionResolver.resolveRegion(headers);
        S3TablesService.BucketPath path = service.parseBucketPath(rest);
        TableBucket bucket = service.getTableBucket(path.bucketArn(), region);
        if (path.extra().isEmpty()) {
            List<NamespaceRepresentation> namespaces = service.listNamespaces(path.bucketArn(), region).stream()
                    .map(ns -> toNamespace(ns, bucket))
                    .toList();
            return Response.ok(new ListNamespacesResponse(namespaces, null)).build();
        }
        TableNamespace ns = service.getNamespace(path.bucketArn(), path.extra().get(0), region);
        return Response.ok(toNamespace(ns, bucket)).build();
    }

    @DELETE
    @Path("/namespaces/{rest:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteNamespace(@Context HttpHeaders headers, @PathParam("rest") String rest) {
        String region = regionResolver.resolveRegion(headers);
        S3TablesService.BucketPath path = service.parseBucketPath(rest);
        if (path.extra().isEmpty()) {
            throw new AwsException("BadRequestException", "namespace is required.", 400);
        }
        service.deleteNamespace(path.bucketArn(), path.extra().get(0), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @PUT
    @Path("/tables/{rest:.+}")
    public Response createTable(
            @Context HttpHeaders headers,
            @PathParam("rest") String rest,
            String body) {
        String region = regionResolver.resolveRegion(headers);
        S3TablesService.BucketPath path = service.parseBucketPath(rest);
        if (path.extra().isEmpty()) {
            throw new AwsException("BadRequestException", "namespace is required.", 400);
        }
        if (path.extra().size() >= 3 && "metadata-location".equals(path.extra().get(path.extra().size() - 1))) {
            JsonNode json = parse(body);
            Table table = service.updateTableMetadataLocation(
                    path.bucketArn(),
                    path.extra().get(0),
                    path.extra().get(1),
                    text(json, "versionToken"),
                    text(json, "metadataLocation"),
                    region);
            return Response.ok(new UpdateTableMetadataLocationResponse(
                    table.getName(),
                    table.getTableArn(),
                    List.of(table.getNamespace()),
                    table.getVersionToken(),
                    table.getMetadataLocation())).build();
        }
        JsonNode json = parse(body);
        String name = text(json, "name");
        String format = text(json, "format");
        Object metadata = json.has("metadata") ? json.get("metadata") : null;
        Table table = service.createTable(
                path.bucketArn(), path.extra().get(0), name, format, metadata, region);
        return Response.ok(new CreateTableResponse(table.getTableArn(), table.getVersionToken())).build();
    }

    @GET
    @Path("/tables/{rest:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response listTables(
            @Context HttpHeaders headers,
            @PathParam("rest") String rest,
            @QueryParam("namespace") String namespace) {
        String region = regionResolver.resolveRegion(headers);
        S3TablesService.BucketPath path = service.parseBucketPath(rest);
        if (path.extra().size() >= 3 && "metadata-location".equals(path.extra().get(path.extra().size() - 1))) {
            Table table = service.getTableMetadataLocation(
                    path.bucketArn(), path.extra().get(0), path.extra().get(1), region);
            return Response.ok(new TableMetadataLocationResponse(
                    table.getVersionToken(),
                    table.getMetadataLocation(),
                    table.getWarehouseLocation())).build();
        }
        if (path.extra().size() >= 3 && "maintenance-job-status".equals(path.extra().get(path.extra().size() - 1))) {
            Table table = service.getTable(
                    path.bucketArn(), path.extra().get(0), path.extra().get(1), null, region);
            return Response.ok(new GetTableMaintenanceJobStatusResponse(
                    table.getTableArn(),
                    java.util.Map.of(
                            "icebergCompaction", new MaintenanceJobStatusValue("Not_Yet_Run"),
                            "icebergSnapshotManagement", new MaintenanceJobStatusValue("Not_Yet_Run"),
                            "icebergUnreferencedFileRemoval", new MaintenanceJobStatusValue("Not_Yet_Run")))).build();
        }
        TableBucket bucket = service.getTableBucket(path.bucketArn(), region);
        String namespaceFilter = path.extra().isEmpty() ? namespace : path.extra().get(0);
        List<TableSummaryRepresentation> tables = service.listTables(path.bucketArn(), namespaceFilter, region).stream()
                .map(t -> toTableSummary(t, bucket))
                .toList();
        return Response.ok(new ListTablesResponse(tables, null)).build();
    }

    @DELETE
    @Path("/tables/{rest:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteTable(@Context HttpHeaders headers, @PathParam("rest") String rest) {
        String region = regionResolver.resolveRegion(headers);
        S3TablesService.BucketPath path = service.parseBucketPath(rest);
        if (path.extra().size() < 2) {
            throw new AwsException("BadRequestException", "namespace and name are required.", 400);
        }
        service.deleteTable(path.bucketArn(), path.extra().get(0), path.extra().get(1), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/get-table")
    @Consumes(MediaType.WILDCARD)
    public Response getTable(
            @Context HttpHeaders headers,
            @QueryParam("tableBucketARN") String tableBucketArn,
            @QueryParam("namespace") String namespace,
            @QueryParam("name") String name,
            @QueryParam("tableArn") String tableArn) {
        String region = regionResolver.resolveRegion(headers);
        Table table = service.getTable(tableBucketArn, namespace, name, tableArn, region);
        TableBucket bucket = service.getTableBucket(
                tableBucketArn != null && !tableBucketArn.isBlank()
                        ? tableBucketArn
                        : table.getTableArn(),
                region);
        return Response.ok(toTable(table, bucket)).build();
    }

    private TableBucketRepresentation toBucket(TableBucket bucket) {
        return new TableBucketRepresentation(
                bucket.getArn(),
                bucket.getName(),
                bucket.getOwnerAccountId(),
                bucket.getCreatedAt(),
                bucket.getTableBucketId(),
                bucket.getType());
    }

    private NamespaceRepresentation toNamespace(TableNamespace ns, TableBucket bucket) {
        return new NamespaceRepresentation(
                List.of(ns.getName()),
                ns.getCreatedAt(),
                ns.getCreatedBy(),
                ns.getOwnerAccountId(),
                ns.getNamespaceId(),
                bucket.getTableBucketId());
    }

    private TableRepresentation toTable(Table table, TableBucket bucket) {
        TableNamespace ns = bucket.getNamespaces().get(table.getNamespace());
        return new TableRepresentation(
                table.getName(),
                table.getType(),
                table.getTableArn(),
                List.of(table.getNamespace()),
                ns != null ? ns.getNamespaceId() : null,
                table.getVersionToken(),
                table.getMetadataLocation(),
                table.getWarehouseLocation(),
                table.getCreatedAt(),
                table.getCreatedBy(),
                table.getModifiedAt(),
                table.getModifiedBy(),
                table.getOwnerAccountId(),
                table.getFormat(),
                bucket.getTableBucketId());
    }

    private TableSummaryRepresentation toTableSummary(Table table, TableBucket bucket) {
        TableNamespace ns = bucket.getNamespaces().get(table.getNamespace());
        return new TableSummaryRepresentation(
                List.of(table.getNamespace()),
                table.getName(),
                table.getType(),
                table.getTableArn(),
                table.getCreatedAt(),
                table.getModifiedAt(),
                ns != null ? ns.getNamespaceId() : null,
                bucket.getTableBucketId());
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", "Invalid JSON request body.", 400);
        }
    }

    private static String text(JsonNode json, String field) {
        JsonNode node = json.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
