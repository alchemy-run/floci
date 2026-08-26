package io.github.hectorvent.floci.services.servicecatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.services.servicecatalog.model.CatalogRecord;
import io.github.hectorvent.floci.services.servicecatalog.model.Portfolio;
import io.github.hectorvent.floci.services.servicecatalog.model.Product;
import io.github.hectorvent.floci.services.servicecatalog.model.ProvisionedProduct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * JSON 1.1 handler for AWS Service Catalog. Dispatched from
 * {@code AwsJson11Controller} under the {@code AWS242ServiceCatalogService.} target prefix.
 */
@ApplicationScoped
public class ServiceCatalogJsonHandler {

    static final String TARGET_PREFIX = "AWS242ServiceCatalogService.";

    private final ServiceCatalogService service;
    private final ObjectMapper objectMapper;

    @Inject
    public ServiceCatalogJsonHandler(ServiceCatalogService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "ListPortfolios" -> listPortfolios(region);
                case "DescribePortfolio" -> describePortfolio(body, region);
                case "CreatePortfolio" -> createPortfolio(body, region);
                case "UpdatePortfolio" -> updatePortfolio(body, region);
                case "DeletePortfolio" -> {
                    service.deletePortfolio(region, body);
                    yield ok();
                }
                case "SearchProductsAsAdmin" -> searchProductsAsAdmin(body, region);
                case "DescribeProductAsAdmin" -> describeProductAsAdmin(body, region);
                case "CreateProduct" -> createProduct(body, region);
                case "UpdateProduct" -> updateProduct(body, region);
                case "DeleteProduct" -> {
                    service.deleteProduct(region, body);
                    yield ok();
                }
                case "ListProvisioningArtifacts" -> listProvisioningArtifacts(body, region);
                case "UpdateProvisioningArtifact" -> updateProvisioningArtifact(body, region);
                case "AssociateProductWithPortfolio" -> {
                    service.associateProductWithPortfolio(region, body);
                    yield ok();
                }
                case "DisassociateProductFromPortfolio" -> {
                    service.disassociateProductFromPortfolio(region, body);
                    yield ok();
                }
                case "ListPortfoliosForProduct" -> listPortfoliosForProduct(body, region);
                case "AssociatePrincipalWithPortfolio" -> {
                    service.associatePrincipalWithPortfolio(region, body);
                    yield ok();
                }
                case "DisassociatePrincipalFromPortfolio" -> {
                    service.disassociatePrincipalFromPortfolio(region, body);
                    yield ok();
                }
                case "ListPrincipalsForPortfolio" -> listPrincipalsForPortfolio(body, region);
                case "SearchProducts" -> searchProducts(region);
                case "DescribeProduct", "DescribeProductView" -> describeProduct(body, region);
                case "ListLaunchPaths" -> listLaunchPaths(body, region);
                case "DescribeProvisioningParameters" -> describeProvisioningParameters(body, region);
                case "ProvisionProduct" -> recordResponse(service.provisionProduct(region, body));
                case "DescribeRecord" -> describeRecord(body, region);
                case "DescribeProvisionedProduct" -> describeProvisionedProduct(body, region);
                case "SearchProvisionedProducts" -> searchProvisionedProducts(region);
                case "GetProvisionedProductOutputs" -> getOutputs(body, region);
                case "ListRecordHistory" -> listRecordHistory(region);
                case "TerminateProvisionedProduct" ->
                        recordResponse(service.terminateProvisionedProduct(region, body));
                case "UpdateProvisionedProduct" ->
                        recordResponse(service.updateProvisionedProduct(region, body));
                case "ListStackInstancesForProvisionedProduct" -> listStackInstances(body, region);
                case "DescribeServiceActionExecutionParameters",
                     "ExecuteProvisionedProductServiceAction" -> {
                    service.requireProvisionedProduct(region, body);
                    service.requireServiceAction(body);
                    yield ok();
                }
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(TARGET_PREFIX + action);
            };
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    private Response listPortfolios(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("PortfolioDetails");
        for (Portfolio portfolio : service.listPortfolios(region)) {
            details.add(portfolioDetail(portfolio));
        }
        return Response.ok(response).build();
    }

    private Response describePortfolio(JsonNode request, String region) {
        Portfolio portfolio = service.describePortfolio(region, request);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("PortfolioDetail", portfolioDetail(portfolio));
        response.set("Tags", tagsArray(portfolio.getTags()));
        return Response.ok(response).build();
    }

    private Response createPortfolio(JsonNode request, String region) {
        Portfolio portfolio = service.createPortfolio(region, request);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("PortfolioDetail", portfolioDetail(portfolio));
        response.set("Tags", tagsArray(portfolio.getTags()));
        return Response.ok(response).build();
    }

