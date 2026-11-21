package io.floci.aws.services.iotsitewise;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.floci.aws.services.iotsitewise.SiteWiseStore.ACCESS_POLICY;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.DASHBOARD;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.PORTAL;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.PROJECT;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.conflicting;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.invalid;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.meta;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.notFound;

/**
 * SiteWise Monitor: portals (IAM or IAM Identity Center authenticated, each served at its start URL by
 * {@link SiteWisePortalController}), their projects and the assets those share, dashboards whose widgets show asset
 * properties, and access policies that grant users, groups, IAM users or IAM roles a portal or project. A portal is
 * CREATING, then ACTIVE; one with projects can't be deleted, nor a project with dashboards.
 */
@ApplicationScoped
public class SiteWiseMonitor {

    static final String IMAGE = "portal-image";

    private final SiteWiseStore store;
    private final ObjectMapper mapper;
    private final Instance<IamService> iam;
    private final Instance<SiteWiseAssets> assets;

    @Inject
    public SiteWiseMonitor(SiteWiseStore store, Instance<IamService> iam, Instance<SiteWiseAssets> assets) {
        this.store = store;
        this.mapper = store.mapper();
        this.iam = iam;
        this.assets = assets;
    }

    // ---------------------------------------------------------------- portals

    ObjectNode requirePortal(String account, String region, String id) {
        ObjectNode portal = store.get(account, region, PORTAL, id).orElse(null);
        portal = portal == null ? null : settle(account, region, portal);
        if (portal == null) {
            throw notFound("Portal " + id + " not found.");
        }
        return portal;
    }

    private ObjectNode settle(String account, String region, ObjectNode portal) {
        long readyAt = meta(portal).path("readyAt").asLong(0);
        if (readyAt == 0 || System.currentTimeMillis() < readyAt) {
            return portal;
        }
        String id = portal.get("portalId").asText();
        if ("DELETING".equals(portal.path("portalStatus").path("state").asText())) {
            store.delete(account, region, PORTAL, id);
            return null;
        }
        portal.set("portalStatus", mapper.createObjectNode().put("state", "ACTIVE"));
        meta(portal).remove("readyAt");
        store.put(account, region, PORTAL, id, portal);
        return portal;
    }

    private void transition(ObjectNode portal, String state) {
        portal.set("portalStatus", mapper.createObjectNode().put("state", state));
        meta(portal).put("readyAt", System.currentTimeMillis() + store.config().transitionMillis());
    }

