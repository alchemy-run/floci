package io.github.hectorvent.floci.services.servicecatalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.servicecatalog.model.CatalogRecord;
import io.github.hectorvent.floci.services.servicecatalog.model.Portfolio;
import io.github.hectorvent.floci.services.servicecatalog.model.Product;
import io.github.hectorvent.floci.services.servicecatalog.model.ProvisionedProduct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AWS Service Catalog JSON 1.1 ({@code AWS242ServiceCatalogService.*}).
 *
 * <p>Portfolios, products, associations, and provisioned-product lifecycle
 * settle immediately so local reconcilers and binding fixtures do not wait
 * on the live catalog/CloudFormation window. Provisioning records a synthetic
 * CloudFormation stack ARN without creating a real stack.
 */
@ApplicationScoped
public class ServiceCatalogService implements Resettable {

    static final String SERVICE = "servicecatalog";
    static final String ARN_SERVICE = "catalog";
    static final String PP_ARN_SERVICE = "servicecatalog";
    static final String TARGET_PREFIX = "AWS242ServiceCatalogService.";

    private final StorageBackend<String, Portfolio> portfolios;
    private final StorageBackend<String, Product> products;
    private final StorageBackend<String, ProvisionedProduct> provisioned;
    private final StorageBackend<String, CatalogRecord> records;
    private final RegionResolver regionResolver;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public ServiceCatalogService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create(SERVICE, "servicecatalog-portfolios.json",
                        new TypeReference<Map<String, Portfolio>>() {
                        }),
                storageFactory.create(SERVICE, "servicecatalog-products.json",
                        new TypeReference<Map<String, Product>>() {
                        }),
                storageFactory.create(SERVICE, "servicecatalog-provisioned.json",
                        new TypeReference<Map<String, ProvisionedProduct>>() {
                        }),
                storageFactory.create(SERVICE, "servicecatalog-records.json",
                        new TypeReference<Map<String, CatalogRecord>>() {
                        }),
                regionResolver);
    }

    ServiceCatalogService(
            StorageBackend<String, Portfolio> portfolios,
            StorageBackend<String, Product> products,
            StorageBackend<String, ProvisionedProduct> provisioned,
            StorageBackend<String, CatalogRecord> records,
            RegionResolver regionResolver) {
        this.portfolios = portfolios;
        this.products = products;
        this.provisioned = provisioned;
        this.records = records;
        this.regionResolver = regionResolver;
    }

    @Override
    public void clear() {
        portfolios.clear();
        products.clear();
        provisioned.clear();
        records.clear();
    }

    public synchronized Portfolio createPortfolio(String region, JsonNode request) {
        String displayName = requireText(request, "DisplayName");
        String providerName = requireText(request, "ProviderName");
        String token = requireText(request, "IdempotencyToken");
        Optional<Portfolio> existing = findPortfolioByToken(region, token);
        if (existing.isPresent()) {
            return existing.get();
        }
        long now = Instant.now().getEpochSecond();
        String id = generateId("port-", 12);
        Portfolio portfolio = new Portfolio();
        portfolio.setId(id);
        portfolio.setArn(catalogArn(region, "portfolio/" + id));
        portfolio.setDisplayName(displayName);
        portfolio.setDescription(optionalText(request, "Description"));
        portfolio.setProviderName(providerName);
        portfolio.setCreatedTime(now);
        portfolio.setRegion(region);
        portfolio.setIdempotencyToken(token);
        portfolio.setTags(readTagMap(request.get("Tags")));
        portfolios.put(key(region, id), portfolio);
        return portfolio;
    }

    public Portfolio describePortfolio(String region, JsonNode request) {
        return requirePortfolio(region, requireText(request, "Id"));
    }

    public List<Portfolio> listPortfolios(String region) {
        List<Portfolio> found = portfolios.scan(k -> k.startsWith(region + "::"));
        found.sort(Comparator.comparing(Portfolio::getCreatedTime));
        return found;
    }

    public synchronized Portfolio updatePortfolio(String region, JsonNode request) {
        Portfolio portfolio = requirePortfolio(region, requireText(request, "Id"));
        if (request.hasNonNull("DisplayName")) {
            portfolio.setDisplayName(request.get("DisplayName").asText());
        }
        if (request.hasNonNull("ProviderName")) {
            portfolio.setProviderName(request.get("ProviderName").asText());
        }
        if (request.hasNonNull("Description")) {
            portfolio.setDescription(request.get("Description").asText());
        }
        applyTagDelta(portfolio.getTags(), request);
        portfolios.put(key(region, portfolio.getId()), portfolio);
        return portfolio;
    }

    public synchronized void deletePortfolio(String region, JsonNode request) {
        Portfolio portfolio = requirePortfolio(region, requireText(request, "Id"));
        if (!portfolio.getProductIds().isEmpty() || !portfolio.getPrincipals().isEmpty()) {
            throw inUse("Portfolio " + portfolio.getId() + " still has associations.");
        }
        portfolios.delete(key(region, portfolio.getId()));
    }

    public synchronized Product createProduct(String region, JsonNode request) {
        String name = requireText(request, "Name");
        String owner = requireText(request, "Owner");
        String productType = requireText(request, "ProductType");
        String token = requireText(request, "IdempotencyToken");
        Optional<Product> existing = findProductByToken(region, token);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (findProductByName(region, name).isPresent()) {
            throw duplicate("Product " + name + " already exists.");
        }
        long now = Instant.now().getEpochSecond();
        String id = generateId("prod-", 13);
        Product product = new Product();
        product.setId(id);
        product.setViewId(generateId("prodview-", 13));
        product.setArn(catalogArn(region, "product/" + id));
        product.setName(name);
        product.setOwner(owner);
        product.setDescription(optionalText(request, "Description"));
        product.setDistributor(optionalText(request, "Distributor"));
        product.setSupportDescription(optionalText(request, "SupportDescription"));
        product.setSupportEmail(optionalText(request, "SupportEmail"));
        product.setSupportUrl(optionalText(request, "SupportUrl"));
        product.setProductType(productType);
        product.setStatus("AVAILABLE");
        product.setCreatedTime(now);
        product.setRegion(region);
        product.setIdempotencyToken(token);
        product.setTags(readTagMap(request.get("Tags")));
        JsonNode artifactParams = request.get("ProvisioningArtifactParameters");
        if (artifactParams != null && artifactParams.isObject()) {
            product.getArtifacts().add(createArtifact(artifactParams, productType, now));
        }
        products.put(key(region, id), product);
        return product;
    }

    public Product describeProduct(String region, JsonNode request) {
        return requireProduct(region, request);
    }

    public List<Product> searchProducts(String region) {
        List<Product> found = products.scan(k -> k.startsWith(region + "::"));
        found.sort(Comparator.comparing(Product::getName, Comparator.nullsLast(String::compareTo)));
        return found;
    }

    public List<Product> searchProductsAsAdmin(String region, JsonNode request) {
        String portfolioId = optionalText(request, "PortfolioId");
        List<Product> found = searchProducts(region);
        if (portfolioId != null) {
            Portfolio portfolio = requirePortfolio(region, portfolioId);
            found = found.stream().filter(p -> portfolio.getProductIds().contains(p.getId())).toList();
        }
        return found;
    }

    public synchronized Product updateProduct(String region, JsonNode request) {
        Product product = requireProductById(region, requireText(request, "Id"));
        if (request.hasNonNull("Name")) {
            String name = request.get("Name").asText();
            Optional<Product> clash = findProductByName(region, name);
            if (clash.isPresent() && !clash.get().getId().equals(product.getId())) {
                throw duplicate("Product " + name + " already exists.");
            }
            product.setName(name);
        }
        if (request.hasNonNull("Owner")) {
            product.setOwner(request.get("Owner").asText());
        }
        if (request.hasNonNull("Description")) {
            product.setDescription(request.get("Description").asText());
        }
        if (request.hasNonNull("Distributor")) {
            product.setDistributor(request.get("Distributor").asText());
        }
        if (request.hasNonNull("SupportDescription")) {
            product.setSupportDescription(request.get("SupportDescription").asText());
        }
        if (request.hasNonNull("SupportEmail")) {
            product.setSupportEmail(request.get("SupportEmail").asText());
        }
        if (request.hasNonNull("SupportUrl")) {
            product.setSupportUrl(request.get("SupportUrl").asText());
        }
        applyTagDelta(product.getTags(), request);
        products.put(key(region, product.getId()), product);
        return product;
    }

    public synchronized void deleteProduct(String region, JsonNode request) {
        Product product = requireProductById(region, requireText(request, "Id"));
        for (Portfolio portfolio : listPortfolios(region)) {
            if (portfolio.getProductIds().contains(product.getId())) {
                throw inUse("Product " + product.getId() + " is still associated with a portfolio.");
            }
        }
        products.delete(key(region, product.getId()));
    }

    public synchronized Product updateProvisioningArtifact(String region, JsonNode request) {
        Product product = requireProductById(region, requireText(request, "ProductId"));
        String artifactId = requireText(request, "ProvisioningArtifactId");
        Product.Artifact artifact = product.getArtifacts().stream()
                .filter(a -> artifactId.equals(a.getId()))
                .findFirst()
                .orElseThrow(() -> notFound("Provisioning artifact " + artifactId + " was not found."));
        if (request.hasNonNull("Name")) {
            artifact.setName(request.get("Name").asText());
        }
        if (request.hasNonNull("Description")) {
            artifact.setDescription(request.get("Description").asText());
        }
        if (request.hasNonNull("Active")) {
            artifact.setActive(request.get("Active").asBoolean());
        }
        if (request.hasNonNull("Guidance")) {
            artifact.setGuidance(request.get("Guidance").asText());
        }
        products.put(key(region, product.getId()), product);
        return product;
    }

    public synchronized void associateProductWithPortfolio(String region, JsonNode request) {
        Portfolio portfolio = requirePortfolio(region, requireText(request, "PortfolioId"));
        Product product = requireProductById(region, requireText(request, "ProductId"));
        if (!portfolio.getProductIds().contains(product.getId())) {
            portfolio.getProductIds().add(product.getId());
            portfolios.put(key(region, portfolio.getId()), portfolio);
        }
    }

    public synchronized void disassociateProductFromPortfolio(String region, JsonNode request) {
        Portfolio portfolio = requirePortfolio(region, requireText(request, "PortfolioId"));
        String productId = requireText(request, "ProductId");
        requireProductById(region, productId);
        portfolio.getProductIds().remove(productId);
        portfolios.put(key(region, portfolio.getId()), portfolio);
    }

    public List<Portfolio> listPortfoliosForProduct(String region, JsonNode request) {
        Product product = requireProduct(region, request);
        String productId = product.getId();
        return listPortfolios(region).stream()
                .filter(p -> p.getProductIds().contains(productId))
                .toList();
    }

    public synchronized void associatePrincipalWithPortfolio(String region, JsonNode request) {
        Portfolio portfolio = requirePortfolio(region, requireText(request, "PortfolioId"));
        String arn = requireText(request, "PrincipalARN");
        String type = optionalText(request, "PrincipalType");
        if (type == null || type.isBlank()) {
            type = "IAM";
        }
        String resolvedType = type;
        boolean present = portfolio.getPrincipals().stream()
                .anyMatch(p -> arn.equals(p.getPrincipalArn()));
        if (!present) {
            portfolio.getPrincipals().add(new Portfolio.PrincipalAssociation(arn, resolvedType));
            portfolios.put(key(region, portfolio.getId()), portfolio);
        }
    }

    public synchronized void disassociatePrincipalFromPortfolio(String region, JsonNode request) {
        Portfolio portfolio = requirePortfolio(region, requireText(request, "PortfolioId"));
        String arn = requireText(request, "PrincipalARN");
        portfolio.getPrincipals().removeIf(p -> arn.equals(p.getPrincipalArn()));
        portfolios.put(key(region, portfolio.getId()), portfolio);
    }

    public List<Portfolio.PrincipalAssociation> listPrincipalsForPortfolio(String region, JsonNode request) {
        return requirePortfolio(region, requireText(request, "PortfolioId")).getPrincipals();
    }

    public List<Portfolio> listLaunchPaths(String region, JsonNode request) {
        return listPortfoliosForProduct(region, request);
    }

    public synchronized CatalogRecord provisionProduct(String region, JsonNode request) {
        String token = requireText(request, "ProvisionToken");
        Optional<CatalogRecord> existing = findRecordByToken(region, token);
        if (existing.isPresent()) {
            return existing.get();
        }
        String name = requireText(request, "ProvisionedProductName");
        if (findProvisionedByName(region, name).isPresent()) {
            throw duplicate("A provisioned product with name " + name + " already exists.");
        }
        Product product = requireProduct(region, request);
        Product.Artifact artifact = resolveArtifact(product, request);
        String pathId = optionalText(request, "PathId");
        if (pathId == null) {
            List<Portfolio> paths = listPortfolios(region).stream()
                    .filter(p -> p.getProductIds().contains(product.getId()))
                    .toList();
            if (!paths.isEmpty()) {
                pathId = paths.get(0).getId();
            }
        }
        long now = Instant.now().getEpochSecond();
        String ppId = generateId("pp-", 13);
        String recordId = generateId("rec-", 17);
        String stackId = UUID.randomUUID().toString();
        String stackArn = "arn:aws:cloudformation:" + region + ":" + accountId()
                + ":stack/SC-" + name + "-" + ppId + "/" + stackId;

        ProvisionedProduct pp = new ProvisionedProduct();
        pp.setId(ppId);
        pp.setName(name);
        pp.setArn("arn:aws:" + PP_ARN_SERVICE + ":" + region + ":" + accountId()
                + ":stack/" + name + "/" + ppId);
        pp.setStatus("AVAILABLE");
        pp.setRegion(region);
        pp.setProductId(product.getId());
        pp.setProvisioningArtifactId(artifact.getId());
        pp.setPathId(pathId);
        pp.setLastRecordId(recordId);
        pp.setLastProvisioningRecordId(recordId);
        pp.setLastSuccessfulProvisioningRecordId(recordId);
        pp.setProvisionToken(token);
        pp.setStackArn(stackArn);
        pp.setCreatedTime(now);
        pp.setTags(readTagMap(request.get("Tags")));
        pp.getOutputs().add(new ProvisionedProduct.Output(
                "CloudformationStackARN", stackArn, "ARN of the CloudFormation stack"));
        provisioned.put(key(region, ppId), pp);

        CatalogRecord record = newRecord(recordId, region, now, "PROVISION_PRODUCT", token, pp);
        records.put(key(region, recordId), record);
        return record;
    }

    public CatalogRecord describeRecord(String region, JsonNode request) {
        String id = requireText(request, "Id");
        return records.get(key(region, id)).orElseThrow(() -> notFound("Record " + id + " was not found."));
    }

    public ProvisionedProduct describeProvisionedProduct(String region, JsonNode request) {
        String id = optionalText(request, "Id");
        if (id == null) {
            id = optionalText(request, "ProvisionedProductId");
        }
        String name = optionalText(request, "Name");
        if (name == null) {
            name = optionalText(request, "ProvisionedProductName");
        }
        if (id == null && name == null) {
            throw invalid("Either Id or Name must be specified.");
        }
        if (id != null) {
            return requireProvisionedById(region, id);
        }
        String productName = name;
        return findProvisionedByName(region, productName)
                .orElseThrow(() -> notFound("Provisioned product " + productName + " was not found."));
    }

    public List<ProvisionedProduct> searchProvisionedProducts(String region) {
        List<ProvisionedProduct> found = provisioned.scan(k -> k.startsWith(region + "::"));
        found.sort(Comparator.comparing(ProvisionedProduct::getCreatedTime));
        return found;
    }

    public ProvisionedProduct getProvisionedProductOutputs(String region, JsonNode request) {
        return describeProvisionedProduct(region, request);
    }

    public List<CatalogRecord> listRecordHistory(String region) {
        List<CatalogRecord> found = records.scan(k -> k.startsWith(region + "::"));
        found.sort(Comparator.comparing(CatalogRecord::getCreatedTime).reversed());
        return found;
    }

    public synchronized CatalogRecord terminateProvisionedProduct(String region, JsonNode request) {
        String token = requireText(request, "TerminateToken");
        Optional<CatalogRecord> existing = findRecordByToken(region, token);
        if (existing.isPresent()) {
            return existing.get();
        }
        ProvisionedProduct pp = describeProvisionedProduct(region, request);
        long now = Instant.now().getEpochSecond();
        String recordId = generateId("rec-", 17);
        CatalogRecord record = newRecord(recordId, region, now, "TERMINATE_PROVISIONED_PRODUCT", token, pp);
        records.put(key(region, recordId), record);
        provisioned.delete(key(region, pp.getId()));
        return record;
    }

    public synchronized CatalogRecord updateProvisionedProduct(String region, JsonNode request) {
        String token = requireText(request, "UpdateToken");
        Optional<CatalogRecord> existing = findRecordByToken(region, token);
        if (existing.isPresent()) {
            return existing.get();
        }
        ProvisionedProduct pp = describeProvisionedProduct(region, request);
        Product product = requireProductById(region, pp.getProductId());
        if (request.hasNonNull("ProductId") || request.hasNonNull("ProductName")) {
            product = requireProduct(region, request);
            pp.setProductId(product.getId());
        }
        if (request.hasNonNull("ProvisioningArtifactId") || request.hasNonNull("ProvisioningArtifactName")) {
            Product.Artifact artifact = resolveArtifact(product, request);
            pp.setProvisioningArtifactId(artifact.getId());
        }
        if (request.hasNonNull("PathId")) {
            pp.setPathId(request.get("PathId").asText());
        }
        long now = Instant.now().getEpochSecond();
        String recordId = generateId("rec-", 17);
        pp.setLastRecordId(recordId);
        pp.setLastProvisioningRecordId(recordId);
        pp.setLastSuccessfulProvisioningRecordId(recordId);
        provisioned.put(key(region, pp.getId()), pp);
        CatalogRecord record = newRecord(recordId, region, now, "UPDATE_PROVISIONED_PRODUCT", token, pp);
        records.put(key(region, recordId), record);
        return record;
    }

    public void requireProvisionedProduct(String region, JsonNode request) {
        String id = optionalText(request, "ProvisionedProductId");
        if (id == null) {
            describeProvisionedProduct(region, request);
            return;
        }
        requireProvisionedById(region, id);
    }

    public void requireServiceAction(JsonNode request) {
        String actionId = optionalText(request, "ServiceActionId");
        if (actionId == null || actionId.isBlank()) {
            throw invalid("ServiceActionId is required.");
        }
        throw notFound("Service action " + actionId + " was not found.");
    }

    private CatalogRecord newRecord(
            String recordId, String region, long now, String type, String token, ProvisionedProduct pp) {
        CatalogRecord record = new CatalogRecord();
        record.setRecordId(recordId);
        record.setProvisionedProductName(pp.getName());
        record.setProvisionedProductId(pp.getId());
        record.setProvisionedProductType(pp.getType());
        record.setStatus("SUCCEEDED");
        record.setRecordType(type);
        record.setProductId(pp.getProductId());
        record.setProvisioningArtifactId(pp.getProvisioningArtifactId());
        record.setPathId(pp.getPathId());
        record.setRegion(region);
        record.setIdempotencyToken(token);
        record.setCreatedTime(now);
        record.setUpdatedTime(now);
        return record;
    }

    private Product.Artifact createArtifact(JsonNode params, String productType, long now) {
        Product.Artifact artifact = new Product.Artifact();
        artifact.setId(generateId("pa-", 13));
        artifact.setName(optionalText(params, "Name") != null ? optionalText(params, "Name") : "v1");
        artifact.setDescription(optionalText(params, "Description"));
        String type = optionalText(params, "Type");
        artifact.setType(type != null ? type : productType);
        artifact.setCreatedTime(now);
        JsonNode info = params.get("Info");
        if (info != null && info.isObject()) {
            if (info.hasNonNull("LoadTemplateFromURL")) {
                artifact.setTemplateUrl(info.get("LoadTemplateFromURL").asText());
            } else if (info.hasNonNull("LoadTemplateFromS3")) {
                artifact.setTemplateUrl(info.get("LoadTemplateFromS3").asText());
            }
        }
        return artifact;
    }

    private Product.Artifact resolveArtifact(Product product, JsonNode request) {
        String artifactId = optionalText(request, "ProvisioningArtifactId");
        String artifactName = optionalText(request, "ProvisioningArtifactName");
        if (artifactId != null) {
            return product.getArtifacts().stream()
                    .filter(a -> artifactId.equals(a.getId()))
                    .findFirst()
                    .orElseThrow(() -> notFound("Provisioning artifact " + artifactId + " was not found."));
        }
        if (artifactName != null) {
            return product.getArtifacts().stream()
                    .filter(a -> artifactName.equals(a.getName()))
                    .findFirst()
                    .orElseThrow(() -> notFound("Provisioning artifact " + artifactName + " was not found."));
        }
        if (product.getArtifacts().isEmpty()) {
            throw invalid("Product " + product.getId() + " has no provisioning artifacts.");
        }
        return product.getArtifacts().get(0);
    }

    private Portfolio requirePortfolio(String region, String id) {
        return portfolios.get(key(region, id))
                .orElseThrow(() -> notFound("Portfolio " + id + " was not found."));
    }

    private Product requireProduct(String region, JsonNode request) {
        String id = optionalText(request, "Id");
        if (id == null) {
            id = optionalText(request, "ProductId");
        }
        String name = optionalText(request, "Name");
        if (name == null) {
            name = optionalText(request, "ProductName");
        }
        if (id == null && name == null) {
            throw invalid("Either Id or Name must be specified.");
        }
        if (id != null) {
            return requireProductById(region, id);
        }
        String productName = name;
        return findProductByName(region, productName)
                .orElseThrow(() -> notFound("Product " + productName + " was not found."));
    }

    private Product requireProductById(String region, String id) {
        return products.get(key(region, id))
                .orElseThrow(() -> notFound("Product " + id + " was not found."));
    }

    private ProvisionedProduct requireProvisionedById(String region, String id) {
        return provisioned.get(key(region, id))
                .orElseThrow(() -> notFound("Provisioned product " + id + " was not found."));
    }

    private Optional<Portfolio> findPortfolioByToken(String region, String token) {
        return portfolios.scan(k -> k.startsWith(region + "::")).stream()
                .filter(p -> token.equals(p.getIdempotencyToken()))
                .findFirst();
    }

    private Optional<Product> findProductByToken(String region, String token) {
        return products.scan(k -> k.startsWith(region + "::")).stream()
                .filter(p -> token.equals(p.getIdempotencyToken()))
                .findFirst();
    }

    private Optional<Product> findProductByName(String region, String name) {
        return products.scan(k -> k.startsWith(region + "::")).stream()
                .filter(p -> name.equals(p.getName()))
                .findFirst();
    }

    private Optional<ProvisionedProduct> findProvisionedByName(String region, String name) {
        return provisioned.scan(k -> k.startsWith(region + "::")).stream()
                .filter(p -> name.equals(p.getName()))
                .findFirst();
    }

    private Optional<CatalogRecord> findRecordByToken(String region, String token) {
        return records.scan(k -> k.startsWith(region + "::")).stream()
                .filter(r -> token.equals(r.getIdempotencyToken()))
                .findFirst();
    }

    private void applyTagDelta(Map<String, String> tags, JsonNode request) {
        if (request.has("RemoveTags") && request.get("RemoveTags").isArray()) {
            for (JsonNode key : request.get("RemoveTags")) {
                if (key != null && key.isTextual()) {
                    tags.remove(key.asText());
                }
            }
        }
        if (request.has("AddTags")) {
            tags.putAll(readTagMap(request.get("AddTags")));
        }
    }

    private static Map<String, String> readTagMap(JsonNode tagList) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagList == null || !tagList.isArray()) {
            return tags;
        }
        for (JsonNode tag : tagList) {
            String key = textOrNull(tag, "Key");
            if (key != null) {
                String value = textOrNull(tag, "Value");
                tags.put(key, value != null ? value : "");
            }
        }
        return tags;
    }

    private String catalogArn(String region, String resource) {
        return "arn:aws:" + ARN_SERVICE + ":" + region + ":" + accountId() + ":" + resource;
    }

    private String accountId() {
        return regionResolver != null ? regionResolver.getAccountId() : "000000000000";
    }

    private String generateId(String prefix, int length) {
        byte[] bytes = new byte[(length + 1) / 2];
        random.nextBytes(bytes);
        String hex = HexFormat.of().formatHex(bytes);
        return prefix + hex.substring(0, length);
    }

    private static String key(String region, String id) {
        return region + "::" + id;
    }

    private static String requireText(JsonNode node, String field) {
        String value = textOrNull(node, field);
        if (value == null) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    static String optionalText(JsonNode node, String field) {
        return textOrNull(node, field);
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 400);
    }

    static AwsException invalid(String message) {
        return new AwsException("InvalidParametersException", message, 400);
    }

    static AwsException duplicate(String message) {
        return new AwsException("DuplicateResourceException", message, 400);
    }

    static AwsException inUse(String message) {
        return new AwsException("ResourceInUseException", message, 400);
    }
}
