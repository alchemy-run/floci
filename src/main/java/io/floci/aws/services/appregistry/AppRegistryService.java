package io.floci.aws.services.appregistry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudformation.CloudFormationService;
import io.floci.aws.services.cloudformation.model.Stack;
import io.floci.aws.services.resourcegroups.ResourceGroupsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AWS Service Catalog AppRegistry: applications, attribute groups, and the CloudFormation stacks
 * and tag values associated with applications. An application gets the {@code awsApplication} tag
 * its resources carry and two Resource Groups (its resources, and the resources tagged for it);
 * associating a stack applies that tag to the stack unless the association skips it, and
 * {@code SyncResource} associates a stack whose tag names an application. Requests are checked
 * against the constraints vendored from botocore by {@code tools/aws/regen_appregistry_shapes.py}.
 */
@ApplicationScoped
public class AppRegistryService implements Resettable {
    static final String APPLICATION_TAG = "awsApplication";
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int MAX_ATTRIBUTE_GROUPS = 1000;
    private static final int MAX_RESOURCES = 1000;

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/servicecatalog-appregistry-request-shapes.json", "AppRegistry request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return validation(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    return validation(path + " is outside the allowed bounds" + (min == null ? "" : " (min " + min
                            + ")") + (max == null ? "" : " (max " + max + ")") + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return validation(path + " does not match the pattern " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return validation(path + " must be one of " + values + ".");
                }

                @Override
                public AwsException required(String path) {
                    return validation(path + " is required.");
                }
            });

