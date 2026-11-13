package io.floci.aws.services.s3;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * S3 Access Grants: one instance per account and Region, its registered locations (each with the
 * IAM role that vends access to it), and grants of READ, WRITE or READWRITE on a location's
 * prefixes to IAM principals or Identity Center users and groups. {@code GetDataAccess} matches the
 * caller to its narrowest grant and returns temporary credentials for the location's role, limited
 * by a session policy to the granted prefix; the credentials are registered with IAM, so they sign
 * real S3 requests.
 */
@ApplicationScoped
public class S3AccessGrantsService implements Resettable {
    private static final Set<String> PERMISSIONS = Set.of("READ", "WRITE", "READWRITE");
    private static final Set<String> GRANTEE_TYPES = Set.of("IAM", "DIRECTORY_USER", "DIRECTORY_GROUP");
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final AccountAwareStorageBackend<ObjectNode> instances;
    private final AccountAwareStorageBackend<ObjectNode> locations;
    private final AccountAwareStorageBackend<ObjectNode> grants;
    private final Instance<IamService> iam;
    private final Instance<RequestContext> requestContext;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public S3AccessGrantsService(StorageFactory storageFactory, Instance<IamService> iam,
                                 Instance<RequestContext> requestContext, RegionResolver regionResolver,
                                 ObjectMapper mapper) {
        TypeReference<Map<String, ObjectNode>> type = new TypeReference<>() {};
        this.instances = storageFactory.create("s3", "s3control-access-grants-instances.json", type);
        this.locations = storageFactory.create("s3", "s3control-access-grants-locations.json", type);
        this.grants = storageFactory.create("s3", "s3control-access-grants.json", type);
        this.iam = iam;
        this.requestContext = requestContext;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    // ── Instance ───────────────────────────────────────────────────────────

    public synchronized ObjectNode createInstance(ObjectNode in, String region) {
        if (instances.get(region).isPresent()) {
            throw new AwsException("AccessGrantsInstanceAlreadyExists",
                    "An Access Grants instance already exists in this account and Region.", 409);
        }
        ObjectNode instance = mapper.createObjectNode();
        instance.put("AccessGrantsInstanceId", "default");
        instance.put("AccessGrantsInstanceArn", arn(region, "access-grants/default"));
        instance.put("CreatedAt", Instant.now().toString());
        identityCenter(instance, in.path("IdentityCenterArn").asText(null));
        instance.set("Tags", tagMap(in.path("Tags")));
        instances.put(region, instance);
        return view(instance);
    }

    private void identityCenter(ObjectNode instance, String identityCenterArn) {
        if (identityCenterArn == null || identityCenterArn.isEmpty()) {
            instance.remove(List.of("IdentityCenterArn", "IdentityCenterInstanceArn", "IdentityCenterApplicationArn"));
            return;
        }
        if (!AwsArnUtils.isArnFor(identityCenterArn, "sso")) {
            throw invalid("IdentityCenterArn must be an IAM Identity Center instance ARN.");
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(identityCenterArn);
        instance.put("IdentityCenterArn", identityCenterArn);
        instance.put("IdentityCenterInstanceArn", identityCenterArn);
        instance.put("IdentityCenterApplicationArn", AwsArnUtils.Arn.global(regionResolver.getPartition(), "sso", regionResolver.getAccountId(),
                "application/" + parsed.resource().substring(parsed.resource().lastIndexOf('/') + 1) + "/apl-"
                        + randomText(16).toLowerCase()).toString());
    }

    public ObjectNode getInstance(String region) {
        return view(requireInstance(region));
    }

    public synchronized void deleteInstance(String region) {
        requireInstance(region);
        if (!locations.scan(k -> k.startsWith(region + "::")).isEmpty()) {
            throw new AwsException("AccessGrantsInstanceNotEmptyError",
                    "Delete the instance's locations and grants first.", 400);
        }
        instances.delete(region);
    }

    public synchronized void associateIdentityCenter(String identityCenterArn, String region) {
        ObjectNode instance = requireInstance(region);
        identityCenter(instance, identityCenterArn);
        instances.put(region, instance);
    }

    public synchronized void dissociateIdentityCenter(String region) {
        ObjectNode instance = requireInstance(region);
        identityCenter(instance, null);
        instances.put(region, instance);
    }

    public ObjectNode listInstances(ObjectNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("AccessGrantsInstancesList");
        instances.get(region).ifPresent(instance -> list.add(view(instance)));
        return out;
    }

    public ObjectNode instanceForPrefix(String prefix, String region) {
        ObjectNode instance = requireInstance(region);
        if (bestLocation(prefix, region).isEmpty()) {
            throw new AwsException("NoSuchAccessGrantsLocationError",
                    "No Access Grants location covers " + prefix + ".", 404);
        }
        return mapper.createObjectNode().put("AccessGrantsInstanceArn", instance.path("AccessGrantsInstanceArn")
                .asText()).put("AccessGrantsInstanceId", "default");
    }

    public synchronized ObjectNode putResourcePolicy(String policy, String organization, String region) {
        ObjectNode instance = requireInstance(region);
        try {
            JsonNode document = mapper.readTree(policy == null ? "" : policy);
            if (document == null || !document.has("Statement")) {
                throw new IllegalArgumentException();
            }
        } catch (Exception e) {
            throw new AwsException("MalformedPolicy", "Policies must be valid JSON with a Statement.", 400);
        }
        ObjectNode resourcePolicy = instance.putObject("ResourcePolicy");
        resourcePolicy.put("Policy", policy);
        if (organization != null) {
            resourcePolicy.put("Organization", organization);
        }
        resourcePolicy.put("CreatedAt", Instant.now().toString());
        instances.put(region, instance);
        return resourcePolicy.deepCopy();
    }

    public ObjectNode getResourcePolicy(String region) {
        JsonNode policy = requireInstance(region).path("ResourcePolicy");
        if (!policy.isObject()) {
            throw new AwsException("AccessGrantsInstanceResourcePolicyNotExists",
                    "The Access Grants instance has no resource policy.", 404);
        }
        return policy.deepCopy();
    }

    public synchronized void deleteResourcePolicy(String region) {
        ObjectNode instance = requireInstance(region);
        instance.remove("ResourcePolicy");
        instances.put(region, instance);
    }

    private ObjectNode requireInstance(String region) {
        return instances.get(region).orElseThrow(() -> new AwsException("AccessGrantsInstanceNotExistsError",
                "There is no Access Grants instance in this account and Region.", 404));
    }

    // ── Locations ──────────────────────────────────────────────────────────

    public synchronized ObjectNode createLocation(ObjectNode in, String region) {
        requireInstance(region);
        String scope = in.path("LocationScope").asText("");
        if (!scope.startsWith("s3://")) {
            throw invalid("LocationScope must be s3:// or an s3://bucket/prefix location.");
        }
        for (ObjectNode existing : locations.scan(k -> k.startsWith(region + "::"))) {
            if (scope.equals(existing.path("LocationScope").asText())) {
                throw new AwsException("AccessGrantsLocationAlreadyExistsError",
                        "A location is already registered for " + scope + ".", 409);
            }
        }
        requireRole(in.path("IAMRoleArn").asText(null));
        String id = "s3://".equals(scope) ? "default" : UUID.randomUUID().toString();
        ObjectNode location = mapper.createObjectNode();
        location.put("CreatedAt", Instant.now().toString());
        location.put("AccessGrantsLocationId", id);
        location.put("AccessGrantsLocationArn", arn(region, "access-grants/default/location/" + id));
        location.put("LocationScope", scope);
        location.put("IAMRoleArn", in.path("IAMRoleArn").asText());
        location.set("Tags", tagMap(in.path("Tags")));
        locations.put(region + "::" + id, location);
        return view(location);
    }

    public ObjectNode getLocation(String id, String region) {
        return view(requireLocation(id, region));
    }

    public synchronized ObjectNode updateLocation(String id, String roleArn, String region) {
        ObjectNode location = requireLocation(id, region);
        requireRole(roleArn);
        location.put("IAMRoleArn", roleArn);
        locations.put(region + "::" + id, location);
        return view(location);
    }

    public synchronized void deleteLocation(String id, String region) {
        requireLocation(id, region);
        boolean granted = grants.scan(k -> k.startsWith(region + "::")).stream()
                .anyMatch(grant -> id.equals(grant.path("AccessGrantsLocationId").asText()));
        if (granted) {
            throw new AwsException("AccessGrantsLocationNotEmptyError",
                    "Delete the location's grants first.", 400);
        }
        locations.delete(region + "::" + id);
    }

    public ObjectNode listLocations(ObjectNode in, String region) {
        String scope = in.path("LocationScope").asText(null);
        List<ObjectNode> all = locations.scan(k -> k.startsWith(region + "::")).stream()
                .filter(location -> scope == null || location.path("LocationScope").asText().startsWith(scope))
                .sorted(Comparator.comparing(location -> location.path("CreatedAt").asText()))
                .map(this::view).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("AccessGrantsLocationsList");
        page(all, in, out).forEach(list::add);
        return out;
    }

    private ObjectNode requireLocation(String id, String region) {
        requireInstance(region);
        return locations.get(region + "::" + id).orElseThrow(() -> new AwsException(
                "AccessGrantsLocationNotExistsError", "The Access Grants location " + id + " does not exist.", 404));
    }

    private Optional<ObjectNode> bestLocation(String target, String region) {
        return locations.scan(k -> k.startsWith(region + "::")).stream()
                .filter(location -> covers(location.path("LocationScope").asText(), target))
                .max(Comparator.comparingInt(location -> location.path("LocationScope").asText().length()));
    }

    // ── Grants ─────────────────────────────────────────────────────────────

    public synchronized ObjectNode createGrant(ObjectNode in, String region) {
        ObjectNode location = requireLocation(in.path("AccessGrantsLocationId").asText(""), region);
        String permission = in.path("Permission").asText("");
        if (!PERMISSIONS.contains(permission)) {
            throw invalid("Permission must be READ, WRITE or READWRITE.");
        }
        JsonNode grantee = in.path("Grantee");
        String granteeType = grantee.path("GranteeType").asText("");
        String granteeId = grantee.path("GranteeIdentifier").asText("");
        if (!GRANTEE_TYPES.contains(granteeType) || granteeId.isEmpty()) {
            throw invalid("Grantee needs a GranteeType of IAM, DIRECTORY_USER or DIRECTORY_GROUP and an identifier.");
        }
        if ("IAM".equals(granteeType) && !AwsArnUtils.isArnFor(granteeId, "iam")) {
            throw invalid("An IAM grantee is identified by its IAM user or role ARN.");
        }
        if (!"IAM".equals(granteeType) && !requireInstance(region).has("IdentityCenterArn")) {
            throw invalid("Directory grantees need an Access Grants instance associated with IAM Identity Center.");
        }
        String subPrefix = in.path("AccessGrantsLocationConfiguration").path("S3SubPrefix").asText("");
        String locationScope = location.path("LocationScope").asText();
        String scope = locationScope + ("s3://".equals(locationScope) || subPrefix.isEmpty() || locationScope
                .endsWith("/") ? "" : "/") + subPrefix;
        if ("Object".equals(in.path("S3PrefixType").asText(null)) && scope.endsWith("*")) {
            throw invalid("An Object grant scope names one object, without a wildcard.");
        }
        if (!"Object".equals(in.path("S3PrefixType").asText(null)) && !scope.endsWith("*")) {
            scope = scope.endsWith("/") || "s3://".equals(scope) ? scope + "*" : scope + "/*";
        }
        String id = UUID.randomUUID().toString();
        ObjectNode grant = mapper.createObjectNode();
        grant.put("CreatedAt", Instant.now().toString());
        grant.put("AccessGrantId", id);
        grant.put("AccessGrantArn", arn(region, "access-grants/default/grant/" + id));
        grant.set("Grantee", grantee.deepCopy());
        grant.put("Permission", permission);
        grant.put("AccessGrantsLocationId", location.path("AccessGrantsLocationId").asText());
        grant.putObject("AccessGrantsLocationConfiguration").put("S3SubPrefix", subPrefix);
        grant.put("GrantScope", scope);
        if (in.hasNonNull("ApplicationArn")) {
            grant.put("ApplicationArn", in.get("ApplicationArn").asText());
        }
        grant.set("Tags", tagMap(in.path("Tags")));
        grants.put(region + "::" + id, grant);
        return view(grant);
    }

    public ObjectNode getGrant(String id, String region) {
        return view(requireGrant(id, region));
    }

    public synchronized void deleteGrant(String id, String region) {
        requireGrant(id, region);
        grants.delete(region + "::" + id);
    }

    public ObjectNode listGrants(ObjectNode in, String region) {
        requireInstance(region);
        List<ObjectNode> all = grants.scan(k -> k.startsWith(region + "::")).stream()
                .filter(grant -> matches(in, "GranteeType", grant.path("Grantee").path("GranteeType"))
                        && matches(in, "GranteeIdentifier", grant.path("Grantee").path("GranteeIdentifier"))
                        && matches(in, "Permission", grant.path("Permission"))
                        && matches(in, "GrantScope", grant.path("GrantScope"))
                        && matches(in, "ApplicationArn", grant.path("ApplicationArn")))
                .sorted(Comparator.comparing(grant -> grant.path("CreatedAt").asText()))
                .map(this::view).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("AccessGrantsList");
        page(all, in, out).forEach(list::add);
        return out;
    }

    private static boolean matches(ObjectNode filter, String field, JsonNode value) {
        return !filter.hasNonNull(field) || filter.get(field).asText().equals(value.asText());
    }

    private ObjectNode requireGrant(String id, String region) {
        requireInstance(region);
        return grants.get(region + "::" + id).orElseThrow(() -> new AwsException("AccessGrantNotExistsError",
                "The access grant " + id + " does not exist.", 404));
    }

    /** The grants that name the caller, optionally limited to those covering a scope. */
    public ObjectNode listCallerGrants(ObjectNode in, String region) {
        requireInstance(region);
        String scope = in.path("GrantScope").asText(null);
        List<ObjectNode> all = callerGrants(region).stream()
                .filter(grant -> scope == null || covers(grant.path("GrantScope").asText(), scope)
                        || covers(scope, grant.path("GrantScope").asText()))
                .map(grant -> {
                    ObjectNode entry = mapper.createObjectNode();
                    entry.put("Permission", grant.path("Permission").asText());
                    entry.put("GrantScope", grant.path("GrantScope").asText());
                    if (grant.has("ApplicationArn")) {
                        entry.put("ApplicationArn", grant.path("ApplicationArn").asText());
                    }
                    return entry;
                }).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("CallerAccessGrantsList");
        page(all, in, out).forEach(list::add);
        return out;
    }

    /**
     * Temporary credentials for the narrowest grant that gives the caller the requested permission
     * on the target, assumed from the grant's location role and limited to the grant's scope (or to
     * the target itself with {@code Privilege=Minimal}).
     */
    public ObjectNode getDataAccess(ObjectNode in, String region) {
        requireInstance(region);
        String target = in.path("Target").asText("");
        String permission = in.path("Permission").asText("");
        if (!target.startsWith("s3://") || !PERMISSIONS.contains(permission)) {
            throw invalid("GetDataAccess needs an s3:// Target and a Permission of READ, WRITE or READWRITE.");
        }
        long duration = in.path("DurationSeconds").asLong(3600);
        if (duration < 900 || duration > 43200) {
            throw invalid("DurationSeconds must be between 900 and 43200.");
        }
        ObjectNode grant = callerGrants(region).stream()
                .filter(g -> covers(g.path("GrantScope").asText(), target))
                .filter(g -> "READWRITE".equals(g.path("Permission").asText())
                        || permission.equals(g.path("Permission").asText()))
                .max(Comparator.comparingInt(g -> g.path("GrantScope").asText().length()))
                .orElseThrow(() -> new AwsException("AccessDenied", "You do not have a grant that gives "
                        + permission + " access to " + target + ".", 403));
        ObjectNode location = requireLocation(grant.path("AccessGrantsLocationId").asText(), region);
        String matched = "Minimal".equals(in.path("Privilege").asText("Default")) ? target
                : grant.path("GrantScope").asText();
        String accessKeyId = "ASIA" + randomText(16).toUpperCase();
        String secret = randomText(40);
        String token = randomText(356);
        Instant expiration = Instant.now().plusSeconds(duration);
        if (iam.isResolvable()) {
            iam.get().registerSession(accessKeyId, secret, token, location.path("IAMRoleArn").asText(), expiration,
                    sessionPolicy(matched, permission), regionResolver.getAccountId());
        }
        ObjectNode out = mapper.createObjectNode();
        out.putObject("Credentials").put("AccessKeyId", accessKeyId).put("SecretAccessKey", secret)
                .put("SessionToken", token).put("Expiration", expiration.toString());
        out.put("MatchedGrantTarget", matched);
        out.set("Grantee", grant.path("Grantee").deepCopy());
        return out;
    }

    /** A session policy granting the permission's object actions on the matched scope. */
    private String sessionPolicy(String scope, String permission) {
        String path = scope.substring("s3://".length());
        String bucket = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
        String resource = path.isEmpty() || "*".equals(path) ? "*" : AwsArnUtils.Arn.global(regionResolver.getPartition(), "s3", "", path)
                .toString();
        ObjectNode policy = mapper.createObjectNode().put("Version", "2012-10-17");
        ArrayNode statements = policy.putArray("Statement");
        ArrayNode objectActions = mapper.createArrayNode();
        if (!"WRITE".equals(permission)) {
            objectActions.add("s3:GetObject").add("s3:GetObjectVersion").add("s3:GetObjectAcl");
        }
        if (!"READ".equals(permission)) {
            objectActions.add("s3:PutObject").add("s3:DeleteObject").add("s3:AbortMultipartUpload");
        }
        statements.addObject().put("Effect", "Allow").<ObjectNode>set("Action", objectActions)
                .put("Resource", resource);
        statements.addObject().put("Effect", "Allow").put("Action", "s3:ListBucket")
                .put("Resource", bucket.isEmpty() || "*".equals(bucket) ? "*"
                        : AwsArnUtils.Arn.global(regionResolver.getPartition(), "s3", "", bucket).toString());
        return policy.toString();
    }

    private List<ObjectNode> callerGrants(String region) {
        Set<String> identities = callerIdentities();
        List<ObjectNode> found = new ArrayList<>();
        for (ObjectNode grant : grants.scan(k -> k.startsWith(region + "::"))) {
            JsonNode grantee = grant.path("Grantee");
            if ("IAM".equals(grantee.path("GranteeType").asText())
                    && identities.contains(grantee.path("GranteeIdentifier").asText())) {
                found.add(grant);
            }
        }
        return found;
    }

    /** The caller's IAM identities: its user or session ARN, the role behind a session, and its account root. */
    private Set<String> callerIdentities() {
        String accountId = regionResolver.getAccountId();
        Set<String> identities = new HashSet<>();
        identities.add(AwsArnUtils.Arn.global(regionResolver.getPartition(), "iam", accountId, "root").toString());
        String accessKeyId = null;
        if (requestContext.isResolvable()) {
            try {
                accessKeyId = requestContext.get().getAccessKeyId();
            } catch (ContextNotActiveException ignored) {
                // outside a request: only the account root
            }
        }
        if (accessKeyId != null && iam.isResolvable()) {
            iam.get().resolveCallerArns(accessKeyId).ifPresent(caller -> {
                identities.add(caller.callerArn());
                identities.add(caller.principalArn());
            });
        }
        return identities;
    }

    /** Whether a scope ({@code s3://}, {@code s3://bucket/prefix*} or an object) covers a target. */
    static boolean covers(String scope, String target) {
        if ("s3://".equals(scope) || "s3://*".equals(scope)) {
            return true;
        }
        String base = scope.endsWith("*") ? scope.substring(0, scope.length() - 1) : scope;
        String wanted = target.endsWith("*") ? target.substring(0, target.length() - 1) : target;
        if (!scope.endsWith("*")) {
            return wanted.equals(base) || wanted.startsWith(base.endsWith("/") ? base : base + "/");
        }
        return wanted.startsWith(base) || (base.endsWith("/") && wanted.equals(base.substring(0, base.length() - 1)));
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    /** The stored instance, location or grant an Access Grants ARN names, with its store and key. */
    S3ControlResourceService.Tagged tagged(String arn, String region) {
        if (!AwsArnUtils.isArnFor(arn, "s3")) {
            return null;
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String resource = parsed.resource();
        String arnRegion = parsed.region().isEmpty() ? region : parsed.region();
        if ("access-grants/default".equals(resource)) {
            return instances.get(arnRegion).map(i -> new S3ControlResourceService.Tagged(instances, arnRegion, i))
                    .orElse(null);
        }
        for (String kind : List.of("location", "grant")) {
            String prefix = "access-grants/default/" + kind + "/";
            if (resource.startsWith(prefix)) {
                AccountAwareStorageBackend<ObjectNode> store = "grant".equals(kind) ? grants : locations;
                String key = arnRegion + "::" + resource.substring(prefix.length());
                return store.get(key).map(r -> new S3ControlResourceService.Tagged(store, key, r)).orElse(null);
            }
        }
        return null;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void requireRole(String roleArn) {
        if (roleArn == null || !AwsArnUtils.isArnFor(roleArn, "iam") || !roleArn.contains(":role/")) {
            throw invalid("IAMRoleArn must be an IAM role ARN.");
        }
    }

    private ObjectNode view(ObjectNode resource) {
        ObjectNode copy = resource.deepCopy();
        copy.remove(List.of("Tags", "ResourcePolicy"));
        return copy;
    }

    private ObjectNode tagMap(JsonNode tags) {
        ObjectNode map = mapper.createObjectNode();
        tags.forEach(tag -> map.put(tag.path("Key").asText(), tag.path("Value").asText("")));
        return map;
    }

    private static List<ObjectNode> page(List<ObjectNode> all, JsonNode in, ObjectNode out) {
        int max = in.path("MaxResults").asInt(1000);
        if (max < 1 || max > 1000) {
            max = 1000;
        }
        int start = 0;
        if (in.hasNonNull("NextToken")) {
            try {
                start = Integer.parseInt(in.get("NextToken").asText());
            } catch (NumberFormatException e) {
                throw invalid("The continuation token provided is incorrect.");
            }
        }
        int end = Math.min(all.size(), start + max);
        if (end < all.size()) {
            out.put("NextToken", Integer.toString(end));
        }
        return all.subList(Math.min(start, end), end);
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of("s3", region, regionResolver.getAccountId(), resource).toString();
    }

    private String randomText(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        return out.toString();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequest", message, 400);
    }

    @Override
    public void clear() {
        instances.clear();
        locations.clear();
        grants.clear();
    }
}