    private Response updatePortfolio(JsonNode request, String region) {
        Portfolio portfolio = service.updatePortfolio(region, request);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("PortfolioDetail", portfolioDetail(portfolio));
        response.set("Tags", tagsArray(portfolio.getTags()));
        return Response.ok(response).build();
    }

    private Response searchProductsAsAdmin(JsonNode request, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("ProductViewDetails");
        for (Product product : service.searchProductsAsAdmin(region, request)) {
            details.add(productViewDetail(product));
        }
        return Response.ok(response).build();
    }

    private Response describeProductAsAdmin(JsonNode request, String region) {
        Product product = service.describeProduct(region, request);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ProductViewDetail", productViewDetail(product));
        ArrayNode summaries = response.putArray("ProvisioningArtifactSummaries");
        for (Product.Artifact artifact : product.getArtifacts()) {
            summaries.add(artifactSummary(artifact));
        }
        response.set("Tags", tagsArray(product.getTags()));
        return Response.ok(response).build();
    }

    private Response createProduct(JsonNode request, String region) {
        Product product = service.createProduct(region, request);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ProductViewDetail", productViewDetail(product));
        if (!product.getArtifacts().isEmpty()) {
            response.set("ProvisioningArtifactDetail", artifactDetail(product.getArtifacts().get(0)));
        }
        response.set("Tags", tagsArray(product.getTags()));
        return Response.ok(response).build();
    }

    private Response updateProduct(JsonNode request, String region) {
        Product product = service.updateProduct(region, request);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ProductViewDetail", productViewDetail(product));
        response.set("Tags", tagsArray(product.getTags()));
        return Response.ok(response).build();
    }