    private void checkRole(String account, String roleArn) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(roleArn);
        } catch (RuntimeException e) {
            throw invalid("The role ARN " + roleArn + " isn't valid.");
        }
        if (!"iam".equals(arn.service()) || !arn.resource().startsWith("role/")) {
            throw invalid("The ARN " + roleArn + " isn't an IAM role.");
        }
        String name = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
        try {
            RequestScopes.callAs(account, () -> iam.get().getRole(name));
        } catch (AwsException e) {
            throw invalid("The role " + roleArn + " doesn't exist.");
        }
    }

    ObjectNode createPortal(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreatePortal", in);
        if (repeated != null) {
            return repeated;
        }
        checkRole(account, in.get("roleArn").asText());
        if (in.has("alarms")) {
            checkRole(account, in.get("alarms").get("alarmRoleArn").asText());
        }
        String id = SiteWiseStore.uuid();
        double now = SiteWiseStore.now();
        ObjectNode portal = store.record();
        portal.put("portalId", id);
        portal.put("portalArn", store.arn(account, region, PORTAL, id));
        portal.put("portalName", in.get("portalName").asText());
        if (in.hasNonNull("portalDescription")) {
            portal.put("portalDescription", in.get("portalDescription").asText());
        }
        portal.put("portalClientId", "E" + id.replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT));
        portal.put("portalStartUrl", startUrl(id));
        portal.put("portalContactEmail", in.get("portalContactEmail").asText());
        portal.put("portalCreationDate", now);
        portal.put("portalLastUpdateDate", now);
        if (in.has("portalLogoImageFile")) {
            portal.set("portalLogoImageLocation", image(account, region, in.get("portalLogoImageFile")));
        }
        portal.put("roleArn", in.get("roleArn").asText());
        portal.put("portalAuthMode", in.path("portalAuthMode").asText("SSO"));
        for (String field : List.of("notificationSenderEmail", "alarms", "portalTypeConfiguration")) {
            if (in.has(field)) {
                portal.set(field, in.get(field).deepCopy());
            }
        }
        portal.put("portalType", in.path("portalType").asText("SITEWISE_PORTAL_V1"));
        meta(portal).set("tags", SiteWiseStore.tagsOf(mapper, in.get("tags")));
        meta(portal).put("ssoApplicationId", "ins-" + id.replace("-", "").substring(0, 16));
        transition(portal, "CREATING");
        store.put(account, region, PORTAL, id, portal);
        ObjectNode out = mapper.createObjectNode();
        out.put("portalId", id);
        out.put("portalArn", portal.get("portalArn").asText());
        out.put("portalStartUrl", portal.get("portalStartUrl").asText());
        out.set("portalStatus", portal.get("portalStatus").deepCopy());
        out.put("ssoApplicationId", meta(portal).get("ssoApplicationId").asText());
        store.remember(account, region, "CreatePortal", in, out);
        return out;
    }

    String startUrl(String portalId) {
        return store.baseUrl() + SiteWisePortalController.PATH + "/portals/" + portalId;
    }

    private ObjectNode image(String account, String region, JsonNode file) {
        if (!"PNG".equals(file.path("type").asText())) {
            throw invalid("A portal logo is a PNG image.");
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(file.get("data").asText());
        } catch (IllegalArgumentException e) {
            throw invalid("The portal logo's data isn't base64.");
        }
        if (data.length < 8 || (data[0] & 0xff) != 0x89 || data[1] != 'P' || data[2] != 'N' || data[3] != 'G') {
            throw invalid("The portal logo isn't a PNG image.");
        }
        String id = SiteWiseStore.uuid();
        ObjectNode record = store.record();
        record.put("data", file.get("data").asText());
        store.put(account, region, IMAGE, id, record);
        return mapper.createObjectNode().put("id", id)
                .put("url", store.baseUrl() + SiteWisePortalController.PATH + "/images/" + region + "/" + id);
    }

    ObjectNode describePortal(ObjectNode in, String region) {
        ObjectNode view = SiteWiseStore.view(requirePortal(store.account(), region, in.get("portalId").asText()));
        return view;
    }

    ObjectNode listPortals(ObjectNode in, String region) {
        String account = store.account();
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode stored : store.list(account, region, PORTAL)) {
            ObjectNode portal = settle(account, region, stored);
            if (portal == null) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            summary.put("id", portal.get("portalId").asText());
            summary.put("name", portal.get("portalName").asText());
            if (portal.hasNonNull("portalDescription")) {
                summary.put("description", portal.get("portalDescription").asText());
            }
            summary.put("startUrl", portal.get("portalStartUrl").asText());
            summary.put("creationDate", portal.get("portalCreationDate").asDouble());
            summary.put("lastUpdateDate", portal.get("portalLastUpdateDate").asDouble());
            summary.put("roleArn", portal.get("roleArn").asText());
            summary.set("status", portal.get("portalStatus").deepCopy());
            summary.put("portalType", portal.get("portalType").asText());
            summaries.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "portalSummaries");
        return out;
    }

    ObjectNode updatePortal(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode portal = requirePortal(account, region, in.get("portalId").asText());
        if (!"ACTIVE".equals(portal.path("portalStatus").path("state").asText())) {
            throw conflicting("Portal " + portal.get("portalId").asText() + " isn't ACTIVE.",
                    portal.get("portalId").asText(), portal.get("portalArn").asText());
        }
        checkRole(account, in.get("roleArn").asText());
        portal.put("portalName", in.get("portalName").asText());
        if (in.hasNonNull("portalDescription")) {
            portal.put("portalDescription", in.get("portalDescription").asText());
        } else {
            portal.remove("portalDescription");
        }
        portal.put("portalContactEmail", in.get("portalContactEmail").asText());
        portal.put("roleArn", in.get("roleArn").asText());
        for (String field : List.of("notificationSenderEmail", "alarms", "portalTypeConfiguration")) {
            if (in.has(field)) {
                portal.set(field, in.get(field).deepCopy());
            } else {
                portal.remove(field);
            }
        }
        if (in.hasNonNull("portalType")) {
            portal.put("portalType", in.get("portalType").asText());
        }
        JsonNode logo = in.get("portalLogoImage");
        if (logo != null) {
            if (logo.has("file")) {
                portal.set("portalLogoImageLocation", image(account, region, logo.get("file")));
            } else if (logo.hasNonNull("id")) {
                if (!logo.get("id").asText().equals(portal.path("portalLogoImageLocation").path("id").asText())) {
                    throw invalid("The image " + logo.get("id").asText() + " isn't this portal's logo.");
                }
            } else {
                portal.remove("portalLogoImageLocation");
            }
        }
        portal.put("portalLastUpdateDate", SiteWiseStore.now());
        transition(portal, "UPDATING");
        store.put(account, region, PORTAL, portal.get("portalId").asText(), portal);
        ObjectNode out = mapper.createObjectNode();
        out.set("portalStatus", portal.get("portalStatus").deepCopy());
        return out;
    }

    ObjectNode deletePortal(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode portal = requirePortal(account, region, in.get("portalId").asText());
        String id = portal.get("portalId").asText();
        if (store.list(account, region, PROJECT).stream().anyMatch(p -> id.equals(p.path("portalId").asText()))) {
            throw conflicting("Portal " + id + " still has projects; delete them first.", id,
                    portal.get("portalArn").asText());
        }
        for (ObjectNode policy : store.list(account, region, ACCESS_POLICY)) {
            if (id.equals(policy.path("accessPolicyResource").path("portal").path("id").asText())) {
                store.delete(account, region, ACCESS_POLICY, policy.get("accessPolicyId").asText());
            }
        }
        transition(portal, "DELETING");
        store.put(account, region, PORTAL, id, portal);
        ObjectNode out = mapper.createObjectNode();
        out.set("portalStatus", portal.get("portalStatus").deepCopy());
        return out;
    }

    // ---------------------------------------------------------------- projects

    ObjectNode requireProject(String account, String region, String id) {
        return store.get(account, region, PROJECT, id).orElseThrow(() -> notFound("Project " + id + " not found."));
    }

    ObjectNode createProject(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateProject", in);
        if (repeated != null) {
            return repeated;
        }
        ObjectNode portal = requirePortal(account, region, in.get("portalId").asText());
        String id = SiteWiseStore.uuid();
        double now = SiteWiseStore.now();
        ObjectNode project = store.record();
        project.put("projectId", id);
        project.put("projectArn", store.arn(account, region, PROJECT, id));
        project.put("projectName", in.get("projectName").asText());
        project.put("portalId", portal.get("portalId").asText());
        if (in.hasNonNull("projectDescription")) {
            project.put("projectDescription", in.get("projectDescription").asText());
        }
        project.put("projectCreationDate", now);
        project.put("projectLastUpdateDate", now);
        meta(project).set("tags", SiteWiseStore.tagsOf(mapper, in.get("tags")));
        meta(project).putArray("assets");
        store.put(account, region, PROJECT, id, project);
        ObjectNode out = mapper.createObjectNode().put("projectId", id).put("projectArn", project.get("projectArn")
                .asText());
        store.remember(account, region, "CreateProject", in, out);
        return out;
    }

    ObjectNode describeProject(ObjectNode in, String region) {
        return SiteWiseStore.view(requireProject(store.account(), region, in.get("projectId").asText()));
    }

    ObjectNode listProjects(ObjectNode in, String region) {
        String account = store.account();
        String portalId = requirePortal(account, region, in.get("portalId").asText()).get("portalId").asText();
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode project : store.list(account, region, PROJECT)) {
            if (portalId.equals(project.get("portalId").asText())) {
                ObjectNode summary = mapper.createObjectNode();
                summary.put("id", project.get("projectId").asText());
                summary.put("name", project.get("projectName").asText());
                if (project.hasNonNull("projectDescription")) {
                    summary.put("description", project.get("projectDescription").asText());
                }
                summary.put("creationDate", project.get("projectCreationDate").asDouble());
                summary.put("lastUpdateDate", project.get("projectLastUpdateDate").asDouble());
                summaries.add(summary);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "projectSummaries");
        return out;
    }

    ObjectNode updateProject(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode project = requireProject(account, region, in.get("projectId").asText());
        project.put("projectName", in.get("projectName").asText());
        if (in.hasNonNull("projectDescription")) {
            project.put("projectDescription", in.get("projectDescription").asText());
        } else {
            project.remove("projectDescription");
        }
        project.put("projectLastUpdateDate", SiteWiseStore.now());
        store.put(account, region, PROJECT, project.get("projectId").asText(), project);
        return mapper.createObjectNode();
    }

    ObjectNode deleteProject(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode project = requireProject(account, region, in.get("projectId").asText());
        String id = project.get("projectId").asText();
        if (store.list(account, region, DASHBOARD).stream().anyMatch(d -> id.equals(d.path("projectId").asText()))) {
            throw conflicting("Project " + id + " still has dashboards; delete them first.", id,
                    project.get("projectArn").asText());
        }
        for (ObjectNode policy : store.list(account, region, ACCESS_POLICY)) {
            if (id.equals(policy.path("accessPolicyResource").path("project").path("id").asText())) {
                store.delete(account, region, ACCESS_POLICY, policy.get("accessPolicyId").asText());
            }
        }
        store.delete(account, region, PROJECT, id);
        return mapper.createObjectNode();
    }

    ObjectNode batchAssociateProjectAssets(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode project = requireProject(account, region, in.get("projectId").asText());
        ArrayNode list = (ArrayNode) meta(project).withArray("assets");
        ArrayNode errors = mapper.createArrayNode();
        if (in.get("assetIds").size() > 100) {
            throw invalid("Associate at most 100 assets at a time.");
        }
        for (JsonNode assetId : in.get("assetIds")) {
            String id = assetId.asText();
            if (assets.get().find(account, region, id) == null) {
                errors.addObject().put("assetId", id).put("code", "INTERNAL_FAILURE")
                        .put("message", "Asset " + id + " not found.");
                continue;
            }
            boolean present = false;
            for (JsonNode existing : list) {
                present |= id.equals(existing.asText());
            }
            if (!present) {
                list.add(id);
            }
        }
        store.put(account, region, PROJECT, project.get("projectId").asText(), project);
        ObjectNode out = mapper.createObjectNode();
        if (!errors.isEmpty()) {
            out.set("errors", errors);
        }
        return out;
    }

    ObjectNode batchDisassociateProjectAssets(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode project = requireProject(account, region, in.get("projectId").asText());
        ArrayNode list = (ArrayNode) meta(project).withArray("assets");
        ArrayNode errors = mapper.createArrayNode();
        for (JsonNode assetId : in.get("assetIds")) {
            String id = assetId.asText();
            boolean removed = false;
            for (int i = list.size() - 1; i >= 0; i--) {
                if (id.equals(list.get(i).asText())) {
                    list.remove(i);
                    removed = true;
                }
            }
            if (!removed) {
                errors.addObject().put("assetId", id).put("code", "INTERNAL_FAILURE")
                        .put("message", "Asset " + id + " isn't in the project.");
            }
        }
        store.put(account, region, PROJECT, project.get("projectId").asText(), project);
        ObjectNode out = mapper.createObjectNode();
        if (!errors.isEmpty()) {
            out.set("errors", errors);
        }
        return out;
    }

    ObjectNode listProjectAssets(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode project = requireProject(account, region, in.get("projectId").asText());
        List<JsonNode> ids = new ArrayList<>();
        meta(project).path("assets").forEach(ids::add);
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(ids, in, 50, out, "assetIds");
        return out;
    }

    // ---------------------------------------------------------------- dashboards

    private ObjectNode requireDashboard(String account, String region, String id) {
        return store.get(account, region, DASHBOARD, id).orElseThrow(() -> notFound("Dashboard " + id
                + " not found."));
    }

    /** A dashboard definition: JSON with a widgets list, whose metrics name assets in the project. */
    private void checkDefinition(String account, String region, ObjectNode project, String definition) {
        JsonNode parsed;
        try {
            parsed = mapper.readTree(definition);
        } catch (JsonProcessingException e) {
            throw invalid("The dashboard definition isn't valid JSON: " + e.getOriginalMessage());
        }
        if (parsed == null || !parsed.isObject() || !parsed.path("widgets").isArray()) {
            throw invalid("A dashboard definition is a JSON object with a widgets list.");
        }
        for (JsonNode widget : parsed.get("widgets")) {
            for (String field : List.of("type", "x", "y", "height", "width")) {
                if (!widget.has(field)) {
                    throw invalid("Each dashboard widget has a " + field + ".");
                }
            }
            for (JsonNode metric : widget.path("metrics")) {
                String assetId = metric.path("assetId").asText(null);
                if (assetId == null) {
                    continue;
                }
                ObjectNode asset = assets.get().find(account, region, assetId);
                if (asset == null) {
                    throw invalid("The widget metric names asset " + assetId + ", which doesn't exist.");
                }
                if (metric.hasNonNull("propertyId")) {
                    try {
                        assets.get().property(asset, metric.get("propertyId").asText());
                    } catch (AwsException e) {
                        throw invalid(e.getMessage());
                    }
                }
            }
        }
    }

    ObjectNode createDashboard(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateDashboard", in);
        if (repeated != null) {
            return repeated;
        }
        ObjectNode project = requireProject(account, region, in.get("projectId").asText());
        checkDefinition(account, region, project, in.get("dashboardDefinition").asText());
        String id = SiteWiseStore.uuid();
        double now = SiteWiseStore.now();
        ObjectNode dashboard = store.record();
        dashboard.put("dashboardId", id);
        dashboard.put("dashboardArn", store.arn(account, region, DASHBOARD, id));
        dashboard.put("dashboardName", in.get("dashboardName").asText());
        dashboard.put("projectId", project.get("projectId").asText());
        if (in.hasNonNull("dashboardDescription")) {
            dashboard.put("dashboardDescription", in.get("dashboardDescription").asText());
        }
        dashboard.put("dashboardDefinition", in.get("dashboardDefinition").asText());
        dashboard.put("dashboardCreationDate", now);
        dashboard.put("dashboardLastUpdateDate", now);
        meta(dashboard).set("tags", SiteWiseStore.tagsOf(mapper, in.get("tags")));
        store.put(account, region, DASHBOARD, id, dashboard);
        ObjectNode out = mapper.createObjectNode().put("dashboardId", id)
                .put("dashboardArn", dashboard.get("dashboardArn").asText());
        store.remember(account, region, "CreateDashboard", in, out);
        return out;
    }

    ObjectNode describeDashboard(ObjectNode in, String region) {
        return SiteWiseStore.view(requireDashboard(store.account(), region, in.get("dashboardId").asText()));
    }

    ObjectNode listDashboards(ObjectNode in, String region) {
        String account = store.account();
        String projectId = requireProject(account, region, in.get("projectId").asText()).get("projectId").asText();
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode dashboard : store.list(account, region, DASHBOARD)) {
            if (projectId.equals(dashboard.get("projectId").asText())) {
                ObjectNode summary = mapper.createObjectNode();
                summary.put("id", dashboard.get("dashboardId").asText());
                summary.put("name", dashboard.get("dashboardName").asText());
                if (dashboard.hasNonNull("dashboardDescription")) {
                    summary.put("description", dashboard.get("dashboardDescription").asText());
                }
                summary.put("creationDate", dashboard.get("dashboardCreationDate").asDouble());
                summary.put("lastUpdateDate", dashboard.get("dashboardLastUpdateDate").asDouble());
                summaries.add(summary);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "dashboardSummaries");
        return out;
    }

    ObjectNode updateDashboard(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode dashboard = requireDashboard(account, region, in.get("dashboardId").asText());
        ObjectNode project = requireProject(account, region, dashboard.get("projectId").asText());
        checkDefinition(account, region, project, in.get("dashboardDefinition").asText());
        dashboard.put("dashboardName", in.get("dashboardName").asText());
        if (in.hasNonNull("dashboardDescription")) {
            dashboard.put("dashboardDescription", in.get("dashboardDescription").asText());
        } else {
            dashboard.remove("dashboardDescription");
        }
        dashboard.put("dashboardDefinition", in.get("dashboardDefinition").asText());
        dashboard.put("dashboardLastUpdateDate", SiteWiseStore.now());
        store.put(account, region, DASHBOARD, dashboard.get("dashboardId").asText(), dashboard);
        return mapper.createObjectNode();
    }

    ObjectNode deleteDashboard(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode dashboard = requireDashboard(account, region, in.get("dashboardId").asText());
        store.delete(account, region, DASHBOARD, dashboard.get("dashboardId").asText());
        return mapper.createObjectNode();
    }

    // ---------------------------------------------------------------- access policies

    private void checkPolicy(String account, String region, JsonNode in) {
        JsonNode identity = in.get("accessPolicyIdentity");
        JsonNode resource = in.get("accessPolicyResource");
        if (identity.size() != 1) {
            throw invalid("An access policy's identity is exactly one of user, group, iamUser and iamRole.");
        }
        if (resource.size() != 1) {
            throw invalid("An access policy's resource is exactly one of portal and project.");
        }
        ObjectNode portal;
        if (resource.has("portal")) {
            portal = requirePortal(account, region, resource.get("portal").get("id").asText());
        } else {
            ObjectNode project = requireProject(account, region, resource.get("project").get("id").asText());
            portal = requirePortal(account, region, project.get("portalId").asText());
        }
        boolean iamMode = "IAM".equals(portal.path("portalAuthMode").asText());
        boolean iamIdentity = identity.has("iamUser") || identity.has("iamRole");
        if (iamMode != iamIdentity) {
            throw invalid("A portal with " + portal.path("portalAuthMode").asText() + " authentication takes "
                    + (iamMode ? "IAM users and roles" : "IAM Identity Center users and groups") + ".");
        }
        if (iamIdentity) {
            String arn = (identity.has("iamUser") ? identity.get("iamUser") : identity.get("iamRole")).get("arn")
                    .asText();
            AwsArnUtils.Arn parsed;
            try {
                parsed = AwsArnUtils.parse(arn);
            } catch (RuntimeException e) {
                throw invalid("The ARN " + arn + " isn't valid.");
            }
            String kind = identity.has("iamUser") ? "user/" : "role/";
            if (!"iam".equals(parsed.service()) || !parsed.resource().startsWith(kind)) {
                throw invalid("The ARN " + arn + " isn't an IAM " + kind.substring(0, kind.length() - 1) + ".");
            }
        }
    }

    ObjectNode createAccessPolicy(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateAccessPolicy", in);
        if (repeated != null) {
            return repeated;
        }
        checkPolicy(account, region, in);
        String id = SiteWiseStore.uuid();
        double now = SiteWiseStore.now();
        ObjectNode policy = store.record();
        policy.put("accessPolicyId", id);
        policy.put("accessPolicyArn", store.arn(account, region, ACCESS_POLICY, id));
        policy.set("accessPolicyIdentity", in.get("accessPolicyIdentity").deepCopy());
        policy.set("accessPolicyResource", in.get("accessPolicyResource").deepCopy());
        policy.put("accessPolicyPermission", in.get("accessPolicyPermission").asText());
        policy.put("accessPolicyCreationDate", now);
        policy.put("accessPolicyLastUpdateDate", now);
        meta(policy).set("tags", SiteWiseStore.tagsOf(mapper, in.get("tags")));
        store.put(account, region, ACCESS_POLICY, id, policy);
        ObjectNode out = mapper.createObjectNode().put("accessPolicyId", id)
                .put("accessPolicyArn", policy.get("accessPolicyArn").asText());
        store.remember(account, region, "CreateAccessPolicy", in, out);
        return out;
    }

    private ObjectNode requirePolicy(String account, String region, String id) {
        return store.get(account, region, ACCESS_POLICY, id).orElseThrow(() -> notFound("Access policy " + id
                + " not found."));
    }

    ObjectNode describeAccessPolicy(ObjectNode in, String region) {
        return SiteWiseStore.view(requirePolicy(store.account(), region, in.get("accessPolicyId").asText()));
    }

    ObjectNode updateAccessPolicy(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode policy = requirePolicy(account, region, in.get("accessPolicyId").asText());
        checkPolicy(account, region, in);
        policy.set("accessPolicyIdentity", in.get("accessPolicyIdentity").deepCopy());
        policy.set("accessPolicyResource", in.get("accessPolicyResource").deepCopy());
        policy.put("accessPolicyPermission", in.get("accessPolicyPermission").asText());
        policy.put("accessPolicyLastUpdateDate", SiteWiseStore.now());
        store.put(account, region, ACCESS_POLICY, policy.get("accessPolicyId").asText(), policy);
        return mapper.createObjectNode();
    }

    ObjectNode deleteAccessPolicy(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode policy = requirePolicy(account, region, in.get("accessPolicyId").asText());
        store.delete(account, region, ACCESS_POLICY, policy.get("accessPolicyId").asText());
        return mapper.createObjectNode();
    }

    ObjectNode listAccessPolicies(ObjectNode in, String region) {
        String account = store.account();
        String identityType = in.path("identityType").asText(null);
        String identityId = in.path("identityId").asText(null);
        String resourceType = in.path("resourceType").asText(null);
        String resourceId = in.path("resourceId").asText(null);
        String iamArn = in.path("iamArn").asText(null);
        if (("USER".equals(identityType) || "GROUP".equals(identityType)) && identityId == null) {
            throw invalid("An identityType of USER or GROUP needs an identityId.");
        }
        if ((resourceType == null) != (resourceId == null)) {
            throw invalid("A resourceType and a resourceId go together.");
        }
        if ("IAM".equals(identityType) && iamArn == null) {
            throw invalid("An identityType of IAM needs an iamArn.");
        }
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode policy : store.list(account, region, ACCESS_POLICY)) {
            JsonNode identity = policy.get("accessPolicyIdentity");
            JsonNode resource = policy.get("accessPolicyResource");
            if (identityType != null) {
                boolean match = switch (identityType) {
                    case "USER" -> identityId.equals(identity.path("user").path("id").asText(null));
                    case "GROUP" -> identityId.equals(identity.path("group").path("id").asText(null));
                    default -> iamArn.equals(identity.path("iamUser").path("arn").asText(null))
                            || iamArn.equals(identity.path("iamRole").path("arn").asText(null));
                };
                if (!match) {
                    continue;
                }
            }
            if (resourceType != null && !resourceId.equals(resource.path(resourceType.toLowerCase(Locale.ROOT))
                    .path("id").asText(null))) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            summary.put("id", policy.get("accessPolicyId").asText());
            summary.set("identity", identity.deepCopy());
            summary.set("resource", resource.deepCopy());
            summary.put("permission", policy.get("accessPolicyPermission").asText());
            summary.put("creationDate", policy.get("accessPolicyCreationDate").asDouble());
            summary.put("lastUpdateDate", policy.get("accessPolicyLastUpdateDate").asDouble());
            summaries.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "accessPolicySummaries");
        return out;
    }

    /** The portal, its projects, their dashboards and assets, for the portal's start page. */
    Map<String, Object> portalPage(String account, String region, String portalId) {
        ObjectNode portal = requirePortal(account, region, portalId);
        List<Map<String, Object>> projects = new ArrayList<>();
        for (ObjectNode project : store.list(account, region, PROJECT)) {
            if (!portalId.equals(project.get("portalId").asText())) {
                continue;
            }
            List<String> dashboards = new ArrayList<>();
            for (ObjectNode dashboard : store.list(account, region, DASHBOARD)) {
                if (project.get("projectId").asText().equals(dashboard.get("projectId").asText())) {
                    dashboards.add(dashboard.get("dashboardName").asText());
                }
            }
            List<String> assetNames = new ArrayList<>();
            meta(project).path("assets").forEach(id -> {
                ObjectNode asset = assets.get().find(account, region, id.asText());
                if (asset != null) {
                    assetNames.add(asset.get("assetName").asText());
                }
            });
            projects.add(Map.of("name", project.get("projectName").asText(), "dashboards", dashboards,
                    "assets", assetNames));
        }
        return Map.of("name", portal.get("portalName").asText(), "projects", projects,
                "logo", portal.path("portalLogoImageLocation").path("url").asText(""));
    }

    byte[] image(String account, String region, String id) {
        return store.get(account, region, IMAGE, id).map(r -> Base64.getDecoder().decode(r.get("data").asText()))
                .orElse(null);
    }
}