    private final AccountAwareStorageBackend<ObjectNode> applications;
    private final AccountAwareStorageBackend<ObjectNode> attributeGroups;
    private final AccountAwareStorageBackend<ObjectNode> configuration;
    private final Instance<CloudFormationService> cloudFormation;
    private final Instance<ResourceGroupsService> resourceGroups;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public AppRegistryService(StorageFactory storageFactory, Instance<CloudFormationService> cloudFormation,
                              Instance<ResourceGroupsService> resourceGroups, RegionResolver regionResolver,
                              ObjectMapper mapper) {
        TypeReference<Map<String, ObjectNode>> type = new TypeReference<>() {};
        this.applications = storageFactory.create("appregistry", "appregistry-applications.json", type);
        this.attributeGroups = storageFactory.create("appregistry", "appregistry-attribute-groups.json", type);
        this.configuration = storageFactory.create("appregistry", "appregistry-configuration.json", type);
        this.cloudFormation = cloudFormation;
        this.resourceGroups = resourceGroups;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    /** The request with a {@code clientToken}, which the SDKs fill in when the caller doesn't. */
    private JsonNode withToken(JsonNode in) {
        ObjectNode copy = in != null && in.isObject() ? ((ObjectNode) in).deepCopy() : mapper.createObjectNode();
        if (!copy.hasNonNull("clientToken")) {
            copy.put("clientToken", UUID.randomUUID().toString());
        }
        return copy;
    }

    static void check(String operation, JsonNode request, String... pathMembers) {
        ObjectNode checked = request != null && request.isObject() ? ((ObjectNode) request).deepCopy()
                : new ObjectMapper().createObjectNode();
        for (int i = 0; i + 1 < pathMembers.length; i += 2) {
            if (pathMembers[i + 1] != null) {
                checked.put(pathMembers[i], pathMembers[i + 1]);
            }
        }
        SHAPES.check(operation, checked);
    }

    // ── Applications ───────────────────────────────────────────────────────

    public synchronized ObjectNode createApplication(JsonNode in, String region) {
        boolean explicitToken = in.hasNonNull("clientToken");
        in = withToken(in);
        check("CreateApplication", in);
        String name = in.path("name").asText();
        String token = in.path("clientToken").asText(null);
        for (ObjectNode existing : applications.scan(k -> k.startsWith(region + "::"))) {
            if (token != null && token.equals(existing.path("clientToken").asText(null))) {
                return wrap("application", applicationView(existing));
            }
            if (name.equals(existing.path("name").asText())) {
                throw new AwsException("ConflictException", "An application named " + name + " already exists.", 409);
            }
        }
        String id = randomId();
        String arn = arn(region, "/applications/" + id);
        String now = Instant.now().toString();
        ObjectNode app = mapper.createObjectNode();
        app.put("id", id);
        app.put("arn", arn);
        app.put("name", name);
        app.put("description", in.path("description").asText(""));
        app.put("creationTime", now);
        app.put("lastUpdateTime", now);
        app.set("tags", tagsOf(in));
        app.putObject("applicationTag").put(APPLICATION_TAG, arn);
        if (token != null && explicitToken) {
            app.put("clientToken", token);
        }
        app.putArray("attributeGroups");
        app.putArray("resources");
        app.set("integrations", groups(name, arn, region));
        applications.put(key(region, id), app);
        return wrap("application", applicationView(app));
    }

    /** The application's Resource Groups: its associated resources, and the resources carrying its tag. */
    private ObjectNode groups(String name, String arn, String region) {
        ObjectNode integrations = mapper.createObjectNode();
        String account = regionResolver.getAccountId();
        integrations.set("resourceGroup", group(region, account, "AWS_AppRegistry_Application-" + name,
                "Resources of AppRegistry application " + name, arn));
        integrations.set("applicationTagResourceGroup", group(region, account, "AWS_AppRegistry_AppTag_" + account
                + "-" + name, "Resources tagged for AppRegistry application " + name, arn));
        return integrations;
    }

    private ObjectNode group(String region, String account, String name, String description, String appArn) {
        ObjectNode state = mapper.createObjectNode();
        if (!resourceGroups.isResolvable()) {
            return state.put("state", "CREATE_FAILED").put("errorMessage", "Resource Groups is not enabled.");
        }
        ObjectNode request = mapper.createObjectNode();
        request.put("Name", name);
        request.put("Description", description);
        ObjectNode query = request.putObject("ResourceQuery");
        query.put("Type", "TAG_FILTERS_1_0");
        ObjectNode filters = mapper.createObjectNode();
        filters.putArray("ResourceTypeFilters").add("AWS::AllSupported");
        filters.putArray("TagFilters").addObject().put("Key", APPLICATION_TAG).putArray("Values").add(appArn);
        query.put("Query", filters.toString());
        try {
            String groupArn = resourceGroups.get().createServiceManaged(region, account, request).arn();
            return state.put("state", "CREATE_COMPLETE").put("arn", groupArn).put("errorMessage", "");
        } catch (AwsException e) {
            return state.put("state", "CREATE_FAILED").put("errorMessage", e.getMessage());
        }
    }

    public ObjectNode getApplication(String specifier, String region) {
        check("GetApplication", null, "application", specifier);
        ObjectNode app = requireApplication(specifier, region);
        ObjectNode out = applicationView(app);
        out.put("associatedResourceCount", app.withArray("resources").size());
        out.set("integrations", app.path("integrations").deepCopy());
        return out;
    }

    public synchronized ObjectNode updateApplication(String specifier, JsonNode in, String region) {
        check("UpdateApplication", in, "application", specifier);
        ObjectNode app = requireApplication(specifier, region);
        if (in.hasNonNull("name") && !in.get("name").asText().equals(app.path("name").asText())) {
            String name = in.get("name").asText();
            boolean taken = applications.scan(k -> k.startsWith(region + "::")).stream()
                    .anyMatch(a -> name.equals(a.path("name").asText()));
            if (taken) {
                throw new AwsException("ConflictException", "An application named " + name + " already exists.",
                        409);
            }
            app.put("name", name);
        }
        if (in.has("description")) {
            app.put("description", in.path("description").asText(""));
        }
        app.put("lastUpdateTime", Instant.now().toString());
        applications.put(key(region, app.path("id").asText()), app);
        return wrap("application", applicationView(app));
    }

    /** Deletes an application with no associations left, and its Resource Groups. */
    public synchronized ObjectNode deleteApplication(String specifier, String region) {
        check("DeleteApplication", null, "application", specifier);
        ObjectNode app = requireApplication(specifier, region);
        if (!app.withArray("attributeGroups").isEmpty() || !app.withArray("resources").isEmpty()) {
            throw validation("Disassociate the application's attribute groups and resources before you delete it.");
        }
        if (resourceGroups.isResolvable()) {
            String account = regionResolver.getAccountId();
            String name = app.path("name").asText();
            resourceGroups.get().deleteServiceManaged(region, account, "AWS_AppRegistry_Application-" + name);
            resourceGroups.get().deleteServiceManaged(region, account, "AWS_AppRegistry_AppTag_" + account + "-"
                    + name);
        }
        applications.delete(key(region, app.path("id").asText()));
        return wrap("application", summary(app));
    }

    public ObjectNode listApplications(Integer maxResults, String nextToken, String region) {
        List<ObjectNode> all = applications.scan(k -> k.startsWith(region + "::")).stream()
                .sorted(Comparator.comparing(a -> a.path("creationTime").asText())).map(this::summary).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("applications");
        page(all, maxResults, nextToken, out).forEach(list::add);
        return out;
    }

    private ObjectNode requireApplication(String specifier, String region) {
        return find(applications, specifier, region).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "Application " + specifier + " not found.", 404));
    }

    private ObjectNode applicationView(ObjectNode app) {
        ObjectNode out = summary(app);
        out.set("tags", app.path("tags").deepCopy());
        out.set("applicationTag", app.path("applicationTag").deepCopy());
        return out;
    }

    private ObjectNode summary(ObjectNode resource) {
        ObjectNode out = mapper.createObjectNode();
        for (String field : List.of("id", "arn", "name", "description", "creationTime", "lastUpdateTime")) {
            out.set(field, resource.path(field).deepCopy());
        }
        return out;
    }

    // ── Attribute groups ───────────────────────────────────────────────────

    public synchronized ObjectNode createAttributeGroup(JsonNode in, String region) {
        boolean explicitToken = in.hasNonNull("clientToken");
        in = withToken(in);
        check("CreateAttributeGroup", in);
        String name = in.path("name").asText();
        String token = in.path("clientToken").asText(null);
        for (ObjectNode existing : attributeGroups.scan(k -> k.startsWith(region + "::"))) {
            if (token != null && token.equals(existing.path("clientToken").asText(null))) {
                return wrap("attributeGroup", groupView(existing));
            }
            if (name.equals(existing.path("name").asText())) {
                throw new AwsException("ConflictException", "An attribute group named " + name + " already exists.",
                        409);
            }
        }
        String attributes = attributes(in.path("attributes").asText(""));
        String id = randomId();
        String now = Instant.now().toString();
        ObjectNode group = mapper.createObjectNode();
        group.put("id", id);
        group.put("arn", arn(region, "/attribute-groups/" + id));
        group.put("name", name);
        group.put("description", in.path("description").asText(""));
        group.put("attributes", attributes);
        group.put("creationTime", now);
        group.put("lastUpdateTime", now);
        group.put("createdBy", "SELF");
        group.set("tags", tagsOf(in));
        if (token != null && explicitToken) {
            group.put("clientToken", token);
        }
        attributeGroups.put(key(region, id), group);
        return wrap("attributeGroup", groupView(group));
    }

    private static String attributes(String attributes) {
        try {
            JsonNode node = new ObjectMapper().readTree(attributes);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException();
            }
            return attributes;
        } catch (Exception e) {
            throw validation("attributes must be a JSON object.");
        }
    }

    public ObjectNode getAttributeGroup(String specifier, String region) {
        check("GetAttributeGroup", null, "attributeGroup", specifier);
        ObjectNode group = requireGroup(specifier, region);
        ObjectNode out = groupView(group);
        out.put("attributes", group.path("attributes").asText());
        out.put("createdBy", group.path("createdBy").asText());
        return out;
    }

    public synchronized ObjectNode updateAttributeGroup(String specifier, JsonNode in, String region) {
        check("UpdateAttributeGroup", in, "attributeGroup", specifier);
        ObjectNode group = requireGroup(specifier, region);
        if (in.hasNonNull("name")) {
            group.put("name", in.get("name").asText());
        }
        if (in.has("description")) {
            group.put("description", in.path("description").asText(""));
        }
        if (in.hasNonNull("attributes")) {
            group.put("attributes", attributes(in.get("attributes").asText()));
        }
        group.put("lastUpdateTime", Instant.now().toString());
        attributeGroups.put(key(region, group.path("id").asText()), group);
        return wrap("attributeGroup", groupView(group));
    }

    public synchronized ObjectNode deleteAttributeGroup(String specifier, String region) {
        check("DeleteAttributeGroup", null, "attributeGroup", specifier);
        ObjectNode group = requireGroup(specifier, region);
        String arn = group.path("arn").asText();
        boolean associated = applications.scan(k -> k.startsWith(region + "::")).stream()
                .anyMatch(app -> containsText(app.withArray("attributeGroups"), arn));
        if (associated) {
            throw validation("Disassociate the attribute group from its applications before you delete it.");
        }
        attributeGroups.delete(key(region, group.path("id").asText()));
        return wrap("attributeGroup", summary(group));
    }

    public ObjectNode listAttributeGroups(Integer maxResults, String nextToken, String region) {
        List<ObjectNode> all = attributeGroups.scan(k -> k.startsWith(region + "::")).stream()
                .sorted(Comparator.comparing(g -> g.path("creationTime").asText())).map(this::summary).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("attributeGroups");
        page(all, maxResults, nextToken, out).forEach(g -> {
            g.put("createdBy", "SELF");
            list.add(g);
        });
        return out;
    }

    private ObjectNode requireGroup(String specifier, String region) {
        return find(attributeGroups, specifier, region).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "Attribute group " + specifier + " not found.", 404));
    }

    private ObjectNode groupView(ObjectNode group) {
        ObjectNode out = summary(group);
        out.set("tags", group.path("tags").deepCopy());
        return out;
    }

    // ── Attribute group associations ───────────────────────────────────────

    public synchronized ObjectNode associateAttributeGroup(String appSpecifier, String groupSpecifier, String region) {
        check("AssociateAttributeGroup", null, "application", appSpecifier, "attributeGroup", groupSpecifier);
        ObjectNode app = requireApplication(appSpecifier, region);
        ObjectNode group = requireGroup(groupSpecifier, region);
        ArrayNode list = app.withArray("attributeGroups");
        if (!containsText(list, group.path("arn").asText())) {
            if (list.size() >= MAX_ATTRIBUTE_GROUPS) {
                throw new AwsException("ServiceQuotaExceededException", "An application can have at most 1000 "
                        + "attribute groups.", 402);
            }
            list.add(group.path("arn").asText());
            applications.put(key(region, app.path("id").asText()), app);
        }
        return mapper.createObjectNode().put("applicationArn", app.path("arn").asText())
                .put("attributeGroupArn", group.path("arn").asText());
    }

    public synchronized ObjectNode disassociateAttributeGroup(String appSpecifier, String groupSpecifier,
                                                              String region) {
        check("DisassociateAttributeGroup", null, "application", appSpecifier, "attributeGroup", groupSpecifier);
        ObjectNode app = requireApplication(appSpecifier, region);
        ObjectNode group = requireGroup(groupSpecifier, region);
        if (!removeText(app.withArray("attributeGroups"), group.path("arn").asText())) {
            throw new AwsException("ResourceNotFoundException", "The attribute group isn't associated with the "
                    + "application.", 404);
        }
        applications.put(key(region, app.path("id").asText()), app);
        return mapper.createObjectNode().put("applicationArn", app.path("arn").asText())
                .put("attributeGroupArn", group.path("arn").asText());
    }

    public ObjectNode listAssociatedAttributeGroups(String appSpecifier, Integer maxResults, String nextToken,
                                                    String region) {
        check("ListAssociatedAttributeGroups", null, "application", appSpecifier);
        ObjectNode app = requireApplication(appSpecifier, region);
        List<ObjectNode> all = new ArrayList<>();
        app.withArray("attributeGroups").forEach(arn -> all.add(mapper.createObjectNode().put("arn", arn.asText())));
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("attributeGroups");
        page(all, maxResults, nextToken, out).forEach(g -> list.add(idOf(g.path("arn").asText())));
        return out;
    }

    public ObjectNode listAttributeGroupsForApplication(String appSpecifier, Integer maxResults, String nextToken,
                                                        String region) {
        check("ListAttributeGroupsForApplication", null, "application", appSpecifier);
        ObjectNode app = requireApplication(appSpecifier, region);
        List<ObjectNode> all = new ArrayList<>();
        for (JsonNode arn : app.withArray("attributeGroups")) {
            find(attributeGroups, arn.asText(), region).ifPresent(group -> all.add(mapper.createObjectNode()
                    .put("id", group.path("id").asText()).put("arn", group.path("arn").asText())
                    .put("name", group.path("name").asText()).put("createdBy", "SELF")));
        }
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("attributeGroupsDetails");
        page(all, maxResults, nextToken, out).forEach(list::add);
        return out;
    }

    // ── Resource associations ──────────────────────────────────────────────

    /**
     * Associates a CloudFormation stack (by name or ARN) or a tag value with an application. A stack
     * is tagged {@code awsApplication} with the application's ARN, unless the options skip it; a stack
     * already tagged for another application is a ConflictException.
     */
    public synchronized ObjectNode associateResource(String appSpecifier, String type, String resource,
                                                     JsonNode in, String region) {
        check("AssociateResource", in, "application", appSpecifier, "resourceType", type, "resource", resource);
        ObjectNode app = requireApplication(appSpecifier, region);
        List<String> options = new ArrayList<>();
        in.path("options").forEach(o -> options.add(o.asText()));
        boolean applyTag = !options.contains("SKIP_APPLICATION_TAG");
        ObjectNode resolved = resolveResource(type, resource, region);
        String resourceArn = resolved.path("arn").asText();
        ArrayNode list = app.withArray("resources");
        for (JsonNode existing : list) {
            if (resourceArn.equals(existing.path("arn").asText())) {
                return associationResult(app, existing);
            }
        }
        if (list.size() >= MAX_RESOURCES) {
            throw new AwsException("ServiceQuotaExceededException", "An application can have at most 1000 "
                    + "associated resources.", 402);
        }
        if ("CFN_STACK".equals(type)) {
            String tagged = resolved.path("tag").asText("");
            if (!tagged.isEmpty() && !tagged.equals(app.path("arn").asText())) {
                throw new AwsException("ConflictException", "The stack is already tagged for application " + tagged
                        + ".", 409);
            }
            if (applyTag && cloudFormation.isResolvable()) {
                cloudFormation.get().setStackTag(resolved.path("name").asText(), APPLICATION_TAG,
                        app.path("arn").asText(), region);
            }
        }
        ObjectNode association = list.addObject();
        association.put("name", resolved.path("name").asText());
        association.put("arn", resourceArn);
        association.put("resourceType", type);
        association.put("associationTime", Instant.now().toString());
        association.putArray("options").add(applyTag ? "APPLY_APPLICATION_TAG" : "SKIP_APPLICATION_TAG");
        if ("RESOURCE_TAG_VALUE".equals(type)) {
            association.putObject("resourceDetails").put("tagValue", resource);
        }
        applications.put(key(region, app.path("id").asText()), app);
        return associationResult(app, association);
    }

    private ObjectNode associationResult(ObjectNode app, JsonNode association) {
        ObjectNode out = mapper.createObjectNode();
        out.put("applicationArn", app.path("arn").asText());
        out.put("resourceArn", association.path("arn").asText());
        out.set("options", association.path("options").deepCopy());
        return out;
    }

    /** A stack (its name, ARN and the application its tag names) or a tag value as an associable resource. */
    private ObjectNode resolveResource(String type, String resource, String region) {
        ObjectNode out = mapper.createObjectNode();
        if ("RESOURCE_TAG_VALUE".equals(type)) {
            out.put("name", resource);
            out.put("arn", arn(region, "/tag-values/" + resource));
            return out;
        }
        if (!"CFN_STACK".equals(type)) {
            throw validation("resourceType must be CFN_STACK or RESOURCE_TAG_VALUE.");
        }
        if (!cloudFormation.isResolvable()) {
            throw new AwsException("ResourceNotFoundException", "Stack " + resource + " not found.", 404);
        }
        Stack stack;
        try {
            stack = cloudFormation.get().describeStacks(resource, region).getFirst();
        } catch (AwsException e) {
            throw new AwsException("ResourceNotFoundException", "Stack " + resource + " not found.", 404);
        }
        if ("DELETE_COMPLETE".equals(stack.getStatus())) {
            throw new AwsException("ResourceNotFoundException", "Stack " + resource + " not found.", 404);
        }
        out.put("name", stack.getStackName());
        out.put("arn", stack.getStackId());
        Map<String, String> tags = stack.getTags() == null ? Map.of() : stack.getTags();
        out.put("tag", tags.getOrDefault(APPLICATION_TAG, ""));
        return out;
    }

    public synchronized ObjectNode disassociateResource(String appSpecifier, String type, String resource,
                                                        String region) {
        check("DisassociateResource", null, "application", appSpecifier, "resourceType", type, "resource", resource);
        ObjectNode app = requireApplication(appSpecifier, region);
        ArrayNode list = app.withArray("resources");
        for (int i = 0; i < list.size(); i++) {
            JsonNode existing = list.get(i);
            if (type.equals(existing.path("resourceType").asText()) && (resource.equals(existing.path("name").asText())
                    || resource.equals(existing.path("arn").asText()))) {
                list.remove(i);
                applications.put(key(region, app.path("id").asText()), app);
                if ("CFN_STACK".equals(type) && containsText(existing.path("options"), "APPLY_APPLICATION_TAG")
                        && cloudFormation.isResolvable()) {
                    try {
                        cloudFormation.get().setStackTag(existing.path("name").asText(), APPLICATION_TAG, null,
                                region);
                    } catch (AwsException ignored) {
                        // The stack is gone: there is no tag left to remove.
                    }
                }
                return mapper.createObjectNode().put("applicationArn", app.path("arn").asText())
                        .put("resourceArn", existing.path("arn").asText());
            }
        }
        throw new AwsException("ResourceNotFoundException", "Resource " + resource + " isn't associated with the "
                + "application.", 404);
    }

    public ObjectNode getAssociatedResource(String appSpecifier, String type, String resource, String region) {
        check("GetAssociatedResource", null, "application", appSpecifier, "resourceType", type, "resource",
                resource);
        ObjectNode app = requireApplication(appSpecifier, region);
        for (JsonNode existing : app.withArray("resources")) {
            if (type.equals(existing.path("resourceType").asText()) && (resource.equals(existing.path("name").asText())
                    || resource.equals(existing.path("arn").asText()))) {
                ObjectNode out = mapper.createObjectNode();
                ObjectNode body = out.putObject("resource");
                body.put("name", existing.path("name").asText());
                body.put("arn", existing.path("arn").asText());
                body.put("associationTime", existing.path("associationTime").asText());
                body.putObject("integrations").set("resourceGroup", app.path("integrations").path("resourceGroup")
                        .deepCopy());
                out.set("options", existing.path("options").deepCopy());
                ObjectNode tagResult = out.putObject("applicationTagResult");
                boolean applied = containsText(existing.path("options"), "APPLY_APPLICATION_TAG");
                tagResult.put("applicationTagStatus", "SUCCESS");
                ArrayNode resources = tagResult.putArray("resources");
                if (applied && "CFN_STACK".equals(type)) {
                    resources.addObject().put("name", existing.path("name").asText())
                            .put("arn", existing.path("arn").asText()).put("resourceType", "AWS::CloudFormation::Stack");
                }
                return out;
            }
        }
        throw new AwsException("ResourceNotFoundException", "Resource " + resource + " isn't associated with the "
                + "application.", 404);
    }

    public ObjectNode listAssociatedResources(String appSpecifier, Integer maxResults, String nextToken,
                                              String region) {
        check("ListAssociatedResources", null, "application", appSpecifier);
        ObjectNode app = requireApplication(appSpecifier, region);
        List<ObjectNode> all = new ArrayList<>();
        app.withArray("resources").forEach(r -> {
            ObjectNode info = mapper.createObjectNode();
            info.put("name", r.path("name").asText());
            info.put("arn", r.path("arn").asText());
            info.put("resourceType", r.path("resourceType").asText());
            if (r.has("resourceDetails")) {
                info.set("resourceDetails", r.get("resourceDetails").deepCopy());
            }
            info.set("options", r.path("options").deepCopy());
            all.add(info);
        });
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("resources");
        page(all, maxResults, nextToken, out).forEach(list::add);
        return out;
    }

    /**
     * Associates a stack whose {@code awsApplication} tag names an application, as AppRegistry does
     * when the tag is set outside it; a stack already associated, or untagged, needs no action.
     */
    public synchronized ObjectNode syncResource(String type, String resource, String region) {
        check("SyncResource", null, "resourceType", type, "resource", resource);
        ObjectNode resolved = resolveResource(type, resource, region);
        String tag = resolved.path("tag").asText("");
        ObjectNode out = mapper.createObjectNode().put("resourceArn", resolved.path("arn").asText());
        if (tag.isEmpty()) {
            return out.put("actionTaken", "NO_ACTION");
        }
        ObjectNode app = find(applications, tag, region).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "The stack's awsApplication tag names application " + tag
                        + ", which doesn't exist.", 404));
        out.put("applicationArn", app.path("arn").asText());
        for (JsonNode existing : app.withArray("resources")) {
            if (resolved.path("arn").asText().equals(existing.path("arn").asText())) {
                return out.put("actionTaken", "NO_ACTION");
            }
        }
        ObjectNode association = app.withArray("resources").addObject();
        association.put("name", resolved.path("name").asText());
        association.put("arn", resolved.path("arn").asText());
        association.put("resourceType", type);
        association.put("associationTime", Instant.now().toString());
        association.putArray("options").add("APPLY_APPLICATION_TAG");
        applications.put(key(region, app.path("id").asText()), app);
        return out.put("actionTaken", "START_SYNC");
    }

    // ── Configuration ──────────────────────────────────────────────────────

    public ObjectNode getConfiguration(String region) {
        ObjectNode stored = configuration.get(region).orElse(null);
        ObjectNode out = mapper.createObjectNode();
        ObjectNode config = out.putObject("configuration");
        config.putObject("tagQueryConfiguration").put("tagKey", stored == null ? APPLICATION_TAG
                : stored.path("tagKey").asText(APPLICATION_TAG));
        return out;
    }

    public synchronized void putConfiguration(JsonNode in, String region) {
        check("PutConfiguration", in);
        String tagKey = in.path("configuration").path("tagQueryConfiguration").path("tagKey").asText(null);
        if (tagKey == null || tagKey.isEmpty()) {
            throw validation("configuration.tagQueryConfiguration.tagKey is required.");
        }
        configuration.put(region, mapper.createObjectNode().put("tagKey", tagKey));
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    private record Tagged(AccountAwareStorageBackend<ObjectNode> store, String key, ObjectNode resource) {}

    private Tagged tagged(String arn, String region) {
        if (!AwsArnUtils.isArnFor(arn, "servicecatalog")) {
            throw validation("The resource ARN " + arn + " is not an AppRegistry ARN.");
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String arnRegion = parsed.region().isEmpty() ? region : parsed.region();
        for (AccountAwareStorageBackend<ObjectNode> store : List.of(applications, attributeGroups)) {
            Optional<ObjectNode> found = find(store, arn, arnRegion);
            if (found.isPresent()) {
                return new Tagged(store, key(arnRegion, found.get().path("id").asText()), found.get());
            }
        }
        throw new AwsException("ResourceNotFoundException", "Resource " + arn + " not found.", 404);
    }

    public Map<String, String> listTags(String arn, String region) {
        Map<String, String> out = new LinkedHashMap<>();
        tagged(arn, region).resource().path("tags").fields().forEachRemaining(e -> out.put(e.getKey(),
                e.getValue().asText()));
        return out;
    }

    public synchronized void tag(String arn, Map<String, String> tags, String region) {
        Tagged tagged = tagged(arn, region);
        ObjectNode current = tagged.resource().withObject("tags");
        tags.forEach(current::put);
        if (current.size() > 50) {
            throw validation("A resource can have at most 50 tags.");
        }
        tagged.store().put(tagged.key(), tagged.resource());
    }

    public synchronized void untag(String arn, List<String> keys, String region) {
        Tagged tagged = tagged(arn, region);
        keys.forEach(tagged.resource().withObject("tags")::remove);
        tagged.store().put(tagged.key(), tagged.resource());
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** An application or attribute group by its id, name or ARN. */
    private static Optional<ObjectNode> find(AccountAwareStorageBackend<ObjectNode> store, String specifier,
                                             String region) {
        Optional<ObjectNode> byId = store.get(key(region, specifier));
        if (byId.isPresent()) {
            return byId;
        }
        return store.scan(k -> k.startsWith(region + "::")).stream()
                .filter(r -> specifier.equals(r.path("name").asText()) || specifier.equals(r.path("arn").asText()))
                .findFirst();
    }

    private static boolean containsText(JsonNode list, String value) {
        for (JsonNode item : list) {
            if (value.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean removeText(ArrayNode list, String value) {
        for (int i = 0; i < list.size(); i++) {
            if (value.equals(list.get(i).asText())) {
                list.remove(i);
                return true;
            }
        }
        return false;
    }

    private static String idOf(String arn) {
        return arn.substring(arn.lastIndexOf('/') + 1);
    }

    private ObjectNode tagsOf(JsonNode in) {
        ObjectNode tags = mapper.createObjectNode();
        in.path("tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        return tags;
    }

    private ObjectNode wrap(String field, ObjectNode value) {
        ObjectNode out = mapper.createObjectNode();
        out.set(field, value);
        return out;
    }

    private static List<ObjectNode> page(List<ObjectNode> all, Integer maxResults, String nextToken, ObjectNode out) {
        int max = maxResults == null ? 25 : maxResults;
        if (max < 1 || max > 25) {
            throw validation("maxResults must be between 1 and 25.");
        }
        int start = 0;
        if (nextToken != null && !nextToken.isEmpty()) {
            try {
                start = Integer.parseInt(new String(Base64.getDecoder().decode(nextToken), StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw validation("The nextToken is not valid.");
            }
        }
        int end = Math.min(all.size(), start + max);
        if (end < all.size()) {
            out.put("nextToken", Base64.getEncoder().encodeToString(Integer.toString(end)
                    .getBytes(StandardCharsets.UTF_8)));
        }
        return all.subList(Math.min(start, end), end);
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of("servicecatalog", region, regionResolver.getAccountId(), resource).toString();
    }

    private String randomId() {
        StringBuilder out = new StringBuilder(26);
        for (int i = 0; i < 26; i++) {
            out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    private static String key(String region, String id) {
        return region + "::" + id;
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    @Override
    public void clear() {
        applications.clear();
        attributeGroups.clear();
        configuration.clear();
    }
}