    private Response listProvisioningArtifacts(JsonNode request, String region) {
        Product product = service.describeProduct(region, request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("ProvisioningArtifactDetails");
        for (Product.Artifact artifact : product.getArtifacts()) {
            details.add(artifactDetail(artifact));
        }
        return Response.ok(response).build();
    }

    private Response updateProvisioningArtifact(JsonNode request, String region) {
        Product product = service.updateProvisioningArtifact(region, request);
        String artifactId = textOrNull(request, "ProvisioningArtifactId");
        Product.Artifact artifact = product.getArtifacts().stream()
                .filter(a -> artifactId != null && artifactId.equals(a.getId()))
                .findFirst()
                .orElse(product.getArtifacts().isEmpty() ? null : product.getArtifacts().get(0));
        ObjectNode response = objectMapper.createObjectNode();
        if (artifact != null) {
            response.set("ProvisioningArtifactDetail", artifactDetail(artifact));
            if (artifact.getTemplateUrl() != null) {
                ObjectNode info = response.putObject("Info");
                info.put("LoadTemplateFromURL", artifact.getTemplateUrl());
            }
        }
        response.put("Status", "AVAILABLE");
        return Response.ok(response).build();
    }

    private Response listPortfoliosForProduct(JsonNode request, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("PortfolioDetails");
        for (Portfolio portfolio : service.listPortfoliosForProduct(region, request)) {
            details.add(portfolioDetail(portfolio));
        }
        return Response.ok(response).build();
    }

    private Response listPrincipalsForPortfolio(JsonNode request, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode principals = response.putArray("Principals");
        for (Portfolio.PrincipalAssociation principal : service.listPrincipalsForPortfolio(region, request)) {
            ObjectNode node = principals.addObject();
            node.put("PrincipalARN", principal.getPrincipalArn());
            node.put("PrincipalType", principal.getPrincipalType());
        }
        return Response.ok(response).build();
    }

    private Response searchProducts(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("ProductViewSummaries");
        for (Product product : service.searchProducts(region)) {
            summaries.add(productViewSummary(product));
        }
        return Response.ok(response).build();
    }

    private Response describeProduct(JsonNode request, String region) {
        Product product = service.describeProduct(region, request);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ProductViewSummary", productViewSummary(product));
        ArrayNode artifacts = response.putArray("ProvisioningArtifacts");
        for (Product.Artifact artifact : product.getArtifacts()) {
            artifacts.add(artifactSummary(artifact));
        }
        ArrayNode paths = response.putArray("LaunchPaths");
        for (Portfolio portfolio : service.listLaunchPaths(region, request)) {
            ObjectNode path = paths.addObject();
            path.put("Id", portfolio.getId());
            path.put("Name", portfolio.getDisplayName());
        }
        return Response.ok(response).build();
    }

    private Response listLaunchPaths(JsonNode request, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("LaunchPathSummaries");
        for (Portfolio portfolio : service.listLaunchPaths(region, request)) {
            ObjectNode summary = summaries.addObject();
            summary.put("Id", portfolio.getId());
            summary.put("Name", portfolio.getDisplayName());
            summary.putArray("ConstraintSummaries");
            summary.set("Tags", tagsArray(portfolio.getTags()));
        }
        return Response.ok(response).build();
    }

    private Response describeProvisioningParameters(JsonNode request, String region) {
        service.describeProduct(region, request);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("ProvisioningArtifactParameters");
        response.putArray("ConstraintSummaries");
        response.putArray("UsageInstructions");
        response.putArray("TagOptions");
        response.putArray("ProvisioningArtifactOutputs");
        response.putArray("ProvisioningArtifactOutputKeys");
        return Response.ok(response).build();
    }

    private Response describeRecord(JsonNode request, String region) {
        CatalogRecord record = service.describeRecord(region, request);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("RecordDetail", recordDetail(record));
        response.putArray("RecordOutputs");
        return Response.ok(response).build();
    }

    private Response describeProvisionedProduct(JsonNode request, String region) {
        ProvisionedProduct pp = service.describeProvisionedProduct(region, request);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ProvisionedProductDetail", provisionedDetail(pp));
        response.putArray("CloudWatchDashboards");
        return Response.ok(response).build();
    }

    private Response searchProvisionedProducts(String region) {
        java.util.List<ProvisionedProduct> found = service.searchProvisionedProducts(region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("ProvisionedProducts");
        for (ProvisionedProduct pp : found) {
            ObjectNode node = list.addObject();
            node.put("Id", pp.getId());
            node.put("Name", pp.getName());
            node.put("Arn", pp.getArn());
            node.put("Type", pp.getType());
            node.put("Status", pp.getStatus());
            node.put("CreatedTime", pp.getCreatedTime());
            if (pp.getLastRecordId() != null) {
                node.put("LastRecordId", pp.getLastRecordId());
            }
            if (pp.getProductId() != null) {
                node.put("ProductId", pp.getProductId());
            }
            if (pp.getProvisioningArtifactId() != null) {
                node.put("ProvisioningArtifactId", pp.getProvisioningArtifactId());
            }
            if (pp.getStackArn() != null) {
                node.put("PhysicalId", pp.getStackArn());
            }
        }
        response.put("TotalResultsCount", found.size());
        return Response.ok(response).build();
    }

    private Response getOutputs(JsonNode request, String region) {
        ProvisionedProduct pp = service.getProvisionedProductOutputs(region, request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode outputs = response.putArray("Outputs");
        for (ProvisionedProduct.Output output : pp.getOutputs()) {
            ObjectNode node = outputs.addObject();
            node.put("OutputKey", output.getKey());
            node.put("OutputValue", output.getValue());
            if (output.getDescription() != null) {
                node.put("Description", output.getDescription());
            }
        }
        return Response.ok(response).build();
    }

    private Response listRecordHistory(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("RecordDetails");
        for (CatalogRecord record : service.listRecordHistory(region)) {
            details.add(recordDetail(record));
        }
        return Response.ok(response).build();
    }

    private Response listStackInstances(JsonNode request, String region) {
        service.requireProvisionedProduct(region, request);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("StackInstances");
        return Response.ok(response).build();
    }

    private Response recordResponse(CatalogRecord record) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("RecordDetail", recordDetail(record));
        return Response.ok(response).build();
    }

    private ObjectNode productViewSummary(Product product) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("Id", product.getViewId());
        summary.put("ProductId", product.getId());
        summary.put("Name", product.getName());
        summary.put("Owner", product.getOwner());
        if (product.getDescription() != null) {
            summary.put("ShortDescription", product.getDescription());
        }
        summary.put("Type", product.getProductType());
        if (product.getDistributor() != null) {
            summary.put("Distributor", product.getDistributor());
        }
        summary.put("HasDefaultPath", true);
        if (product.getSupportEmail() != null) {
            summary.put("SupportEmail", product.getSupportEmail());
        }
        if (product.getSupportDescription() != null) {
            summary.put("SupportDescription", product.getSupportDescription());
        }
        if (product.getSupportUrl() != null) {
            summary.put("SupportUrl", product.getSupportUrl());
        }
        return summary;
    }

    private ObjectNode provisionedDetail(ProvisionedProduct pp) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", pp.getName());
        node.put("Arn", pp.getArn());
        node.put("Type", pp.getType());
        node.put("Id", pp.getId());
        node.put("Status", pp.getStatus());
        node.put("CreatedTime", pp.getCreatedTime());
        if (pp.getProvisionToken() != null) {
            node.put("IdempotencyToken", pp.getProvisionToken());
        }
        if (pp.getLastRecordId() != null) {
            node.put("LastRecordId", pp.getLastRecordId());
        }
        if (pp.getLastProvisioningRecordId() != null) {
            node.put("LastProvisioningRecordId", pp.getLastProvisioningRecordId());
        }
        if (pp.getLastSuccessfulProvisioningRecordId() != null) {
            node.put("LastSuccessfulProvisioningRecordId", pp.getLastSuccessfulProvisioningRecordId());
        }
        if (pp.getProductId() != null) {
            node.put("ProductId", pp.getProductId());
        }
        if (pp.getProvisioningArtifactId() != null) {
            node.put("ProvisioningArtifactId", pp.getProvisioningArtifactId());
        }
        return node;
    }

