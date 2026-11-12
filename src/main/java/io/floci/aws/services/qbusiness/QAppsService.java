package io.floci.aws.services.qbusiness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.qbusiness.QBusinessSearch.Hit;
import io.floci.aws.services.qbusiness.QBusinessSearch.Passage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Amazon Q Apps on a Q Business application (its instance): apps of cards whose prompts reference other cards,
 * versioned on every update; sessions that fill the input cards and compute the query cards in dependency order,
 * answering from the instance's index or from Floci's Bedrock runtime; files, form submissions and their CSV export;
 * the library with its categories, reviews and verification; permissions; and PredictQApp.
 */
@ApplicationScoped
public class QAppsService {

    private static final Map<String, String> CARD_TYPES = Map.of("textInput", "text-input", "qQuery", "q-query",
            "qPlugin", "q-plugin", "fileUpload", "file-upload", "formInput", "form-input");

    private final QBusinessStore store;
    private final QBusinessService service;
    private final QBusinessChat chat;
    private final ObjectMapper mapper;

    @Inject
    public QAppsService(QBusinessStore store, QBusinessService service, QBusinessChat chat) {
        this.store = store;
        this.service = service;
        this.chat = chat;
        this.mapper = store.mapper();
    }

    private ObjectNode out() {
        return mapper.createObjectNode();
    }

    private String user() {
        return store.account();
    }

    private static String now() {
        return QBusinessStore.iso(QBusinessStore.now());
    }

    /** The Q Business application an instance id names, with Q Apps enabled. */
    private ObjectNode instance(String region, String id) {
        ObjectNode app = store.find(region, QBusinessStore.APPLICATION, id)
                .orElseThrow(() -> QBusinessStore.denied("The caller isn't a user of the Q Apps instance " + id + "."));
        if (!"ENABLED".equals(app.at("/qAppsConfiguration/qAppsControlMode").asText())) {
            throw QBusinessStore.denied("Q Apps is disabled for the instance " + id + ".");
        }
        return app;
    }

    /** Fails unless the request's instance exists with Q Apps enabled, as the service checks before anything else. */
    void checkInstance(JsonNode in, String region) {
        String instanceId = QBusinessService.opt(in, "instanceId");
        if (instanceId != null) {
            instance(region, instanceId);
        }
    }

