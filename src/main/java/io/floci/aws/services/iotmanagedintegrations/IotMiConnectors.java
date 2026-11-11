package io.floci.aws.services.iotmanagedintegrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.floci.aws.services.iotmanagedintegrations.IotMiStore.ASSOCIATION;
import static io.floci.aws.services.iotmanagedintegrations.IotMiStore.CONNECTOR;
import static io.floci.aws.services.iotmanagedintegrations.IotMiStore.CONNECTOR_DESTINATION;
import static io.floci.aws.services.iotmanagedintegrations.IotMiStore.DISCOVERY;
import static io.floci.aws.services.iotmanagedintegrations.IotMiStore.THING;
import static io.floci.aws.services.iotmanagedintegrations.IotMiStore.meta;
import static io.floci.aws.services.iotmanagedintegrations.IotMiStore.view;

/**
 * Cloud-to-cloud integration: cloud connectors (a Lambda function implementing the connector interface), connector
 * destinations (how an end user authorizes the third-party cloud: OAuth 2.0 or general authorization), account
 * associations (one end user's authorization), device discovery, and commands. Floci invokes the connector as
 * managed integrations does, with a {@code header.auth} and a {@code payload} naming the operation
 * ({@code AWS.ActivateUser}, {@code AWS.DiscoverDevices}, {@code AWS.SendCommand}, {@code AWS.DeactivateUser}),
 * and the connector reports back through {@code SendConnectorEvent}. Devices behind a hub would be discovered by
 * the hub's device SDK, which doesn't run in Floci, so a hub's discovery fails.
 */
@ApplicationScoped
public class IotMiConnectors {

    private static final Logger LOG = Logger.getLogger(IotMiConnectors.class);
    static final String CALLBACK_PATH = "/_floci/iotmanagedintegrations/oauth/callback";
    /** The connector interface's built-in commands for writing and reading a capability's properties. */
    private static final String UPDATE_STATE = "0xff01";
    private static final String READ_STATE = "0xff02";
    private static final int CONNECTOR_ATTEMPTS = 6;