    private ObjectNode recordDetail(CatalogRecord record) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("RecordId", record.getRecordId());
        node.put("ProvisionedProductName", record.getProvisionedProductName());
        node.put("Status", record.getStatus());
        node.put("CreatedTime", record.getCreatedTime());
        node.put("UpdatedTime", record.getUpdatedTime());
        if (record.getProvisionedProductType() != null) {
            node.put("ProvisionedProductType", record.getProvisionedProductType());
        }
        node.put("RecordType", record.getRecordType());
        node.put("ProvisionedProductId", record.getProvisionedProductId());
        if (record.getProductId() != null) {
            node.put("ProductId", record.getProductId());
        }
        if (record.getProvisioningArtifactId() != null) {
            node.put("ProvisioningArtifactId", record.getProvisioningArtifactId());
        }
        if (record.getPathId() != null) {
            node.put("PathId", record.getPathId());
        }
        node.putArray("RecordErrors");
        return node;
    }

    private ObjectNode portfolioDetail(Portfolio portfolio) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", portfolio.getId());
        node.put("ARN", portfolio.getArn());
        node.put("DisplayName", portfolio.getDisplayName());
        if (portfolio.getDescription() != null) {
            node.put("Description", portfolio.getDescription());
        }
        node.put("CreatedTime", portfolio.getCreatedTime());
        node.put("ProviderName", portfolio.getProviderName());
        return node;
    }

    private ObjectNode productViewDetail(Product product) {
        ObjectNode node = objectMapper.createObjectNode();
        ObjectNode summary = node.putObject("ProductViewSummary");
        summary.put("Id", product.getViewId());
        summary.put("ProductId", product.getId());
        summary.put("Name", product.getName());
        summary.put("Owner", product.getOwner());
        if (product.getDescription() != null) {
            summary.put("ShortDescription", product.getDescription());
        }
        summary.put("Type", product.getProductType());
        if (product.getDistributor() != null) {
            summary.put("Distributor", product.getDistributor());
        }
        summary.put("HasDefaultPath", false);
        if (product.getSupportEmail() != null) {
            summary.put("SupportEmail", product.getSupportEmail());
        }
        if (product.getSupportDescription() != null) {
            summary.put("SupportDescription", product.getSupportDescription());
        }
        if (product.getSupportUrl() != null) {
            summary.put("SupportUrl", product.getSupportUrl());
        }
        node.put("Status", product.getStatus() != null ? product.getStatus() : "AVAILABLE");
        node.put("ProductARN", product.getArn());
        node.put("CreatedTime", product.getCreatedTime());
        return node;
    }

    private ObjectNode artifactDetail(Product.Artifact artifact) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", artifact.getId());
        if (artifact.getName() != null) {
            node.put("Name", artifact.getName());
        }
        if (artifact.getDescription() != null) {
            node.put("Description", artifact.getDescription());
        }
        if (artifact.getType() != null) {
            node.put("Type", artifact.getType());
        }
        node.put("CreatedTime", artifact.getCreatedTime());
        node.put("Active", artifact.isActive());
        node.put("Guidance", artifact.getGuidance());
        return node;
    }

    private ObjectNode artifactSummary(Product.Artifact artifact) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", artifact.getId());
        if (artifact.getName() != null) {
            node.put("Name", artifact.getName());
        }
        if (artifact.getDescription() != null) {
            node.put("Description", artifact.getDescription());
        }
        node.put("CreatedTime", artifact.getCreatedTime());
        if (artifact.getTemplateUrl() != null) {
            ObjectNode info = node.putObject("ProvisioningArtifactMetadata");
            info.put("LoadTemplateFromURL", artifact.getTemplateUrl());
        }
        return node;
    }

    private ArrayNode tagsArray(Map<String, String> tags) {
        ArrayNode array = objectMapper.createArrayNode();
        if (tags == null) {
            return array;
        }
        tags.forEach((key, value) -> {
            ObjectNode tag = array.addObject();
            tag.put("Key", key);
            tag.put("Value", value);
        });
        return array;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String text = node.get(field).asText();
        return text == null || text.isBlank() ? null : text;
    }

    private Response ok() {
        return Response.ok(objectMapper.createObjectNode()).build();
    }
}