    public ObjectNode handle(String operation, JsonNode in, String region) {
        String instanceId = QBusinessService.opt(in, "instanceId");
        checkInstance(in, region);
        return switch (operation) {
            case "CreateQApp" -> createQApp(in, region, instanceId);
            case "GetQApp" -> getQApp(in, region, instanceId);
            case "UpdateQApp" -> updateQApp(in, region, instanceId);
            case "DeleteQApp" -> deleteQApp(in, region, instanceId);
            case "ListQApps" -> listQApps(in, region, instanceId);
            case "DescribeQAppPermissions" -> permissions(region, app(region, instanceId, QBusinessService.req(in,
                    "appId")));
            case "UpdateQAppPermissions" -> updatePermissions(in, region, instanceId);
            case "AssociateQAppWithUser" -> associate(in, region, instanceId, true);
            case "DisassociateQAppFromUser" -> associate(in, region, instanceId, false);
            case "StartQAppSession" -> startSession(in, region, instanceId);
            case "GetQAppSession" -> getSession(in, region, instanceId);
            case "UpdateQAppSession" -> updateSession(in, region, instanceId);
            case "StopQAppSession" -> stopSession(in, region, instanceId);
            case "GetQAppSessionMetadata" -> sessionMetadata(session(region, instanceId, QBusinessService.req(in,
                    "sessionId")));
            case "UpdateQAppSessionMetadata" -> updateSessionMetadata(in, region, instanceId);
            case "ListQAppSessionData" -> listSessionData(in, region, instanceId);
            case "ExportQAppSessionData" -> exportSessionData(in, region, instanceId);
            case "ImportDocument" -> importDocument(in, region, instanceId);
            case "CreatePresignedUrl" -> createPresignedUrl(in, region, instanceId);
            case "BatchCreateCategory" -> createCategories(in, region, instanceId);
            case "BatchUpdateCategory" -> updateCategories(in, region, instanceId);
            case "BatchDeleteCategory" -> deleteCategories(in, region, instanceId);
            case "ListCategories" -> listCategories(region, instanceId);
            case "CreateLibraryItem" -> createLibraryItem(in, region, instanceId);
            case "GetLibraryItem" -> libraryView(region, instanceId, libraryItem(region, instanceId,
                    QBusinessService.req(in, "libraryItemId")));
            case "UpdateLibraryItem" -> updateLibraryItem(in, region, instanceId);
            case "UpdateLibraryItemMetadata" -> updateLibraryItemMetadata(in, region, instanceId);
            case "DeleteLibraryItem" -> deleteLibraryItem(in, region, instanceId);
            case "ListLibraryItems" -> listLibraryItems(in, region, instanceId);
            case "AssociateLibraryItemReview" -> review(in, region, instanceId, true);
            case "DisassociateLibraryItemReview" -> review(in, region, instanceId, false);
            case "PredictQApp" -> predict(in);
            case "TagResource" -> service.tagResource(in, region);
            case "UntagResource" -> service.untagResource(in, region);
            case "ListTagsForResource" -> {
                ObjectNode result = out();
                result.set("tags", QBusinessStore.tags(service.byArn(QBusinessService.req(in, "resourceARN")))
                        .deepCopy());
                yield result;
            }
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation + ".",
                    404);
        };
    }

    // ================================================================ app definitions

    /** Checks cards and returns them with their dependencies, as {@code AppDefinition} cards. */
    private ArrayNode cards(JsonNode definition, String region, String instanceId) {
        JsonNode input = definition.path("cards");
        if (!input.isArray() || input.isEmpty()) {
            throw QBusinessStore.validation("An app has at least one card.");
        }
        if (input.size() > 20) {
            throw QBusinessStore.validation("An app has at most 20 cards.");
        }
        if (definition.toString().length() > 100_000) {
            throw new AwsException("ContentTooLargeException", "The app definition is larger than 100 KB.", 413);
        }
        Map<String, String> titles = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        List<ObjectNode> cards = new ArrayList<>();
        for (JsonNode wrapper : input) {
            if (wrapper.size() != 1) {
                throw QBusinessStore.validation("A card has exactly one of textInput, qQuery, qPlugin, fileUpload "
                        + "and formInput.");
            }
            String kind = wrapper.fieldNames().next();
            ObjectNode card = (ObjectNode) wrapper.get(kind).deepCopy();
            String type = CARD_TYPES.get(kind);
            if (!type.equals(card.path("type").asText())) {
                throw QBusinessStore.validation("The " + kind + " card " + card.path("id").asText() + " has type "
                        + card.path("type").asText() + ", not " + type + ".");
            }
            if (!ids.add(card.path("id").asText())) {
                throw QBusinessStore.validation("Two cards share the id " + card.path("id").asText() + ".");
            }
            if (titles.put(card.path("title").asText(), card.path("id").asText()) != null) {
                throw QBusinessStore.validation("Two cards share the title " + card.path("title").asText() + ".");
            }
            card.put("_kind", kind);
            cards.add(card);
        }
        Map<String, Set<String>> dependencies = new HashMap<>();
        for (ObjectNode card : cards) {
            Set<String> depends = new LinkedHashSet<>();
            String prompt = card.path("prompt").asText("");
            for (Map.Entry<String, String> title : titles.entrySet()) {
                if (prompt.contains("@" + title.getKey()) && !title.getValue().equals(card.path("id").asText())) {
                    depends.add(title.getValue());
                }
            }
            if (prompt.contains("@" + card.path("title").asText()) && !card.path("title").asText().isEmpty()
                    && card.has("prompt")) {
                throw QBusinessStore.validation("The card " + card.path("title").asText() + " references itself.");
            }
            if ("qPlugin".equals(card.path("_kind").asText())) {
                service.plugin(region, instanceId, card.path("pluginId").asText());
            }
            if ("qQuery".equals(card.path("_kind").asText()) && !card.has("outputSource")) {
                card.put("outputSource", "approved-sources");
            }
            dependencies.put(card.path("id").asText(), depends);
        }
        for (String id : dependencies.keySet()) {
            checkCycle(id, dependencies, new HashSet<>());
        }
        ArrayNode out = mapper.createArrayNode();
        for (ObjectNode card : cards) {
            String kind = card.remove("_kind").asText();
            ArrayNode depends = card.putArray("dependencies");
            dependencies.get(card.path("id").asText()).forEach(depends::add);
            if ("qPlugin".equals(kind)) {
                card.put("pluginType", service.plugin(region, instanceId, card.path("pluginId").asText())
                        .path("type").asText());
            }
            out.addObject().set(kind, card);
        }
        return out;
    }

    private static void checkCycle(String id, Map<String, Set<String>> dependencies, Set<String> path) {
        if (!path.add(id)) {
            throw QBusinessStore.validation("The cards' references form a cycle.");
        }
        for (String next : dependencies.getOrDefault(id, Set.of())) {
            checkCycle(next, dependencies, path);
        }
        path.remove(id);
    }

    private static List<String> capabilities(ArrayNode cards) {
        Set<String> capabilities = new LinkedHashSet<>();
        for (JsonNode wrapper : cards) {
            String kind = wrapper.fieldNames().next();
            JsonNode card = wrapper.get(kind);
            switch (kind) {
                case "fileUpload" -> capabilities.add("FileUpload");
                case "qPlugin" -> capabilities.add("PluginMode");
                case "qQuery" -> capabilities.add("llm".equals(card.path("outputSource").asText()) ? "CreatorMode"
                        : "RetrievalMode");
                default -> {
                }
            }
        }
        return new ArrayList<>(capabilities);
    }

    // ================================================================ apps

    private ObjectNode app(String region, String instanceId, String appId) {
        ObjectNode app = store.get(region, QBusinessStore.QAPP, instanceId + "/" + appId)
                .orElseThrow(() -> QBusinessStore.notFound("app", appId));
        if ("DELETED".equals(app.path("status").asText())) {
            throw QBusinessStore.notFound("app", appId);
        }
        return app;
    }

    private boolean can(ObjectNode app, String action) {
        if (user().equals(app.path("createdBy").asText())) {
            return true;
        }
        for (JsonNode grant : app.path("_").path("permissions")) {
            if (user().equals(grant.path("principal").asText()) && ("write".equals(grant.path("action").asText())
                    || action.equals(grant.path("action").asText()))) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode appOut(ObjectNode app, ObjectNode version) {
        ObjectNode out = service.copy(app, "appId", "appArn", "title", "description", "status", "createdAt",
                "createdBy", "updatedAt", "updatedBy");
        out.put("appVersion", version.path("appVersion").asInt());
        if (version.hasNonNull("initialPrompt")) {
            out.put("initialPrompt", version.get("initialPrompt").asText());
        }
        out.set("requiredCapabilities", version.path("requiredCapabilities").deepCopy());
        return out;
    }

    private ObjectNode version(ObjectNode app, int number) {
        for (JsonNode version : QBusinessStore.meta(app).path("versions")) {
            if (version.path("appVersion").asInt() == number) {
                return (ObjectNode) version;
            }
        }
        throw QBusinessStore.notFound("app", app.path("appId").asText() + " version " + number);
    }

    private ObjectNode latest(ObjectNode app) {
        return version(app, app.path("appVersion").asInt());
    }

    private ObjectNode createQApp(JsonNode in, String region, String instanceId) {
        ArrayNode cards = cards(in.path("appDefinition"), region, instanceId);
        if (store.list(region, QBusinessStore.QAPP, instanceId + "/").size() >= 1000) {
            throw QBusinessStore.quota("An instance has at most 1000 apps.");
        }
        String id = UUID.randomUUID().toString();
        ObjectNode app = store.record();
        String at = now();
        app.put("appId", id).put("appArn", service.arn(region, "application/" + instanceId + "/qapp/" + id).replace(
                ":qbusiness:", ":qapps:")).put("title", QBusinessService.req(in, "title")).put("status", "DRAFT")
                .put("createdAt", at).put("createdBy", user()).put("updatedAt", at).put("updatedBy", user())
                .put("appVersion", 1);
        if (in.hasNonNull("description")) {
            app.put("description", in.get("description").asText());
        }
        ObjectNode version = QBusinessStore.meta(app).putArray("versions").addObject().put("appVersion", 1);
        version.set("cards", cards);
        ArrayNode capabilities = version.putArray("requiredCapabilities");
        capabilities(cards).forEach(capabilities::add);
        if (in.at("/appDefinition/initialPrompt").isTextual()) {
            version.put("initialPrompt", in.at("/appDefinition/initialPrompt").asText());
        }
        QBusinessStore.addTags(app, in.path("tags"));
        store.put(region, QBusinessStore.QAPP, instanceId + "/" + id, app);
        return appOut(app, version);
    }

    private ObjectNode getQApp(JsonNode in, String region, String instanceId) {
        ObjectNode app = app(region, instanceId, QBusinessService.req(in, "appId"));
        if (!can(app, "read") && !"PUBLISHED".equals(app.path("status").asText())) {
            throw QBusinessStore.denied("You can't read the app.");
        }
        ObjectNode version = in.hasNonNull("appVersion") ? version(app, in.get("appVersion").asInt()) : latest(app);
        ObjectNode out = appOut(app, version);
        ObjectNode definition = out.putObject("appDefinition").put("appDefinitionVersion", "1")
                .put("canEdit", can(app, "write"));
        definition.set("cards", version.path("cards").deepCopy());
        return out;
    }

    private ObjectNode updateQApp(JsonNode in, String region, String instanceId) {
        ObjectNode app = app(region, instanceId, QBusinessService.req(in, "appId"));
        if (!can(app, "write")) {
            throw QBusinessStore.denied("You can't edit the app.");
        }
        ObjectNode previous = latest(app);
        ObjectNode version = mapper.createObjectNode().put("appVersion", previous.path("appVersion").asInt() + 1);
        ArrayNode cards = in.has("appDefinition") ? cards(in.get("appDefinition"), region, instanceId)
                : (ArrayNode) previous.get("cards").deepCopy();
        version.set("cards", cards);
        ArrayNode capabilities = version.putArray("requiredCapabilities");
        capabilities(cards).forEach(capabilities::add);
        String prompt = in.at("/appDefinition/initialPrompt").asText(previous.path("initialPrompt").asText(null));
        if (prompt != null) {
            version.put("initialPrompt", prompt);
        }
        ((ArrayNode) QBusinessStore.meta(app).get("versions")).add(version);
        for (String member : List.of("title", "description")) {
            if (in.hasNonNull(member)) {
                app.put(member, in.get(member).asText());
            }
        }
        app.put("appVersion", version.get("appVersion").asInt()).put("updatedAt", now()).put("updatedBy", user());
        service.save(app);
        return appOut(app, version);
    }

    private ObjectNode deleteQApp(JsonNode in, String region, String instanceId) {
        ObjectNode app = app(region, instanceId, QBusinessService.req(in, "appId"));
        if (!can(app, "write")) {
            throw QBusinessStore.denied("You can't delete the app.");
        }
        String appId = app.get("appId").asText();
        for (ObjectNode item : store.list(region, QBusinessStore.LIBRARY, instanceId + "/")) {
            if (appId.equals(item.path("appId").asText())) {
                store.delete(region, QBusinessStore.LIBRARY, QBusinessStore.id(item));
            }
        }
        store.delete(region, QBusinessStore.QAPP, QBusinessStore.id(app));
        return out();
    }

    private ObjectNode listQApps(JsonNode in, String region, String instanceId) {
        List<ObjectNode> views = new ArrayList<>();
        Set<String> associated = new HashSet<>();
        store.get(region, "qapp-user", instanceId + "/" + user()).ifPresent(record -> record.path("apps")
                .forEach(id -> associated.add(id.asText())));
        for (ObjectNode app : store.list(region, QBusinessStore.QAPP, instanceId + "/")) {
            if (can(app, "read") || associated.contains(app.path("appId").asText())) {
                ObjectNode view = service.copy(app, "appId", "appArn", "title", "description", "createdAt",
                        "status");
                view.put("canEdit", can(app, "write")).put("isVerified", verified(region, instanceId,
                        app.path("appId").asText()));
                views.add(view);
            }
        }
        ObjectNode out = out();
        QBusinessStore.page(new ArrayList<JsonNode>(views), in, out, "apps");
        return out;
    }

    private boolean verified(String region, String instanceId, String appId) {
        for (ObjectNode item : store.list(region, QBusinessStore.LIBRARY, instanceId + "/")) {
            if (appId.equals(item.path("appId").asText()) && item.path("isVerified").asBoolean()) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode permissions(String region, ObjectNode app) {
        ObjectNode out = out().put("resourceArn", app.get("appArn").asText()).put("appId", app.get("appId").asText());
        ArrayNode permissions = out.putArray("permissions");
        ObjectNode owner = permissions.addObject().put("action", "write");
        owner.putObject("principal").put("userId", app.get("createdBy").asText()).put("userType", "owner");
        for (JsonNode grant : QBusinessStore.meta(app).path("permissions")) {
            ObjectNode principal = permissions.addObject().put("action", grant.path("action").asText())
                    .putObject("principal").put("userType", "user");
            String id = grant.path("principal").asText();
            if (id.contains("@")) {
                principal.put("email", id);
            } else {
                principal.put("userId", id);
            }
        }
        return out;
    }

    private ObjectNode updatePermissions(JsonNode in, String region, String instanceId) {
        ObjectNode app = app(region, instanceId, QBusinessService.req(in, "appId"));
        if (!user().equals(app.path("createdBy").asText())) {
            throw QBusinessStore.denied("Only the app's owner changes its permissions.");
        }
        ObjectNode meta = QBusinessStore.meta(app);
        ArrayNode grants = meta.has("permissions") ? (ArrayNode) meta.get("permissions") : meta.putArray("permissions");
        for (JsonNode revoke : in.path("revokePermissions")) {
            for (int i = grants.size() - 1; i >= 0; i--) {
                if (grants.get(i).path("principal").asText().equals(revoke.path("principal").asText())
                        && grants.get(i).path("action").asText().equals(revoke.path("action").asText())) {
                    grants.remove(i);
                }
            }
        }
        for (JsonNode grant : in.path("grantPermissions")) {
            boolean present = false;
            for (JsonNode existing : grants) {
                present |= existing.equals(grant);
            }
            if (!present) {
                grants.add(grant.deepCopy());
            }
        }
        if (grants.size() > 100) {
            throw QBusinessStore.validation("An app is shared with at most 100 principals.");
        }
        service.save(app);
        return permissions(region, app);
    }

    private ObjectNode associate(JsonNode in, String region, String instanceId, boolean add) {
        ObjectNode app = app(region, instanceId, QBusinessService.req(in, "appId"));
        if (add && !can(app, "read") && !"PUBLISHED".equals(app.path("status").asText())) {
            throw QBusinessStore.denied("The app isn't published to the library.");
        }
        ObjectNode record = store.get(region, "qapp-user", instanceId + "/" + user()).orElseGet(() -> {
            ObjectNode created = store.record();
            created.putArray("apps");
            return created;
        });
        ArrayNode apps = (ArrayNode) record.get("apps");
        Set<String> ids = new LinkedHashSet<>();
        apps.forEach(id -> ids.add(id.asText()));
        if (add) {
            ids.add(app.get("appId").asText());
        } else if (!ids.remove(app.get("appId").asText())) {
            throw QBusinessStore.notFound("app", app.get("appId").asText());
        }
        apps.removeAll();
        ids.forEach(apps::add);
        store.put(region, "qapp-user", instanceId + "/" + user(), record);
        return out();
    }

    // ================================================================ sessions

    private ObjectNode session(String region, String instanceId, String id) {
        return store.get(region, QBusinessStore.SESSION, instanceId + "/" + id)
                .orElseThrow(() -> QBusinessStore.notFound("session", id));
    }

    private ObjectNode startSession(JsonNode in, String region, String instanceId) {
        ObjectNode app = app(region, instanceId, QBusinessService.req(in, "appId"));
        if (!can(app, "read") && !"PUBLISHED".equals(app.path("status").asText())) {
            throw QBusinessStore.denied("You can't run the app.");
        }
        ObjectNode version = version(app, in.path("appVersion").asInt());
        String id = QBusinessService.opt(in, "sessionId") == null ? UUID.randomUUID().toString()
                : in.get("sessionId").asText();
        if (store.get(region, QBusinessStore.SESSION, instanceId + "/" + id).isPresent()) {
            throw QBusinessStore.validation("The session " + id + " already exists.");
        }
        ObjectNode session = store.record();
        session.put("sessionId", id).put("sessionArn", service.arn(region, "application/" + instanceId
                + "/qapp-session/" + id).replace(":qbusiness:", ":qapps:")).put("appId", app.get("appId").asText())
                .put("appVersion", version.get("appVersion").asInt()).put("host", user());
        session.putObject("sharingConfiguration").put("enabled", false).put("acceptResponses", false)
                .put("revealCards", false);
        ObjectNode values = QBusinessStore.meta(session).putObject("values");
        for (JsonNode wrapper : version.path("cards")) {
            JsonNode card = wrapper.elements().next();
            if (card.hasNonNull("defaultValue")) {
                values.put(card.get("id").asText(), card.get("defaultValue").asText());
            }
            if (card.hasNonNull("fileId")) {
                values.put(card.get("id").asText(), card.path("filename").asText(card.get("fileId").asText()));
            }
        }
        QBusinessStore.meta(session).putObject("submissions");
        QBusinessStore.addTags(session, in.path("tags"));
        apply(region, instanceId, session, in.path("initialValues"));
        compute(region, instanceId, session, version);
        store.put(region, QBusinessStore.SESSION, instanceId + "/" + id, session);
        return out().put("sessionId", id).put("sessionArn", session.get("sessionArn").asText());
    }

    /** Applies card values: plain values for inputs, and submission mutations for form cards. */
    private void apply(String region, String instanceId, ObjectNode session, JsonNode values) {
        ObjectNode current = (ObjectNode) QBusinessStore.meta(session).get("values");
        ObjectNode submissions = (ObjectNode) QBusinessStore.meta(session).get("submissions");
        for (JsonNode value : values) {
            String cardId = value.path("cardId").asText();
            JsonNode mutation = value.path("submissionMutation");
            if (mutation.isObject()) {
                ArrayNode list = submissions.has(cardId) ? (ArrayNode) submissions.get(cardId)
                        : submissions.putArray(cardId);
                String submissionId = mutation.path("submissionId").asText();
                switch (mutation.path("mutationType").asText()) {
                    case "add" -> list.addObject().put("submissionId", submissionId.isEmpty()
                            ? UUID.randomUUID().toString() : submissionId).put("value", value.path("value").asText())
                            .put("timestamp", now()).put("user", user());
                    case "edit", "delete" -> {
                        boolean found = false;
                        for (int i = 0; i < list.size(); i++) {
                            if (submissionId.equals(list.get(i).path("submissionId").asText())) {
                                found = true;
                                if ("delete".equals(mutation.path("mutationType").asText())) {
                                    list.remove(i);
                                } else {
                                    ((ObjectNode) list.get(i)).put("value", value.path("value").asText())
                                            .put("timestamp", now());
                                }
                                break;
                            }
                        }
                        if (!found) {
                            throw QBusinessStore.notFound("submission", submissionId);
                        }
                    }
                    default -> throw QBusinessStore.validation("Unknown mutationType.");
                }
            } else {
                current.put(cardId, value.path("value").asText());
            }
        }
    }

    /** Computes every card whose dependencies are complete, in dependency order. */
    private void compute(String region, String instanceId, ObjectNode session, ObjectNode version) {
        ObjectNode values = (ObjectNode) QBusinessStore.meta(session).get("values");
        ObjectNode submissions = (ObjectNode) QBusinessStore.meta(session).get("submissions");
        ObjectNode status = mapper.createObjectNode();
        Map<String, JsonNode> cards = new LinkedHashMap<>();
        Map<String, String> titles = new HashMap<>();
        Map<String, String> kinds = new HashMap<>();
        for (JsonNode wrapper : version.path("cards")) {
            String kind = wrapper.fieldNames().next();
            JsonNode card = wrapper.get(kind);
            cards.put(card.get("id").asText(), card);
            titles.put(card.get("id").asText(), card.path("title").asText());
            kinds.put(card.get("id").asText(), kind);
        }
        boolean progress = true;
        Set<String> done = new HashSet<>();
        Set<String> failed = new HashSet<>();
        while (progress) {
            progress = false;
            for (Map.Entry<String, JsonNode> entry : cards.entrySet()) {
                String id = entry.getKey();
                if (done.contains(id) || failed.contains(id)) {
                    continue;
                }
                JsonNode card = entry.getValue();
                String kind = kinds.get(id);
                ObjectNode state = mapper.createObjectNode();
                switch (kind) {
                    case "textInput", "fileUpload" -> {
                        String value = values.path(id).asText("");
                        state.put("currentState", value.isEmpty() ? "WAITING" : "COMPLETED").put("currentValue",
                                value);
                    }
                    case "formInput" -> {
                        ArrayNode list = submissions.has(id) ? (ArrayNode) submissions.get(id)
                                : mapper.createArrayNode();
                        state.put("currentState", list.isEmpty() ? "WAITING" : "COMPLETED").put("currentValue",
                                list.isEmpty() ? "" : list.get(list.size() - 1).path("value").asText());
                        ArrayNode out = state.putArray("submissions");
                        list.forEach(s -> out.addObject().put("submissionId", s.path("submissionId").asText())
                                .put("timestamp", s.path("timestamp").asText()).set("value", parse(s.path("value")
                                        .asText())));
                    }
                    default -> {
                        boolean ready = true;
                        for (JsonNode dependency : card.path("dependencies")) {
                            ready &= done.contains(dependency.asText()) && "COMPLETED".equals(status
                                    .path(dependency.asText()).path("currentState").asText());
                        }
                        if (!ready) {
                            boolean blocked = false;
                            for (JsonNode dependency : card.path("dependencies")) {
                                blocked |= failed.contains(dependency.asText()) || (done.contains(dependency.asText())
                                        && !"COMPLETED".equals(status.path(dependency.asText()).path("currentState")
                                        .asText()));
                            }
                            if (!blocked) {
                                continue;
                            }
                            state.put("currentState", "WAITING").put("currentValue", "");
                            break;
                        }
                        String prompt = card.path("prompt").asText();
                        for (JsonNode dependency : card.path("dependencies")) {
                            prompt = prompt.replace("@" + titles.get(dependency.asText()),
                                    status.path(dependency.asText()).path("currentValue").asText());
                        }
                        try {
                            String value = "qPlugin".equals(kind) ? plugin(region, instanceId, card, prompt)
                                    : query(region, instanceId, card, prompt, session);
                            state.put("currentState", "COMPLETED").put("currentValue", value);
                        } catch (AwsException e) {
                            state.put("currentState", "ERROR").put("currentValue", e.getMessage());
                            failed.add(id);
                        }
                    }
                }
                status.set(id, state);
                if (!failed.contains(id)) {
                    done.add(id);
                }
                progress = true;
            }
        }
        for (String id : cards.keySet()) {
            if (!status.has(id)) {
                status.putObject(id).put("currentState", "WAITING").put("currentValue", "");
            }
        }
        QBusinessStore.meta(session).set("cardStatus", status);
        String overall = "COMPLETED";
        for (JsonNode state : status) {
            String value = state.path("currentState").asText();
            if (value.equals("ERROR")) {
                overall = "ERROR";
                break;
            }
            if (value.equals("WAITING")) {
                overall = "WAITING";
            }
        }
        session.put("status", overall);
    }

    private JsonNode parse(String value) {
        try {
            return mapper.readTree(value);
        } catch (IOException e) {
            return mapper.getNodeFactory().textNode(value);
        }
    }

    /** A query card's answer: from the instance's documents and the session's files, or from the model. */
    private String query(String region, String instanceId, JsonNode card, String prompt, ObjectNode session) {
        if ("llm".equals(card.path("outputSource").asText())) {
            return chat.generateText(prompt);
        }
        String indexId = chat.indexOf(region, instanceId);
        List<Passage> passages = chat.passages(region, instanceId, indexId, null, card.path("attributeFilter"), null);
        for (ObjectNode file : store.list(region, QBusinessStore.FILE, instanceId + "/")) {
            String scope = file.path("scope").asText();
            if ("SESSION".equals(scope) && !session.path("sessionId").asText().equals(file.path("sessionId").asText())) {
                continue;
            }
            for (JsonNode text : QBusinessStore.meta(file).path("passages")) {
                passages.add(new Passage(file.get("fileId").asText(), file.get("fileName").asText(), null,
                        text.asText(), mapper.createArrayNode(), null));
            }
        }
        List<Hit> hits = QBusinessSearch.rank(prompt, passages, 5);
        return chat.answer(prompt, hits, indexId).text();
    }

    private String plugin(String region, String instanceId, JsonNode card, String prompt) {
        ObjectNode plugin = service.plugin(region, instanceId, card.path("pluginId").asText());
        if (!"READY".equals(plugin.path("buildStatus").asText())) {
            throw QBusinessStore.conflict("plugin", plugin.get("pluginId").asText(), "The plugin isn't READY.");
        }
        return "Ready to run " + card.path("actionIdentifier").asText(plugin.get("displayName").asText()) + " for: "
                + prompt;
    }

    private ObjectNode getSession(JsonNode in, String region, String instanceId) {
        ObjectNode session = session(region, instanceId, QBusinessService.req(in, "sessionId"));
        ObjectNode app = store.get(region, QBusinessStore.QAPP, instanceId + "/" + session.get("appId").asText())
                .orElse(null);
        ObjectNode out = service.copy(session, "sessionId", "sessionArn", "sessionName", "appVersion", "status");
        out.put("latestPublishedAppVersion", app == null ? session.path("appVersion").asInt()
                : app.path("appVersion").asInt());
        out.set("cardStatus", QBusinessStore.meta(session).path("cardStatus").deepCopy());
        out.put("userIsHost", user().equals(session.path("host").asText()));
        return out;
    }

    private ObjectNode updateSession(JsonNode in, String region, String instanceId) {
        ObjectNode session = session(region, instanceId, QBusinessService.req(in, "sessionId"));
        boolean host = user().equals(session.path("host").asText());
        if (!host && !session.at("/sharingConfiguration/acceptResponses").asBoolean()) {
            throw QBusinessStore.denied("The session doesn't accept responses.");
        }
        ObjectNode app = app(region, instanceId, session.get("appId").asText());
        apply(region, instanceId, session, in.path("values"));
        compute(region, instanceId, session, version(app, session.get("appVersion").asInt()));
        service.save(session);
        return out().put("sessionId", session.get("sessionId").asText()).put("sessionArn",
                session.get("sessionArn").asText());
    }

    private ObjectNode stopSession(JsonNode in, String region, String instanceId) {
        ObjectNode session = session(region, instanceId, QBusinessService.req(in, "sessionId"));
        if (!user().equals(session.path("host").asText())) {
            throw QBusinessStore.denied("Only the session's host stops it.");
        }
        store.delete(region, QBusinessStore.SESSION, QBusinessStore.id(session));
        return out();
    }

    private ObjectNode sessionMetadata(ObjectNode session) {
        ObjectNode out = service.copy(session, "sessionId", "sessionArn", "sessionName", "sharingConfiguration");
        out.put("sessionOwner", user().equals(session.path("host").asText()));
        return out;
    }

    private ObjectNode updateSessionMetadata(JsonNode in, String region, String instanceId) {
        ObjectNode session = session(region, instanceId, QBusinessService.req(in, "sessionId"));
        if (!user().equals(session.path("host").asText())) {
            throw QBusinessStore.denied("Only the session's host changes it.");
        }
        if (in.hasNonNull("sessionName")) {
            session.put("sessionName", in.get("sessionName").asText());
        }
        session.set("sharingConfiguration", in.get("sharingConfiguration").deepCopy());
        service.save(session);
        ObjectNode out = service.copy(session, "sessionId", "sessionArn", "sessionName", "sharingConfiguration");
        return out;
    }

    private ObjectNode listSessionData(JsonNode in, String region, String instanceId) {
        ObjectNode session = session(region, instanceId, QBusinessService.req(in, "sessionId"));
        ArrayNode data = mapper.createArrayNode();
        QBusinessStore.meta(session).path("submissions").properties().forEach(card -> card.getValue().forEach(s -> {
            ObjectNode item = data.addObject().put("cardId", card.getKey()).put("submissionId",
                    s.path("submissionId").asText()).put("timestamp", s.path("timestamp").asText());
            item.set("value", parse(s.path("value").asText()));
            item.putObject("user").put("userId", s.path("user").asText());
        }));
        ObjectNode out = out().put("sessionId", session.get("sessionId").asText()).put("sessionArn",
                session.get("sessionArn").asText());
        out.set("sessionData", data);
        return out;
    }

    private ObjectNode exportSessionData(JsonNode in, String region, String instanceId) {
        ObjectNode session = session(region, instanceId, QBusinessService.req(in, "sessionId"));
        StringBuilder csv = new StringBuilder("cardId,submissionId,userId,timestamp,value\n");
        QBusinessStore.meta(session).path("submissions").properties().forEach(card -> card.getValue().forEach(s ->
                csv.append(card.getKey()).append(',').append(s.path("submissionId").asText()).append(',')
                        .append(s.path("user").asText()).append(',').append(s.path("timestamp").asText()).append(",\"")
                        .append(s.path("value").asText().replace("\"", "\"\"")).append("\"\n")));
        ObjectNode export = store.record();
        String id = UUID.randomUUID().toString();
        QBusinessStore.meta(export).put("data", Base64.getEncoder().encodeToString(csv.toString().getBytes(
                StandardCharsets.UTF_8))).put("mime", "text/csv");
        store.put(region, "qapp-export", instanceId + "/" + id, export);
        long seconds = store.config().presignedUrlSeconds();
        String token = QBusinessContentLinks.token(seconds, "export", store.account(), region, instanceId, id);
        return out().put("csvFileLink", store.endpoint() + QBusinessContentLinks.PATH + token)
                .put("expiresAt", QBusinessStore.iso(QBusinessStore.now() + seconds))
                .put("sessionArn", session.get("sessionArn").asText());
    }

    // ================================================================ files

    private JsonNode fileCard(ObjectNode app, String cardId) {
        for (JsonNode wrapper : latest(app).path("cards")) {
            if (wrapper.has("fileUpload") && cardId.equals(wrapper.get("fileUpload").path("id").asText())) {
                return wrapper.get("fileUpload");
            }
        }
        throw QBusinessStore.validation("The card " + cardId + " isn't a file upload card of the app.");
    }

    /** Stores a file for a card, at application or session scope, and indexes its text for the app's queries. */
    String storeFile(String region, String instanceId, String appId, String cardId, String name, String scope,
                     String sessionId, byte[] data) {
        if (data.length > 10 * 1024 * 1024) {
            throw new AwsException("ContentTooLargeException", "The file is larger than 10 MB.", 413);
        }
        String id = UUID.randomUUID().toString();
        ObjectNode file = store.record();
        file.put("fileId", id).put("fileName", name).put("appId", appId).put("cardId", cardId).put("scope", scope);
        if (sessionId != null) {
            file.put("sessionId", sessionId);
        }
        ArrayNode passages = QBusinessStore.meta(file).putArray("passages");
        try {
            QBusinessSearch.passages(QBusinessSearch.text(data, QBusinessService.typeOf(name))).forEach(passages::add);
        } catch (AwsException e) {
            passages.removeAll();
        }
        QBusinessStore.meta(file).put("data", Base64.getEncoder().encodeToString(data));
        store.put(region, QBusinessStore.FILE, instanceId + "/" + id, file);
        if ("SESSION".equals(scope)) {
            ObjectNode session = session(region, instanceId, sessionId);
            ((ObjectNode) QBusinessStore.meta(session).get("values")).put(cardId, name);
            ObjectNode app = app(region, instanceId, appId);
            compute(region, instanceId, session, version(app, session.get("appVersion").asInt()));
            service.save(session);
        } else {
            ObjectNode app = app(region, instanceId, appId);
            for (JsonNode wrapper : latest(app).path("cards")) {
                if (wrapper.has("fileUpload") && cardId.equals(wrapper.get("fileUpload").path("id").asText())) {
                    ((ObjectNode) wrapper.get("fileUpload")).put("fileId", id).put("filename", name);
                }
            }
            service.save(app);
        }
        return id;
    }

    private ObjectNode importDocument(JsonNode in, String region, String instanceId) {
        String appId = QBusinessService.req(in, "appId");
        ObjectNode app = app(region, instanceId, appId);
        String cardId = QBusinessService.req(in, "cardId");
        fileCard(app, cardId);
        String scope = QBusinessService.req(in, "scope");
        String sessionId = QBusinessService.opt(in, "sessionId");
        if ("SESSION".equals(scope) && sessionId == null) {
            throw QBusinessStore.validation("A SESSION-scoped file needs its sessionId.");
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(QBusinessService.req(in, "fileContentsBase64"));
        } catch (IllegalArgumentException e) {
            throw QBusinessStore.validation("fileContentsBase64 isn't base64.");
        }
        return out().put("fileId", storeFile(region, instanceId, appId, cardId, QBusinessService.req(in, "fileName"),
                scope, sessionId, data));
    }

    private ObjectNode createPresignedUrl(JsonNode in, String region, String instanceId) {
        String appId = QBusinessService.req(in, "appId");
        ObjectNode app = app(region, instanceId, appId);
        String cardId = QBusinessService.req(in, "cardId");
        fileCard(app, cardId);
        String scope = QBusinessService.req(in, "scope");
        String sessionId = QBusinessService.opt(in, "sessionId");
        if ("SESSION".equals(scope)) {
            if (sessionId == null) {
                throw QBusinessStore.validation("A SESSION-scoped file needs its sessionId.");
            }
            session(region, instanceId, sessionId);
        }
        long seconds = Math.min(store.config().presignedUrlSeconds(), 300);
        String token = QBusinessContentLinks.token(seconds, "upload", store.account(), region, instanceId, appId,
                cardId, QBusinessService.req(in, "fileName"), scope, sessionId == null ? "" : sessionId,
                QBusinessService.req(in, "fileContentsSha256"));
        ObjectNode out = out().put("fileId", "pending-" + token.substring(0, 12))
                .put("presignedUrl", store.endpoint() + QBusinessContentLinks.PATH + token)
                .put("presignedUrlExpiration", QBusinessStore.iso(QBusinessStore.now() + seconds));
        out.putObject("presignedUrlFields").put("Content-Type", "application/octet-stream")
                .put("x-amz-checksum-sha256", in.get("fileContentsSha256").asText());
        return out;
    }

    static String sha256(byte[] data) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ================================================================ categories

    private ArrayNode categories(String region, String instanceId) {
        ObjectNode record = store.get(region, QBusinessStore.CATEGORY, instanceId).orElseGet(() -> {
            ObjectNode created = store.record();
            created.putArray("categories");
            return created;
        });
        return (ArrayNode) record.get("categories");
    }

    private void saveCategories(String region, String instanceId, ArrayNode categories) {
        ObjectNode record = store.record();
        record.set("categories", categories);
        store.put(region, QBusinessStore.CATEGORY, instanceId, record);
    }

    private ObjectNode createCategories(JsonNode in, String region, String instanceId) {
        ArrayNode categories = categories(region, instanceId);
        for (JsonNode input : in.path("categories")) {
            for (JsonNode existing : categories) {
                if (existing.path("title").asText().equalsIgnoreCase(input.path("title").asText())) {
                    throw QBusinessStore.conflict("category", existing.path("id").asText(), "The category "
                            + input.path("title").asText() + " already exists.");
                }
            }
            ObjectNode category = categories.addObject().put("id", input.path("id").asText(UUID.randomUUID()
                    .toString())).put("title", input.path("title").asText());
            if (input.hasNonNull("color")) {
                category.put("color", input.get("color").asText());
            }
        }
        if (categories.size() > 10) {
            throw QBusinessStore.validation("An instance has at most 10 categories.");
        }
        saveCategories(region, instanceId, categories);
        return out();
    }

    private ObjectNode updateCategories(JsonNode in, String region, String instanceId) {
        ArrayNode categories = categories(region, instanceId);
        for (JsonNode input : in.path("categories")) {
            boolean found = false;
            for (JsonNode existing : categories) {
                if (existing.path("id").asText().equals(input.path("id").asText())) {
                    ((ObjectNode) existing).put("title", input.path("title").asText());
                    if (input.hasNonNull("color")) {
                        ((ObjectNode) existing).put("color", input.get("color").asText());
                    }
                    found = true;
                }
            }
            if (!found) {
                throw QBusinessStore.notFound("category", input.path("id").asText());
            }
        }
        saveCategories(region, instanceId, categories);
        return out();
    }

    private ObjectNode deleteCategories(JsonNode in, String region, String instanceId) {
        ArrayNode categories = categories(region, instanceId);
        for (JsonNode id : in.path("categories")) {
            boolean found = false;
            for (int i = categories.size() - 1; i >= 0; i--) {
                if (categories.get(i).path("id").asText().equals(id.asText())) {
                    categories.remove(i);
                    found = true;
                }
            }
            if (!found) {
                throw QBusinessStore.notFound("category", id.asText());
            }
            for (ObjectNode item : store.list(region, QBusinessStore.LIBRARY, instanceId + "/")) {
                ArrayNode itemCategories = (ArrayNode) item.get("categoryIds");
                for (int i = itemCategories.size() - 1; i >= 0; i--) {
                    if (itemCategories.get(i).asText().equals(id.asText())) {
                        itemCategories.remove(i);
                    }
                }
                service.save(item);
            }
        }
        saveCategories(region, instanceId, categories);
        return out();
    }

    private ObjectNode listCategories(String region, String instanceId) {
        ArrayNode categories = categories(region, instanceId).deepCopy();
        for (JsonNode category : categories) {
            int count = 0;
            for (ObjectNode item : store.list(region, QBusinessStore.LIBRARY, instanceId + "/")) {
                for (JsonNode id : item.path("categoryIds")) {
                    count += id.asText().equals(category.path("id").asText()) ? 1 : 0;
                }
            }
            ((ObjectNode) category).put("appCount", count);
        }
        ObjectNode out = out();
        out.set("categories", categories);
        return out;
    }

    // ================================================================ library

    private ObjectNode libraryItem(String region, String instanceId, String id) {
        return store.get(region, QBusinessStore.LIBRARY, instanceId + "/" + id)
                .orElseThrow(() -> QBusinessStore.notFound("library-item", id));
    }

    private ArrayNode categoryIds(String region, String instanceId, JsonNode ids) {
        ArrayNode categories = categories(region, instanceId);
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode id : ids) {
            boolean found = false;
            for (JsonNode category : categories) {
                found |= category.path("id").asText().equals(id.asText());
            }
            if (!found) {
                throw QBusinessStore.notFound("category", id.asText());
            }
            out.add(id.asText());
        }
        return out;
    }

    private ObjectNode libraryView(String region, String instanceId, ObjectNode item) {
        ObjectNode view = service.copy(item, "libraryItemId", "appId", "appVersion", "status", "createdAt",
                "createdBy", "updatedAt", "updatedBy", "ratingCount", "isVerified");
        ArrayNode categories = view.putArray("categories");
        for (JsonNode id : item.path("categoryIds")) {
            for (JsonNode category : categories(region, instanceId)) {
                if (category.path("id").asText().equals(id.asText())) {
                    categories.add(category.deepCopy());
                }
            }
        }
        Set<String> raters = new HashSet<>();
        item.path("raters").forEach(r -> raters.add(r.asText()));
        view.put("isRatedByUser", raters.contains(user()));
        int users = 0;
        for (ObjectNode record : store.list(region, "qapp-user", instanceId + "/")) {
            for (JsonNode app : record.path("apps")) {
                users += app.asText().equals(item.path("appId").asText()) ? 1 : 0;
            }
        }
        view.put("userCount", users);
        return view;
    }

    private ObjectNode createLibraryItem(JsonNode in, String region, String instanceId) {
        ObjectNode app = app(region, instanceId, QBusinessService.req(in, "appId"));
        if (!can(app, "write")) {
            throw QBusinessStore.denied("You can't publish the app.");
        }
        version(app, in.path("appVersion").asInt());
        for (ObjectNode existing : store.list(region, QBusinessStore.LIBRARY, instanceId + "/")) {
            if (app.get("appId").asText().equals(existing.path("appId").asText())) {
                throw QBusinessStore.conflict("library-item", existing.get("libraryItemId").asText(),
                        "The app is already in the library.");
            }
        }
        String id = UUID.randomUUID().toString();
        ObjectNode item = store.record();
        String at = now();
        item.put("libraryItemId", id).put("appId", app.get("appId").asText()).put("appVersion",
                in.path("appVersion").asInt()).put("status", "PUBLISHED").put("createdAt", at).put("createdBy", user())
                .put("updatedAt", at).put("updatedBy", user()).put("ratingCount", 0).put("isVerified", false);
        item.set("categoryIds", categoryIds(region, instanceId, in.path("categories")));
        item.putArray("raters");
        store.put(region, QBusinessStore.LIBRARY, instanceId + "/" + id, item);
        app.put("status", "PUBLISHED");
        service.save(app);
        return service.copy(item, "libraryItemId", "status", "createdAt", "createdBy", "updatedAt", "updatedBy",
                "ratingCount", "isVerified");
    }

    private ObjectNode updateLibraryItem(JsonNode in, String region, String instanceId) {
        ObjectNode item = libraryItem(region, instanceId, QBusinessService.req(in, "libraryItemId"));
        if (in.hasNonNull("status")) {
            item.put("status", in.get("status").asText());
        }
        if (in.has("categories")) {
            item.set("categoryIds", categoryIds(region, instanceId, in.get("categories")));
        }
        item.put("updatedAt", now()).put("updatedBy", user());
        service.save(item);
        store.get(region, QBusinessStore.QAPP, instanceId + "/" + item.get("appId").asText()).ifPresent(app -> {
            app.put("status", "DISABLED".equals(item.path("status").asText()) ? "DRAFT" : "PUBLISHED");
            service.save(app);
        });
        return libraryView(region, instanceId, item);
    }

    private ObjectNode updateLibraryItemMetadata(JsonNode in, String region, String instanceId) {
        ObjectNode item = libraryItem(region, instanceId, QBusinessService.req(in, "libraryItemId"));
        if (in.has("isVerified")) {
            item.put("isVerified", in.get("isVerified").asBoolean());
        }
        service.save(item);
        return out();
    }

    private ObjectNode deleteLibraryItem(JsonNode in, String region, String instanceId) {
        ObjectNode item = libraryItem(region, instanceId, QBusinessService.req(in, "libraryItemId"));
        store.delete(region, QBusinessStore.LIBRARY, QBusinessStore.id(item));
        store.get(region, QBusinessStore.QAPP, instanceId + "/" + item.get("appId").asText()).ifPresent(app -> {
            app.put("status", "DRAFT");
            service.save(app);
        });
        return out();
    }

    private ObjectNode listLibraryItems(JsonNode in, String region, String instanceId) {
        String category = QBusinessService.opt(in, "categoryId");
        List<ObjectNode> views = new ArrayList<>();
        for (ObjectNode item : store.list(region, QBusinessStore.LIBRARY, instanceId + "/")) {
            boolean matches = category == null;
            for (JsonNode id : item.path("categoryIds")) {
                matches |= id.asText().equals(category);
            }
            if (matches && "PUBLISHED".equals(item.path("status").asText())) {
                views.add(libraryView(region, instanceId, item));
            }
        }
        ObjectNode out = out();
        QBusinessStore.page(new ArrayList<JsonNode>(views), in, out, "libraryItems");
        return out;
    }

    private ObjectNode review(JsonNode in, String region, String instanceId, boolean add) {
        ObjectNode item = libraryItem(region, instanceId, QBusinessService.req(in, "libraryItemId"));
        ArrayNode raters = (ArrayNode) item.get("raters");
        Set<String> ids = new LinkedHashSet<>();
        raters.forEach(r -> ids.add(r.asText()));
        if (add ? !ids.add(user()) : !ids.remove(user())) {
            throw QBusinessStore.conflict("library-item", item.get("libraryItemId").asText(), add
                    ? "You've already reviewed the app." : "You haven't reviewed the app.");
        }
        raters.removeAll();
        ids.forEach(raters::add);
        item.put("ratingCount", ids.size());
        service.save(item);
        return out();
    }

    // ================================================================ PredictQApp

    private ObjectNode predict(JsonNode in) {
        String problem = in.at("/options/problemStatement").asText("");
        if (problem.isBlank()) {
            StringBuilder conversation = new StringBuilder();
            in.at("/options/conversation").forEach(message -> {
                if ("USER".equals(message.path("type").asText())) {
                    conversation.append(message.path("body").asText()).append(' ');
                }
            });
            problem = conversation.toString().trim();
        }
        if (problem.isBlank()) {
            throw QBusinessStore.validation("PredictQApp needs a problemStatement or a conversation.");
        }
        List<String> words = new ArrayList<>(List.of(problem.replaceAll("[^\\p{L}\\p{N} ]", " ").trim()
                .split("\\s+")));
        StringBuilder title = new StringBuilder();
        for (String word : words.subList(0, Math.min(6, words.size()))) {
            title.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT))
                    .append(' ');
        }
        ObjectNode out = out().put("problemStatement", problem);
        ObjectNode app = out.putObject("app").put("title", title.toString().trim()).put("description",
                "An app that helps with: " + problem);
        ObjectNode definition = app.putObject("appDefinition");
        ArrayNode cards = definition.putArray("cards");
        String inputId = UUID.randomUUID().toString();
        cards.addObject().putObject("textInput").put("id", inputId).put("title", "Details").put("type", "text-input")
                .put("placeholder", "Describe what you need");
        cards.addObject().putObject("qQuery").put("id", UUID.randomUUID().toString()).put("title", "Result")
                .put("type", "q-query").put("prompt", problem + "\n\nUse these details: @Details")
                .put("outputSource", "approved-sources");
        definition.put("initialPrompt", problem);
        return out;
    }
}