    private final IotMiStore store;
    private final IotMiService things;
    private final IotMiSchemas schemas;
    private final IotMiNotifications notifications;
    private final Instance<LambdaService> lambda;
    private final Instance<SecretsManagerService> secrets;
    private final EmulatorConfig config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService invoker = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "iotmanagedintegrations-connectors");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public IotMiConnectors(IotMiStore store, IotMiService things, IotMiSchemas schemas,
                           IotMiNotifications notifications, Instance<LambdaService> lambda,
                           Instance<SecretsManagerService> secrets, EmulatorConfig config) {
        this.store = store;
        this.things = things;
        this.schemas = schemas;
        this.notifications = notifications;
        this.lambda = lambda;
        this.secrets = secrets;
        this.config = config;
    }

    @PreDestroy
    void stop() {
        invoker.shutdownNow();
    }

    // ---------------------------------------------------------------- cloud connectors

    public ObjectNode createCloudConnector(JsonNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateCloudConnector", in);
        if (repeated != null) {
            return repeated;
        }
        String lambdaArn = in.path("EndpointConfig").path("lambda").path("arn").asText();
        checkFunction(account, region, lambdaArn);
        String id = IotMiStore.id();
        ObjectNode record = store.record();
        record.put("Id", id).put("Name", in.path("Name").asText()).put("EndpointType",
                in.path("EndpointType").asText("LAMBDA")).put("Type", "UNLISTED");
        record.set("EndpointConfig", in.path("EndpointConfig").deepCopy());
        if (in.hasNonNull("Description")) {
            record.put("Description", in.path("Description").asText());
        }
        store.put(account, region, CONNECTOR, id, record);
        ObjectNode out = store.mapper().createObjectNode().put("Id", id);
        store.remember(account, region, "CreateCloudConnector", in, out);
        return out;
    }

    public ObjectNode getCloudConnector(String id, String region) {
        return view(store.require(store.account(), region, CONNECTOR, id, "cloud connector"));
    }

    public ObjectNode updateCloudConnector(String id, JsonNode in, String region) {
        String account = store.account();
        ObjectNode record = store.require(account, region, CONNECTOR, id, "cloud connector");
        for (String field : List.of("Name", "Description")) {
            if (in.hasNonNull(field)) {
                record.put(field, in.path(field).asText());
            }
        }
        store.put(account, region, CONNECTOR, id, record);
        return store.mapper().createObjectNode();
    }

    public ObjectNode deleteCloudConnector(String id, String region) {
        String account = store.account();
        store.require(account, region, CONNECTOR, id, "cloud connector");
        for (ObjectNode destination : store.list(account, region, CONNECTOR_DESTINATION)) {
            if (id.equals(destination.path("CloudConnectorId").asText())) {
                throw IotMiStore.conflict("The cloud connector " + id + " has connector destination "
                        + destination.path("Id").asText() + ".");
            }
        }
        store.delete(account, region, CONNECTOR, id);
        return store.mapper().createObjectNode();
    }

    public ObjectNode listCloudConnectors(String type, String lambdaArn, String token, Integer max, String region) {
        List<JsonNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(store.account(), region, CONNECTOR)) {
            if ((type != null && !type.equals(record.path("Type").asText())) || (lambdaArn != null
                    && !lambdaArn.equals(record.path("EndpointConfig").path("lambda").path("arn").asText()))) {
                continue;
            }
            items.add(view(record));
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotMiStore.page(items, token, max, out, "Items");
        return out;
    }

    private void checkFunction(String account, String region, String arn) {
        try {
            RequestScopes.runAs(account, region, () -> lambda.get().getFunction(region, arn));
        } catch (AwsException e) {
            throw IotMiStore.validation("The connector's Lambda function " + arn + " doesn't exist.");
        }
    }

    // ---------------------------------------------------------------- connector destinations

    public ObjectNode createConnectorDestination(JsonNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateConnectorDestination", in);
        if (repeated != null) {
            return repeated;
        }
        String connector = in.path("CloudConnectorId").asText();
        store.require(account, region, CONNECTOR, connector, "cloud connector");
        JsonNode auth = in.path("AuthConfig");
        if (auth.has("oAuth") == auth.has("GeneralAuthorization")) {
            throw IotMiStore.validation("AuthConfig is oAuth or GeneralAuthorization.");
        }
        if (auth.has("oAuth") && !in.has("SecretsManager")) {
            throw IotMiStore.validation("An OAuth connector destination needs the Secrets Manager secret holding the "
                    + "client ID and secret.");
        }
        String id = IotMiStore.id();
        ObjectNode record = store.record();
        record.put("Id", id).put("CloudConnectorId", connector).put("AuthType", in.path("AuthType").asText("OAUTH"));
        for (String field : List.of("Name", "Description")) {
            if (in.hasNonNull(field)) {
                record.put(field, in.path(field).asText());
            }
        }
        record.set("AuthConfig", auth.deepCopy());
        if (in.has("SecretsManager")) {
            record.set("SecretsManager", in.get("SecretsManager").deepCopy());
        }
        record.put("OAuthCompleteRedirectUrl", auth.path("oAuth").path("oAuthCompleteRedirectUrl")
                .asText(config.effectiveBaseUrl() + CALLBACK_PATH + "/complete"));
        store.put(account, region, CONNECTOR_DESTINATION, id, record);
        ObjectNode out = store.mapper().createObjectNode().put("Id", id);
        store.remember(account, region, "CreateConnectorDestination", in, out);
        return out;
    }

    public ObjectNode getConnectorDestination(String id, String region) {
        return view(store.require(store.account(), region, CONNECTOR_DESTINATION, id, "connector destination"));
    }

    public ObjectNode updateConnectorDestination(String id, JsonNode in, String region) {
        String account = store.account();
        ObjectNode record = store.require(account, region, CONNECTOR_DESTINATION, id, "connector destination");
        for (String field : List.of("Name", "Description", "AuthType")) {
            if (in.hasNonNull(field)) {
                record.put(field, in.path(field).asText());
            }
        }
        if (in.has("SecretsManager")) {
            record.set("SecretsManager", in.get("SecretsManager").deepCopy());
        }
        JsonNode update = in.path("AuthConfig");
        ObjectNode auth = (ObjectNode) record.with("AuthConfig");
        if (update.has("oAuthUpdate")) {
            ObjectNode oauth = (ObjectNode) auth.with("oAuth");
            update.path("oAuthUpdate").fields().forEachRemaining(field -> oauth.set(field.getKey(),
                    field.getValue()));
            if (update.path("oAuthUpdate").has("oAuthCompleteRedirectUrl")) {
                record.put("OAuthCompleteRedirectUrl", update.path("oAuthUpdate").path("oAuthCompleteRedirectUrl")
                        .asText());
            }
        }
        if (update.has("GeneralAuthorizationUpdate")) {
            ArrayNode materials = (ArrayNode) auth.withArray("GeneralAuthorization");
            for (String list : List.of("AuthMaterialsToAdd", "AuthMaterialsToUpdate")) {
                for (JsonNode material : update.path("GeneralAuthorizationUpdate").path(list)) {
                    for (int i = materials.size() - 1; i >= 0; i--) {
                        if (materials.get(i).path("AuthMaterialName").asText()
                                .equals(material.path("AuthMaterialName").asText())) {
                            materials.remove(i);
                        }
                    }
                    materials.add(material.deepCopy());
                }
            }
        }
        store.put(account, region, CONNECTOR_DESTINATION, id, record);
        return store.mapper().createObjectNode();
    }

    public ObjectNode deleteConnectorDestination(String id, String region) {
        String account = store.account();
        store.require(account, region, CONNECTOR_DESTINATION, id, "connector destination");
        for (ObjectNode association : store.list(account, region, ASSOCIATION)) {
            if (id.equals(association.path("ConnectorDestinationId").asText())) {
                throw IotMiStore.conflict("The connector destination " + id + " has account association "
                        + association.path("AccountAssociationId").asText() + ".");
            }
        }
        store.delete(account, region, CONNECTOR_DESTINATION, id);
        return store.mapper().createObjectNode();
    }

    public ObjectNode listConnectorDestinations(String connector, String token, Integer max, String region) {
        List<JsonNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(store.account(), region, CONNECTOR_DESTINATION)) {
            if (connector != null && !connector.equals(record.path("CloudConnectorId").asText())) {
                continue;
            }
            ObjectNode summary = store.mapper().createObjectNode().put("Id", record.path("Id").asText())
                    .put("CloudConnectorId", record.path("CloudConnectorId").asText());
            for (String field : List.of("Name", "Description")) {
                if (record.has(field)) {
                    summary.put(field, record.path(field).asText());
                }
            }
            items.add(summary);
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotMiStore.page(items, token, max, out, "ConnectorDestinationList");
        return out;
    }

    // ---------------------------------------------------------------- account associations

    public ObjectNode createAccountAssociation(JsonNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateAccountAssociation", in);
        if (repeated != null) {
            return repeated;
        }
        ObjectNode destination = store.require(account, region, CONNECTOR_DESTINATION,
                in.path("ConnectorDestinationId").asText(), "connector destination");
        String id = IotMiStore.id();
        ObjectNode record = store.record();
        record.put("AccountAssociationId", id).put("Arn", store.arn(account, region, ASSOCIATION, id))
                .put("ConnectorDestinationId", destination.path("Id").asText());
        for (String field : List.of("Name", "Description")) {
            if (in.hasNonNull(field)) {
                record.put(field, in.path(field).asText());
            }
        }
        record.set("Tags", IotMiStore.tagsOf(store.mapper(), in.path("Tags")));
        if (destination.path("AuthConfig").has("oAuth")) {
            begin(account, region, record, destination);
        } else {
            String material = in.path("GeneralAuthorization").path("AuthMaterialName").asText(null);
            JsonNode found = null;
            for (JsonNode candidate : destination.path("AuthConfig").path("GeneralAuthorization")) {
                if (material == null || material.equals(candidate.path("AuthMaterialName").asText())) {
                    found = candidate;
                    break;
                }
            }
            if (found == null) {
                throw IotMiStore.validation("The connector destination has no auth material " + material + ".");
            }
            record.set("GeneralAuthorization", store.mapper().createObjectNode().put("AuthMaterialName",
                    found.path("AuthMaterialName").asText()));
            meta(record).set("auth", store.mapper().createObjectNode().put("type", "GeneralAuthorization")
                    .set("secretsManager", found.path("SecretsManager").deepCopy()));
            record.put("AssociationState", "ASSOCIATION_IN_PROGRESS").put("OAuthAuthorizationUrl", "");
            store.put(account, region, ASSOCIATION, id, record);
            activate(account, region, id);
            record = store.get(account, region, ASSOCIATION, id).orElse(record);
        }
        ObjectNode out = store.mapper().createObjectNode()
                .put("OAuthAuthorizationUrl", record.path("OAuthAuthorizationUrl").asText(""))
                .put("AccountAssociationId", id).put("AssociationState", record.path("AssociationState").asText())
                .put("Arn", record.path("Arn").asText());
        store.remember(account, region, "CreateAccountAssociation", in, out);
        return out;
    }

    /** Starts an OAuth 2.0 authorization: the URL the end user visits, returning to Floci with a code. */
    private void begin(String account, String region, ObjectNode association, ObjectNode destination) {
        JsonNode oauth = destination.path("AuthConfig").path("oAuth");
        String state = IotMiStore.id();
        Map<String, String> client = client(account, region, destination);
        StringBuilder url = new StringBuilder(oauth.path("authUrl").asText());
        url.append(url.indexOf("?") >= 0 ? "&" : "?").append("response_type=code&client_id=")
                .append(encode(client.getOrDefault("client_id", ""))).append("&redirect_uri=")
                .append(encode(redirectUri())).append("&state=").append(state);
        if (oauth.hasNonNull("scope")) {
            url.append("&scope=").append(encode(oauth.path("scope").asText()));
        }
        association.put("AssociationState", "ASSOCIATION_IN_PROGRESS").put("OAuthAuthorizationUrl", url.toString());
        meta(association).put("oauthState", state).put("region", region);
        store.put(account, region, ASSOCIATION, association.path("AccountAssociationId").asText(), association);
        associationEvent(account, region, association, "AccountAssociation State is updated to IN_PROGRESS");
    }

    private String redirectUri() {
        return config.effectiveBaseUrl() + CALLBACK_PATH;
    }

    /**
     * The end of an OAuth 2.0 authorization: exchanges the code at the destination's token URL, activates the user
     * with the connector, and returns where the end user goes next.
     */
    public String completeAuthorization(String state, String code, String error) {
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(key -> key.contains("/" + ASSOCIATION + "/"))) {
            ObjectNode association = entry.value();
            if (!state.equals(meta(association).path("oauthState").asText())) {
                continue;
            }
            String account = entry.accountId();
            String region = meta(association).path("region").asText();
            String id = association.path("AccountAssociationId").asText();
            ObjectNode destination = store.get(account, region, CONNECTOR_DESTINATION,
                    association.path("ConnectorDestinationId").asText()).orElse(null);
            if (destination == null) {
                return null;
            }
            meta(association).remove("oauthState");
            if (error != null || code == null) {
                fail(account, region, association, "The end user didn't authorize the account: " + error);
                return destination.path("OAuthCompleteRedirectUrl").asText();
            }
            try {
                JsonNode tokens = exchange(account, region, destination, code);
                meta(association).set("auth", store.mapper().createObjectNode().put("type", "OAuth2.0")
                        .put("token", tokens.path("access_token").asText()));
                meta(association).put("refreshToken", tokens.path("refresh_token").asText(null));
                store.put(account, region, ASSOCIATION, id, association);
                activate(account, region, id);
            } catch (RuntimeException e) {
                fail(account, region, association, "The token exchange failed: " + e.getMessage());
            }
            return destination.path("OAuthCompleteRedirectUrl").asText();
        }
        return null;
    }

    private JsonNode exchange(String account, String region, ObjectNode destination, String code) {
        JsonNode oauth = destination.path("AuthConfig").path("oAuth");
        Map<String, String> client = client(account, region, destination);
        StringBuilder form = new StringBuilder("grant_type=authorization_code&code=").append(encode(code))
                .append("&redirect_uri=").append(encode(redirectUri()));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(oauth.path("tokenUrl").asText()))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json");
        if ("HTTP_BASIC".equals(oauth.path("tokenEndpointAuthenticationScheme").asText())) {
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((client.get("client_id")
                    + ":" + client.get("client_secret")).getBytes(StandardCharsets.UTF_8)));
        } else {
            form.append("&client_id=").append(encode(client.getOrDefault("client_id", ""))).append("&client_secret=")
                    .append(encode(client.getOrDefault("client_secret", "")));
        }
        try {
            HttpResponse<String> response = http.send(request.POST(HttpRequest.BodyPublishers.ofString(
                    form.toString())).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("the token URL answered " + response.statusCode());
            }
            JsonNode tokens = store.mapper().readTree(response.body());
            if (!tokens.hasNonNull("access_token")) {
                throw new IllegalStateException("the token URL returned no access_token");
            }
            return tokens;
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    /** The OAuth client the destination's secret holds: {@code client_id} and {@code client_secret}. */
    private Map<String, String> client(String account, String region, ObjectNode destination) {
        JsonNode secret = destination.path("SecretsManager");
        Map<String, String> client = new LinkedHashMap<>();
        if (secret.isMissingNode()) {
            return client;
        }
        try {
            String value = RequestScopes.callAs(account, region, () -> secrets.get().getSecretValue(
                    secret.path("arn").asText(), secret.path("versionId").asText(null), null, region)
                    .getSecretString());
            JsonNode parsed = store.mapper().readTree(value);
            Iterator<Map.Entry<String, JsonNode>> fields = parsed.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                client.put(field.getKey().replace("clientId", "client_id").replace("clientSecret", "client_secret"),
                        field.getValue().asText());
            }
        } catch (IOException | RuntimeException e) {
            LOG.debugv("The connector destination secret could not be read: {0}", e.getMessage());
        }
        return client;
    }

    /** Asks the connector to activate the end user, after which the association has SUCCEEDED. */
    private void activate(String account, String region, String id) {
        ObjectNode association = store.get(account, region, ASSOCIATION, id).orElseThrow();
        ObjectNode payload = payload(account, region, association, "AWS.ActivateUser");
        JsonNode response = invokeConnector(account, region, association, payload);
        if (response == null) {
            fail(account, region, association, "The connector didn't activate the user.");
            return;
        }
        String userId = response.path("payload").path("userId").asText(null);
        if (userId != null) {
            meta(association).put("userId", userId);
        }
        association.put("AssociationState", "ASSOCIATION_SUCCEEDED");
        association.remove("ErrorMessage");
        store.put(account, region, ASSOCIATION, id, association);
        associationEvent(account, region, association, "AccountAssociation has completed successfully");
    }

    private void fail(String account, String region, ObjectNode association, String message) {
        association.put("AssociationState", "ASSOCIATION_FAILED")
                .put("ErrorMessage", message.replaceAll("[^A-Za-z0-9-_ ]", " ").trim());
        store.put(account, region, ASSOCIATION, association.path("AccountAssociationId").asText(), association);
        associationEvent(account, region, association, message);
    }

    private void associationEvent(String account, String region, ObjectNode association, String details) {
        ObjectNode payload = store.mapper().createObjectNode().put("traceId", IotMiStore.id()).put("logLevel", "INFO")
                .put("resourceType", ASSOCIATION).put("resourceId", association.path("AccountAssociationId").asText())
                .put("connectorDestinationId", association.path("ConnectorDestinationId").asText())
                .put("associationArn", association.path("Arn").asText())
                .put("state", association.path("AssociationState").asText()).put("isServiceError", false)
                .put("isCustomerError", "ASSOCIATION_FAILED".equals(association.path("AssociationState").asText()))
                .put("details", details);
        notifications.publish(account, region, "ACCOUNT_ASSOCIATION", List.of(association.path("Arn").asText()),
                payload);
    }

    public ObjectNode getAccountAssociation(String id, String region) {
        ObjectNode record = store.require(store.account(), region, ASSOCIATION, id, "account association");
        ObjectNode out = view(record);
        if (!out.has("OAuthAuthorizationUrl")) {
            out.put("OAuthAuthorizationUrl", "");
        }
        return out;
    }

    public ObjectNode updateAccountAssociation(String id, JsonNode in, String region) {
        String account = store.account();
        ObjectNode record = store.require(account, region, ASSOCIATION, id, "account association");
        for (String field : List.of("Name", "Description")) {
            if (in.hasNonNull(field)) {
                record.put(field, in.path(field).asText());
            }
        }
        store.put(account, region, ASSOCIATION, id, record);
        return store.mapper().createObjectNode();
    }

    public ObjectNode startAccountAssociationRefresh(String id, String region) {
        String account = store.account();
        ObjectNode record = store.require(account, region, ASSOCIATION, id, "account association");
        ObjectNode destination = store.require(account, region, CONNECTOR_DESTINATION,
                record.path("ConnectorDestinationId").asText(), "connector destination");
        if (!destination.path("AuthConfig").has("oAuth")) {
            throw IotMiStore.conflict("Only an OAuth account association is refreshed.");
        }
        begin(account, region, record, destination);
        return store.mapper().createObjectNode().put("OAuthAuthorizationUrl", record.path("OAuthAuthorizationUrl")
                .asText());
    }

    public ObjectNode deleteAccountAssociation(String id, String region) {
        String account = store.account();
        ObjectNode record = store.require(account, region, ASSOCIATION, id, "account association");
        record.put("AssociationState", "ASSOCIATION_DELETING");
        store.put(account, region, ASSOCIATION, id, record);
        if (meta(record).has("auth")) {
            invoker.submit(() -> RequestScopes.runAs(account, region, () -> invokeConnector(account, region, record,
                    payload(account, region, record, "AWS.DeactivateUser"))));
        }
        for (ObjectNode thing : store.list(account, region, THING)) {
            ArrayNode associations = (ArrayNode) meta(thing).withArray("associations");
            for (int i = associations.size() - 1; i >= 0; i--) {
                if (id.equals(associations.get(i).path("AccountAssociationId").asText())) {
                    associations.remove(i);
                }
            }
            store.put(account, region, THING, thing.path("Id").asText(), thing);
        }
        store.delete(account, region, ASSOCIATION, id);
        return store.mapper().createObjectNode();
    }

    public ObjectNode listAccountAssociations(String destination, String token, Integer max, String region) {
        List<JsonNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(store.account(), region, ASSOCIATION)) {
            if (destination != null && !destination.equals(record.path("ConnectorDestinationId").asText())) {
                continue;
            }
            ObjectNode summary = view(record);
            summary.remove("Tags");
            summary.remove("OAuthAuthorizationUrl");
            summary.remove("GeneralAuthorization");
            items.add(summary);
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotMiStore.page(items, token, max, out, "Items");
        return out;
    }

    /** Links a managed thing found by an association's discovery to the association. */
    public ObjectNode registerAccountAssociation(JsonNode in, String region) {
        String account = store.account();
        String thingId = in.path("ManagedThingId").asText();
        String associationId = in.path("AccountAssociationId").asText();
        ObjectNode thing = things.thing(account, region, thingId);
        store.require(account, region, ASSOCIATION, associationId, "account association");
        ObjectNode discovery = store.require(account, region, DISCOVERY, in.path("DeviceDiscoveryId").asText(),
                "device discovery");
        if (!associationId.equals(discovery.path("AccountAssociationId").asText())) {
            throw IotMiStore.conflict("The device discovery wasn't run for account association " + associationId
                    + ".");
        }
        associate(account, region, thing, associationId, "ASSOCIATED");
        return store.mapper().createObjectNode().put("AccountAssociationId", associationId)
                .put("DeviceDiscoveryId", discovery.path("Id").asText()).put("ManagedThingId", thingId);
    }

    public ObjectNode deregisterAccountAssociation(JsonNode in, String region) {
        String account = store.account();
        ObjectNode thing = things.thing(account, region, in.path("ManagedThingId").asText());
        String associationId = in.path("AccountAssociationId").asText();
        ArrayNode associations = (ArrayNode) meta(thing).withArray("associations");
        boolean removed = false;
        for (int i = associations.size() - 1; i >= 0; i--) {
            if (associationId.equals(associations.get(i).path("AccountAssociationId").asText())) {
                associations.remove(i);
                removed = true;
            }
        }
        if (!removed) {
            throw IotMiStore.notFound("managed thing association", associationId);
        }
        store.put(account, region, THING, thing.path("Id").asText(), thing);
        return store.mapper().createObjectNode();
    }

    public ObjectNode listManagedThingAccountAssociations(String thingId, String associationId, String token,
                                                          Integer max, String region) {
        List<JsonNode> items = new ArrayList<>();
        for (ObjectNode thing : store.list(store.account(), region, THING)) {
            if (thingId != null && !thingId.equals(thing.path("Id").asText())) {
                continue;
            }
            for (JsonNode association : thing.path("_").path("associations")) {
                if (associationId == null || associationId.equals(association.path("AccountAssociationId").asText())) {
                    items.add(association);
                }
            }
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotMiStore.page(items, token, max, out, "Items");
        return out;
    }

    private void associate(String account, String region, ObjectNode thing, String associationId, String status) {
        ArrayNode associations = (ArrayNode) meta(thing).withArray("associations");
        for (int i = associations.size() - 1; i >= 0; i--) {
            if (associationId.equals(associations.get(i).path("AccountAssociationId").asText())) {
                associations.remove(i);
            }
        }
        associations.addObject().put("ManagedThingId", thing.path("Id").asText())
                .put("AccountAssociationId", associationId).put("ManagedThingAssociationStatus", status);
        store.put(account, region, THING, thing.path("Id").asText(), thing);
    }

    // ---------------------------------------------------------------- device discovery

    public ObjectNode startDeviceDiscovery(JsonNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "StartDeviceDiscovery", in);
        if (repeated != null) {
            return repeated;
        }
        String type = in.path("DiscoveryType").asText();
        ObjectNode association = null;
        ObjectNode controller = null;
        if ("CLOUD".equals(type)) {
            String associationId = in.path("AccountAssociationId").asText(in.path("ConnectorAssociationIdentifier")
                    .asText(null));
            if (associationId == null) {
                throw IotMiStore.validation("A CLOUD discovery needs an AccountAssociationId.");
            }
            association = store.require(account, region, ASSOCIATION, associationId, "account association");
            if (!"ASSOCIATION_SUCCEEDED".equals(association.path("AssociationState").asText())) {
                throw IotMiStore.conflict("The account association " + associationId + " is "
                        + association.path("AssociationState").asText() + ".");
            }
        } else {
            String controllerId = in.path("ControllerIdentifier").asText(null);
            if (controllerId == null) {
                throw IotMiStore.validation("A " + type + " discovery needs a ControllerIdentifier.");
            }
            controller = things.thing(account, region, controllerId);
            if (!"CONTROLLER".equals(controller.path("Role").asText())) {
                throw IotMiStore.validation("The managed thing " + controllerId + " isn't a CONTROLLER.");
            }
        }
        String id = IotMiStore.id();
        ObjectNode record = store.record();
        record.put("Id", id).put("Arn", store.arn(account, region, DISCOVERY, id)).put("DiscoveryType", type)
                .put("Status", "RUNNING").put("StartedAt", IotMiStore.now());
        if (association != null) {
            record.put("AccountAssociationId", association.path("AccountAssociationId").asText());
        }
        if (controller != null) {
            record.put("ControllerId", controller.path("Id").asText());
        }
        record.set("Tags", IotMiStore.tagsOf(store.mapper(), in.path("Tags")));
        meta(record).putArray("devices");
        meta(record).put("expiresAt", Instant.now().plusSeconds(store.config().discoveryTimeoutSeconds()).toString());
        store.put(account, region, DISCOVERY, id, record);
        if (association != null) {
            ObjectNode payload = payload(account, region, association, "AWS.DiscoverDevices");
            ((ObjectNode) payload.get("payload")).put("deviceDiscoveryId", id);
            ArrayNode filter = ((ObjectNode) payload.get("payload")).putArray("connectorDeviceIdList");
            in.path("ConnectorDeviceIdList").forEach(filter::add);
            ObjectNode target = association;
            invoker.submit(() -> RequestScopes.runAs(account, region, () -> {
                if (invokeConnector(account, region, target, payload) == null) {
                    finishDiscovery(account, region, id, "FAILED", 0);
                }
            }));
        } else {
            // The hub's device SDK would scan its Zigbee, Z-Wave or custom radio; it doesn't run in Floci.
            finishDiscovery(account, region, id, "FAILED", 0);
        }
        ObjectNode out = store.mapper().createObjectNode().put("Id", id).put("StartedAt",
                record.path("StartedAt").asDouble());
        store.remember(account, region, "StartDeviceDiscovery", in, out);
        return out;
    }

    public ObjectNode getDeviceDiscovery(String id, String region) {
        return view(discovery(store.account(), region, id));
    }

    public ObjectNode listDeviceDiscoveries(String type, String status, String token, Integer max, String region) {
        List<JsonNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(store.account(), region, DISCOVERY)) {
            ObjectNode discovery = discovery(store.account(), region, record.path("Id").asText());
            if ((type != null && !type.equals(discovery.path("DiscoveryType").asText()))
                    || (status != null && !status.equals(discovery.path("Status").asText()))) {
                continue;
            }
            items.add(store.mapper().createObjectNode().put("Id", discovery.path("Id").asText())
                    .put("DiscoveryType", discovery.path("DiscoveryType").asText())
                    .put("Status", discovery.path("Status").asText()));
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotMiStore.page(items, token, max, out, "Items");
        return out;
    }

    public ObjectNode listDiscoveredDevices(String id, String token, Integer max, String region) {
        ObjectNode record = discovery(store.account(), region, id);
        List<JsonNode> items = new ArrayList<>();
        record.path("_").path("devices").forEach(items::add);
        ObjectNode out = store.mapper().createObjectNode();
        IotMiStore.page(items, token, max, out, "Items");
        return out;
    }

    /** A discovery, TIMED_OUT once it has run past its time without the connector's answer. */
    private ObjectNode discovery(String account, String region, String id) {
        ObjectNode record = store.require(account, region, DISCOVERY, id, "device discovery");
        if ("RUNNING".equals(record.path("Status").asText())
                && Instant.now().isAfter(Instant.parse(meta(record).path("expiresAt").asText()))) {
            finishDiscovery(account, region, id, "TIMED_OUT", 0);
            record = store.require(account, region, DISCOVERY, id, "device discovery");
        }
        return record;
    }

    private void finishDiscovery(String account, String region, String id, String status, int devices) {
        ObjectNode record = store.get(account, region, DISCOVERY, id).orElse(null);
        if (record == null || !"RUNNING".equals(record.path("Status").asText())) {
            return;
        }
        record.put("Status", status).put("FinishedAt", IotMiStore.now());
        store.put(account, region, DISCOVERY, id, record);
        ObjectNode payload = store.mapper().createObjectNode().put("deviceCount", devices)
                .put("deviceDiscoveryId", id).put("status", status);
        notifications.publish(account, region, "DEVICE_DISCOVERY_STATUS", List.of(record.path("Arn").asText()),
                payload);
    }

    // ---------------------------------------------------------------- commands

    /**
     * Sends a command to a managed thing. A device of a cloud connector is reached through the connector's
     * {@code AWS.SendCommand}, with each capability action as its Matter cluster command; the device's answer
     * arrives as a {@code DEVICE_COMMAND_RESPONSE} connector event.
     */
    public ObjectNode sendManagedThingCommand(String thingId, JsonNode in, String region) {
        String account = store.account();
        ObjectNode thing = things.thing(account, region, thingId);
        ArrayNode endpoints = matterCommand(in.path("Endpoints"));
        if (!"ACTIVATED".equals(thing.path("ProvisioningStatus").asText())) {
            throw IotMiStore.conflict("The managed thing " + thingId + " is "
                    + thing.path("ProvisioningStatus").asText() + "; only an ACTIVATED one takes commands.");
        }
        String connectorDevice = thing.path("ConnectorDeviceId").asText(null);
        String associationId = in.path("AccountAssociationId").asText(in.path("ConnectorAssociationId").asText(
                meta(thing).path("associations").path(0).path("AccountAssociationId").asText(null)));
        if (connectorDevice == null || associationId == null) {
            throw IotMiStore.conflict("The managed thing " + thingId + " is reached through its hub, which isn't "
                    + "connected.");
        }
        ObjectNode association = store.require(account, region, ASSOCIATION, associationId, "account association");
        String traceId = IotMiStore.id();
        ObjectNode payload = payload(account, region, association, "AWS.SendCommand");
        ((ObjectNode) payload.get("payload")).put("connectorDeviceId", connectorDevice).put("traceId", traceId)
                .set("endpoints", endpoints);
        ObjectNode commands = (ObjectNode) meta(thing).with("commands");
        commands.putObject(traceId).put("receivedAt", Instant.now().toString()).set("request", in.path("Endpoints"));
        store.put(account, region, THING, thingId, thing);
        invoker.submit(() -> RequestScopes.runAs(account, region, () -> {
            if (invokeConnector(account, region, association, payload) == null) {
                commandResult(account, region, thingId, traceId, "failed");
            }
        }));
        return store.mapper().createObjectNode().put("TraceId", traceId);
    }

    /** Capability actions as the connector's Matter endpoints, clusters and commands. */
    private ArrayNode matterCommand(JsonNode endpoints) {
        ArrayNode matter = store.mapper().createArrayNode();
        for (JsonNode endpoint : endpoints) {
            ObjectNode target = matter.addObject().put("id", endpoint.path("endpointId").asText());
            ArrayNode clusters = target.putArray("clusters");
            for (JsonNode capability : endpoint.path("capabilities")) {
                ObjectNode schema = schemas.capability(capability.path("id").asText()).orElseThrow(() ->
                        IotMiStore.validation("The capability " + capability.path("id").asText()
                                + " isn't in the schema catalog."));
                ObjectNode cluster = clusters.addObject().put("id", schema.path("extrinsicId").asText());
                ArrayNode commands = cluster.putArray("commands");
                for (JsonNode action : capability.path("actions")) {
                    String name = action.path("name").asText();
                    ObjectNode parameters = store.mapper().createObjectNode();
                    String commandId;
                    if ("UpdateState".equals(name) || "ReadState".equals(name)) {
                        commandId = "UpdateState".equals(name) ? UPDATE_STATE : READ_STATE;
                        action.path("parameters").fields().forEachRemaining(field -> {
                            if ("propertiesToRead".equals(field.getKey())) {
                                ArrayNode read = parameters.putArray("attributes");
                                field.getValue().forEach(property -> read.add("*".equals(property.asText()) ? "*"
                                        : schema.path("properties").path(property.asText()).path("extrinsicId")
                                        .asText(property.asText())));
                            } else {
                                parameters.set(schema.path("properties").path(field.getKey()).path("extrinsicId")
                                        .asText(field.getKey()), field.getValue());
                            }
                        });
                    } else {
                        JsonNode definition = null;
                        for (JsonNode candidate : schema.path("actions")) {
                            if (name.equals(candidate.path("name").asText())) {
                                definition = candidate;
                            }
                        }
                        if (definition == null) {
                            throw IotMiStore.validation("The capability " + capability.path("id").asText()
                                    + " has no action " + name + ".");
                        }
                        commandId = definition.path("extrinsicId").asText();
                        JsonNode declared = definition.path("request").path("parameters");
                        action.path("parameters").fields().forEachRemaining(field -> parameters.set(
                                declared.path(field.getKey()).path("extrinsicId").asText(field.getKey()),
                                field.getValue()));
                    }
                    commands.addObject().set(commandId, parameters);
                }
            }
        }
        return matter;
    }

    private void commandResult(String account, String region, String thingId, String traceId, String result) {
        ObjectNode thing = store.get(account, region, THING, thingId).orElse(null);
        if (thing == null) {
            return;
        }
        JsonNode command = meta(thing).path("commands").path(traceId);
        ObjectNode payload = store.mapper().createObjectNode().put("traceId", traceId)
                .put("receivedAt", command.path("receivedAt").asText(Instant.now().toString()))
                .put("executedAt", Instant.now().toString()).put("result", result);
        if (command instanceof ObjectNode stored) {
            stored.put("result", result);
            store.put(account, region, THING, thingId, thing);
        }
        notifications.thingEvent(account, region, "DEVICE_COMMAND", thing, payload);
    }

    // ---------------------------------------------------------------- connector events

    /** What a cloud connector reports: discovered devices, command responses, device events and WebRTC requests. */
    public ObjectNode sendConnectorEvent(String connectorId, JsonNode in, String region) {
        String account = store.account();
        store.require(account, region, CONNECTOR, connectorId, "cloud connector");
        switch (in.path("Operation").asText()) {
            case "DEVICE_DISCOVERY" -> discovered(account, region, connectorId, in);
            case "DEVICE_COMMAND_RESPONSE" -> commandResponse(account, region, connectorId, in);
            case "DEVICE_EVENT" -> deviceEvent(account, region, connectorId, in);
            case "DEVICE_COMMAND_REQUEST" -> {
                ObjectNode thing = connectorThing(account, region, connectorId, in.path("ConnectorDeviceId").asText());
                ObjectNode payload = store.mapper().createObjectNode();
                payload.set("endpoints", capabilities(in.path("MatterEndpoint")));
                notifications.thingEvent(account, region, "DEVICE_COMMAND_REQUEST", thing, payload);
            }
            default -> throw IotMiStore.validation("Unknown operation " + in.path("Operation").asText() + ".");
        }
        return store.mapper().createObjectNode().put("ConnectorId", connectorId);
    }

    private void discovered(String account, String region, String connectorId, JsonNode in) {
        String discoveryId = in.path("DeviceDiscoveryId").asText();
        ObjectNode discovery = store.require(account, region, DISCOVERY, discoveryId, "device discovery");
        ObjectNode association = store.require(account, region, ASSOCIATION,
                discovery.path("AccountAssociationId").asText(), "account association");
        ObjectNode destination = store.require(account, region, CONNECTOR_DESTINATION,
                association.path("ConnectorDestinationId").asText(), "connector destination");
        if (!connectorId.equals(destination.path("CloudConnectorId").asText())) {
            throw IotMiStore.validation("The device discovery " + discoveryId + " isn't of connector " + connectorId
                    + ".");
        }
        int status = in.path("StatusCode").asInt(200);
        if (status / 100 != 2) {
            finishDiscovery(account, region, discoveryId, "FAILED", 0);
            connectorError(account, region, association, "AWS.DiscoverDevices", status,
                    in.path("Message").asText("The connector's discovery failed."), null, null);
            return;
        }
        ArrayNode found = (ArrayNode) meta(discovery).withArray("devices");
        for (JsonNode device : in.path("Devices")) {
            String connectorDevice = device.path("ConnectorDeviceId").asText();
            ObjectNode report = report(device.path("CapabilityReport"));
            ObjectNode existing = null;
            for (ObjectNode thing : store.list(account, region, THING)) {
                if (connectorDevice.equals(thing.path("ConnectorDeviceId").asText())
                        && destination.path("Id").asText().equals(thing.path("ConnectorDestinationId").asText())
                        && !IotMiService.deleted(thing)) {
                    existing = thing;
                }
            }
            String modification;
            ObjectNode thing;
            if (existing == null) {
                ObjectNode request = store.mapper().createObjectNode().put("Role", "DEVICE")
                        .put("AuthenticationMaterialType", "DISCOVERED_DEVICE")
                        .put("AuthenticationMaterial", destination.path("Id").asText() + "/" + connectorDevice)
                        .put("Name", device.path("ConnectorDeviceName").asText(connectorDevice));
                request.set("CapabilityReport", report);
                ObjectNode connector = store.mapper().createObjectNode().put("ConnectorDeviceId", connectorDevice)
                        .put("ConnectorDestinationId", destination.path("Id").asText());
                thing = things.newThing(account, region, request, connector);
                modification = "DISCOVERED";
            } else {
                boolean changed = !report.equals(meta(existing).path("CapabilityReport"));
                meta(existing).set("CapabilityReport", report);
                if (device.hasNonNull("ConnectorDeviceName")) {
                    existing.put("Name", device.path("ConnectorDeviceName").asText());
                }
                existing.put("UpdatedAt", IotMiStore.now());
                store.put(account, region, THING, existing.path("Id").asText(), existing);
                thing = existing;
                modification = changed ? "UPDATED" : "NO_CHANGE";
            }
            meta(thing).set("connectivity", store.mapper().createObjectNode().put("connected", true)
                    .put("timestamp", IotMiStore.now()).put("disconnectReason", "NONE"));
            if (device.has("DeviceMetadata")) {
                meta(thing).set("deviceMetadata", device.get("DeviceMetadata"));
            }
            store.put(account, region, THING, thing.path("Id").asText(), thing);
            associate(account, region, store.get(account, region, THING, thing.path("Id").asText()).orElse(thing),
                    association.path("AccountAssociationId").asText(), "ASSOCIATED");
            ObjectNode summary = found.addObject().put("ConnectorDeviceId", connectorDevice)
                    .put("ManagedThingId", thing.path("Id").asText()).put("Modification", modification)
                    .put("DiscoveredAt", IotMiStore.now());
            if (device.hasNonNull("ConnectorDeviceName")) {
                summary.put("ConnectorDeviceName", device.path("ConnectorDeviceName").asText());
            }
            ArrayNode types = summary.putArray("DeviceTypes");
            device.path("CapabilityReport").path("endpoints").forEach(endpoint -> endpoint.path("deviceTypes")
                    .forEach(types::add));
        }
        store.put(account, region, DISCOVERY, discoveryId, discovery);
        finishDiscovery(account, region, discoveryId, "SUCCEEDED", in.path("Devices").size());
    }

    private void commandResponse(String account, String region, String connectorId, JsonNode in) {
        ObjectNode thing = connectorThing(account, region, connectorId, in.path("ConnectorDeviceId").asText());
        String traceId = in.path("TraceId").asText();
        if (!meta(thing).path("commands").has(traceId)) {
            throw IotMiStore.notFound("command", traceId);
        }
        int status = in.path("StatusCode").asInt(200);
        if (status / 100 == 2 && in.has("MatterEndpoint")) {
            things.reportState(account, region, thing, capabilities(in.path("MatterEndpoint")));
            thing = store.get(account, region, THING, thing.path("Id").asText()).orElse(thing);
        }
        if (status / 100 != 2) {
            String associationId = meta(thing).path("associations").path(0).path("AccountAssociationId").asText(null);
            ObjectNode association = associationId == null ? null
                    : store.get(account, region, ASSOCIATION, associationId).orElse(null);
            if (association != null) {
                connectorError(account, region, association, "AWS.SendCommand", status,
                        in.path("Message").asText("The command failed."), thing, traceId);
            }
        }
        commandResult(account, region, thing.path("Id").asText(), traceId, status / 100 == 2 ? "succeeded" : "failed");
    }

    private void deviceEvent(String account, String region, String connectorId, JsonNode in) {
        ObjectNode thing = connectorThing(account, region, connectorId, in.path("ConnectorDeviceId").asText());
        ArrayNode endpoints = capabilities(in.path("MatterEndpoint"));
        things.reportState(account, region, thing, endpoints);
        ObjectNode payload = store.mapper().createObjectNode().put("iotMiArrivalTimestamp", Instant.now().toString());
        payload.set("endpoints", endpoints);
        notifications.thingEvent(account, region, "DEVICE_EVENT", thing, payload);
    }

    /** The managed thing of a connector's device. */
    private ObjectNode connectorThing(String account, String region, String connectorId, String connectorDevice) {
        for (ObjectNode thing : store.list(account, region, THING)) {
            if (!connectorDevice.equals(thing.path("ConnectorDeviceId").asText()) || IotMiService.deleted(thing)) {
                continue;
            }
            ObjectNode destination = store.get(account, region, CONNECTOR_DESTINATION,
                    thing.path("ConnectorDestinationId").asText()).orElse(null);
            if (destination != null && connectorId.equals(destination.path("CloudConnectorId").asText())) {
                return thing;
            }
        }
        throw IotMiStore.notFound("connector device", connectorDevice);
    }

    /** A Matter capability report from a connector as the managed integrations capability report. */
    private ObjectNode report(JsonNode matter) {
        ObjectNode report = store.mapper().createObjectNode().put("version", matter.path("version").asText("1.0.0"));
        if (matter.hasNonNull("nodeId")) {
            report.put("nodeId", matter.path("nodeId").asText());
        }
        ArrayNode endpoints = report.putArray("endpoints");
        for (JsonNode endpoint : matter.path("endpoints")) {
            ObjectNode target = endpoints.addObject().put("id", endpoint.path("id").asText());
            target.set("deviceTypes", endpoint.path("deviceTypes").deepCopy());
            ArrayNode capabilities = target.putArray("capabilities");
            for (JsonNode cluster : endpoint.path("clusters")) {
                ObjectNode schema = schemas.capabilityForCluster(cluster.path("id").asText()).orElse(null);
                if (schema == null) {
                    continue;
                }
                String id = schema.path("$id").asText().substring(schema.path("$id").asText().lastIndexOf('/') + 1);
                ObjectNode capability = capabilities.addObject().put("id", id).put("name",
                        schema.path("name").asText()).put("version", cluster.path("revision").asText(
                        schema.path("extrinsicVersion").asText()));
                ArrayNode properties = capability.putArray("properties");
                Map<String, String> byId = idsToNames(schema.path("properties"));
                for (JsonNode attribute : cluster.path("attributes")) {
                    properties.add(byId.getOrDefault(normalize(attribute.path("id").asText()),
                            attribute.path("name").asText(attribute.path("id").asText())));
                }
                ArrayNode actions = capability.putArray("actions");
                for (JsonNode command : cluster.path("commands")) {
                    for (JsonNode action : schema.path("actions")) {
                        if (normalize(action.path("extrinsicId").asText()).equals(normalize(command.asText()))) {
                            actions.add(action.path("name").asText());
                        }
                    }
                }
                ArrayNode events = capability.putArray("events");
                for (JsonNode event : cluster.path("events")) {
                    for (JsonNode definition : schema.path("events")) {
                        if (normalize(definition.path("extrinsicId").asText()).equals(normalize(event.asText()))) {
                            events.add(definition.path("name").asText());
                        }
                    }
                }
            }
        }
        return report;
    }

    /** A connector's Matter endpoint, attributes by ID, as capability properties by name. */
    private ArrayNode capabilities(JsonNode matter) {
        ArrayNode endpoints = store.mapper().createArrayNode();
        ObjectNode endpoint = endpoints.addObject().put("endpointId", matter.path("id").asText("1"));
        ArrayNode capabilities = endpoint.putArray("capabilities");
        for (JsonNode cluster : matter.path("clusters")) {
            ObjectNode schema = schemas.capabilityForCluster(cluster.path("id").asText()).orElse(null);
            if (schema == null) {
                continue;
            }
            String id = schema.path("$id").asText().substring(schema.path("$id").asText().lastIndexOf('/') + 1);
            ObjectNode capability = capabilities.addObject().put("id", id).put("name", schema.path("name").asText())
                    .put("version", schema.path("version").asText());
            ObjectNode properties = capability.putObject("properties");
            Map<String, String> byId = idsToNames(schema.path("properties"));
            cluster.path("attributes").fields().forEachRemaining(attribute -> properties.set(
                    byId.getOrDefault(normalize(attribute.getKey()), attribute.getKey()), attribute.getValue()));
        }
        return endpoints;
    }

    private static Map<String, String> idsToNames(JsonNode properties) {
        Map<String, String> names = new LinkedHashMap<>();
        properties.fields().forEachRemaining(property -> names.put(normalize(property.getValue().path("extrinsicId")
                .asText()), property.getKey()));
        return names;
    }

    private static String normalize(String id) {
        try {
            return String.valueOf(IotMiSchemas.number(id));
        } catch (NumberFormatException e) {
            return id;
        }
    }

    private void connectorError(String account, String region, ObjectNode association, String operation, int status,
                                String message, ObjectNode thing, String traceId) {
        ObjectNode payload = store.mapper().createObjectNode().put("statusCode", status)
                .put("resourceType", ASSOCIATION).put("connectorDestinationId",
                        association.path("ConnectorDestinationId").asText()).put("operation", operation)
                .put("operationVersion", "1.0").put("logLevel", "ERROR").put("message", message);
        payload.putArray("resourceId").add(association.path("AccountAssociationId").asText());
        payload.putArray("associationArn").add(association.path("Arn").asText());
        if (thing != null) {
            payload.put("managedThingId", thing.path("Id").asText()).put("connectorDeviceId",
                    thing.path("ConnectorDeviceId").asText());
        }
        if (traceId != null) {
            payload.put("traceId", traceId);
        }
        notifications.publish(account, region, "CONNECTOR_ERROR_REPORT", List.of(association.path("Arn").asText()),
                payload);
    }

    // ---------------------------------------------------------------- invoking the connector

    /** The request a connector operation gets: the association's {@code header.auth} and the operation's payload. */
    private ObjectNode payload(String account, String region, ObjectNode association, String operation) {
        ObjectNode destination = store.require(account, region, CONNECTOR_DESTINATION,
                association.path("ConnectorDestinationId").asText(), "connector destination");
        ObjectNode request = store.mapper().createObjectNode();
        ObjectNode header = request.putObject("header");
        header.set("auth", association.path("_").has("auth") ? association.path("_").path("auth").deepCopy()
                : store.mapper().createObjectNode());
        ObjectNode payload = request.putObject("payload");
        payload.put("operationName", operation).put("operationVersion", "1.0")
                .put("connectorId", destination.path("CloudConnectorId").asText());
        String userId = association.path("_").path("userId").asText(null);
        if (userId != null) {
            payload.put("userId", userId);
        }
        return request;
    }

    /**
     * Invokes the connector's Lambda function, retrying failures as managed integrations does (five retries); a
     * response whose {@code header.responseCode} isn't 2xx is a failure. Returns the response, or null.
     */
    private JsonNode invokeConnector(String account, String region, ObjectNode association, ObjectNode request) {
        ObjectNode destination = store.get(account, region, CONNECTOR_DESTINATION,
                association.path("ConnectorDestinationId").asText()).orElse(null);
        ObjectNode connector = destination == null ? null : store.get(account, region, CONNECTOR,
                destination.path("CloudConnectorId").asText()).orElse(null);
        if (connector == null) {
            return null;
        }
        String function = connector.path("EndpointConfig").path("lambda").path("arn").asText();
        String functionRegion = AwsArnUtils.isArn(function) ? AwsArnUtils.parse(function).region() : region;
        byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
        String operation = request.path("payload").path("operationName").asText();
        for (int attempt = 1; attempt <= CONNECTOR_ATTEMPTS; attempt++) {
            try {
                InvokeResult result = RequestScopes.callAs(account, functionRegion, () -> lambda.get().invoke(
                        functionRegion, function, body, InvocationType.RequestResponse));
                if (result.getFunctionError() == null) {
                    JsonNode response = store.mapper().readTree(result.getPayload());
                    int code = response.path("header").path("responseCode").asInt(200);
                    if (code / 100 == 2) {
                        return response;
                    }
                    connectorError(account, region, association, operation, code,
                            response.path("payload").path("responseMessage").asText("The connector failed."), null,
                            request.path("payload").path("traceId").asText(null));
                    return null;
                }
                LOG.debugv("Connector {0} failed {1} (attempt {2}): {3}", function, operation, attempt,
                        new String(result.getPayload(), StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                LOG.debugv("Connector {0} could not be invoked for {1} (attempt {2}): {3}", function, operation,
                        attempt, e.getMessage());
            }
        }
        connectorError(account, region, association, operation, 500, "The connector couldn't be invoked.", null,
                request.path("payload").path("traceId").asText(null));
        return null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
