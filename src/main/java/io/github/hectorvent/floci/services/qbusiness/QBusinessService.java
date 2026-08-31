package io.github.hectorvent.floci.services.qbusiness;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.qbusiness.model.Application;
import io.github.hectorvent.floci.services.qbusiness.model.Application.Index;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Amazon Q Business restJson1 — application lifecycle plus the data-plane
 * operations Alchemy {@code Bindings.test.ts} exercises.
 *
 * <p>CreateApplication does not validate the Identity Center instance ARN
 * synchronously. Applications using {@code AWS_IAM_IDC} (the default) are
 * created and converge to {@code FAILED} with an Identity Center error detail,
 * matching live AWS when the instance does not exist. {@code ANONYMOUS}
 * applications become {@code ACTIVE} immediately.
 *
 * <p>{@code ListSubscriptions} on a missing application answers an empty page
 * (live AWS does not 404 that call).
 */
@ApplicationScoped
public class QBusinessService {

    static final String SERVICE = "qbusiness";
    private static final String DEFAULT_IDENTITY_TYPE = "AWS_IAM_IDC";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_DELETING = "DELETING";

    static final class Document {
        String id;
        String title;
        String body;
        String status = "INDEXED";
        long createdAt;
        long updatedAt;
    }

    static final class DataSource {
        String id;
        String displayName;
        String type;
        String status = "ACTIVE";
        final List<SyncJob> syncJobs = new ArrayList<>();
    }

    static final class SyncJob {
        String executionId;
        long startTime;
        Long endTime;
        String status;
    }

    static final class Group {
        String name;
        long updatedAt;
    }

    static final class Retriever {
        String id;
        String displayName;
        String type;
        String status = "ACTIVE";
        String arn;
    }

    static final class WebExperience {
        String id;
        String title;
        String status = "ACTIVE";
        String defaultEndpoint;
        String arn;
    }

    static final class User {
        String id;
        JsonNode aliases;
    }

    static final class Conversation {
        String id;
        final List<Message> messages = new ArrayList<>();
    }

    static final class Message {
        String id;
        String type;
        String body;
    }

    static final class Subscription {
        String id;
        String arn;
        JsonNode principal;
        String type;
    }

    static final class Attachment {
        String id;
        String conversationId;
    }

    static final class AppExtras {
        final ConcurrentHashMap<String, Document> documents = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, DataSource> dataSources = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Group> groups = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Retriever> retrievers = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, WebExperience> experiences = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, User> users = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Conversation> conversations = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Subscription> subscriptions = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, JsonNode> permissions = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Attachment> attachments = new ConcurrentHashMap<>();
        String responseScope = "ENTERPRISE_CONTENT_ONLY";
        String policy;
    }

    private final StorageBackend<String, Application> store;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, AppExtras> extras = new ConcurrentHashMap<>();

    @Inject
    public QBusinessService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create(
                "qbusiness",
                "qbusiness-applications.json",
                new TypeReference<Map<String, Application>>() {
                }), regionResolver, objectMapper);
    }

    QBusinessService(
            StorageBackend<String, Application> store,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.store = store;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized Application createApplication(String region, JsonNode request) {
        requireObject(request, "Request body");
        String displayName = requireText(request, "displayName");
        String clientToken = textOrNull(request, "clientToken");
        if (clientToken != null) {
            for (Application existing : store.scan(key -> key.startsWith(prefix(region)))) {
                if (clientToken.equals(existing.getClientToken())
                        && !STATUS_DELETING.equals(existing.getStatus())) {
                    return existing;
                }
            }
        }
        Application clash = findByDisplayName(region, displayName);
        if (clash != null) {
            throw conflict(clash.getApplicationId(), "Application",
                    "An application with the display name " + displayName + " already exists.");
        }

        long now = now();
        String account = regionResolver.getAccountId();
        String id = UUID.randomUUID().toString();
        Application application = new Application();
        application.setApplicationId(id);
        application.setApplicationArn(arn(region, account, "application/" + id));
        application.setDisplayName(displayName);
        application.setDescription(textOrNull(request, "description"));
        application.setRoleArn(textOrNull(request, "roleArn"));
        application.setIamIdentityProviderArn(textOrNull(request, "iamIdentityProviderArn"));
        application.setIdentityCenterInstanceArn(textOrNull(request, "identityCenterInstanceArn"));
        application.setClientToken(clientToken);
        String identityType = textOrDefault(request, "identityType", DEFAULT_IDENTITY_TYPE);
        application.setIdentityType(identityType);
        application.setCreatedAt(now);
        application.setUpdatedAt(now);
        applyIdentityCenterOutcome(application);
        store.put(storageKey(region, id), application);
        extras.put(id, new AppExtras());
        return application;
    }

    public Application getApplication(String region, String applicationId) {
        return requireApplication(region, applicationId);
    }

    public synchronized Application updateApplication(String region, String applicationId, JsonNode request) {
        requireObject(request, "Request body");
        Application application = requireApplication(region, applicationId);
        boolean changed = false;
        if (request.has("displayName") && !request.get("displayName").isNull()) {
            String displayName = requireText(request, "displayName");
            Application clash = findByDisplayName(region, displayName);
            if (clash != null && !clash.getApplicationId().equals(application.getApplicationId())) {
                throw conflict(clash.getApplicationId(), "Application",
                        "An application with the display name " + displayName + " already exists.");
            }
            application.setDisplayName(displayName);
            changed = true;
        }
        if (request.has("description")) {
            application.setDescription(textOrNull(request, "description"));
            changed = true;
        }
        if (request.has("roleArn")) {
            application.setRoleArn(textOrNull(request, "roleArn"));
            changed = true;
        }
        if (request.has("identityCenterInstanceArn")) {
            application.setIdentityCenterInstanceArn(textOrNull(request, "identityCenterInstanceArn"));
            applyIdentityCenterOutcome(application);
            changed = true;
        }
        if (changed) {
            application.setUpdatedAt(now());
            store.put(storageKey(region, application.getApplicationId()), application);
        }
        return application;
    }

    public synchronized void deleteApplication(String region, String applicationId) {
        requireApplication(region, applicationId);
        store.delete(storageKey(region, applicationId));
        extras.remove(applicationId);
    }

    public List<Application> listApplications(String region) {
        List<Application> applications = new ArrayList<>();
        for (Application application : store.scan(key -> key.startsWith(prefix(region)))) {
            if (!STATUS_DELETING.equals(application.getStatus())) {
                applications.add(application);
            }
        }
        applications.sort(Comparator.comparing(Application::getDisplayName, Comparator.nullsLast(String::compareTo))
                .thenComparing(Application::getApplicationId));
        return applications;
    }

    public Index getIndex(String region, String applicationId, String indexId) {
        return requireIndex(region, applicationId, indexId);
    }

    public synchronized ObjectNode createIndex(String region, String applicationId, JsonNode request) {
        Application application = requireApplication(region, applicationId);
        String displayName = requireText(request, "displayName");
        String id = UUID.randomUUID().toString();
        Index index = new Index();
        index.setIndexId(id);
        index.setIndexArn(arn(region, regionResolver.getAccountId(),
                "application/" + applicationId + "/index/" + id));
        index.setDisplayName(displayName);
        index.setDescription(textOrNull(request, "description"));
        index.setStatus(STATUS_ACTIVE);
        index.setCreatedAt(now());
        index.setUpdatedAt(now());
        application.getIndexes().put(id, index);
        store.put(storageKey(region, applicationId), application);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("indexId", id);
        response.put("indexArn", index.getIndexArn());
        return response;
    }

    public ObjectNode chatSync(String applicationId, JsonNode request) {
        Application application = requireApplication(currentRegion(), applicationId);
        AppExtras extra = extras(applicationId);
        String userMessage = textOrDefault(request, "userMessage", "");
        Conversation conversation;
        String conversationId = textOrNull(request, "conversationId");
        if (conversationId != null) {
            conversation = extra.conversations.get(conversationId);
            if (conversation == null) {
                throw notFound(conversationId, "Conversation");
            }
        } else {
            conversation = new Conversation();
            conversation.id = newId();
            extra.conversations.put(conversation.id, conversation);
        }
        Message user = new Message();
        user.id = newId();
        user.type = "USER";
        user.body = userMessage;
        conversation.messages.add(user);
        String answer = answerFromDocuments(extra, userMessage);
        Message system = new Message();
        system.id = newId();
        system.type = "SYSTEM";
        system.body = answer;
        conversation.messages.add(system);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("conversationId", conversation.id);
        response.put("systemMessage", answer);
        response.put("systemMessageId", system.id);
        response.put("userMessageId", user.id);
        return response;
    }

    public ObjectNode searchRelevantContent(String applicationId, JsonNode request) {
        requireApplication(currentRegion(), applicationId);
        AppExtras extra = extras(applicationId);
        JsonNode source = request.get("contentSource");
        if (source != null && source.has("retriever")) {
            String retrieverId = textOrNull(source.get("retriever"), "retrieverId");
            if (retrieverId != null && !extra.retrievers.containsKey(retrieverId)) {
                throw notFound(retrieverId, "Retriever");
            }
        }
        String queryText = textOrDefault(request, "queryText", "");
        ArrayNode items = objectMapper.createArrayNode();
        for (Document document : matchingDocuments(extra, queryText)) {
            ObjectNode item = items.addObject();
            item.put("documentId", document.id);
            item.put("documentTitle", document.title == null ? document.id : document.title);
            item.put("content", excerpt(document.body, queryText));
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("relevantContent", items);
        return response;
    }

    public ObjectNode putFeedback(String applicationId, String conversationId, String messageId) {
        requireApplication(currentRegion(), applicationId);
        Conversation conversation = extras(applicationId).conversations.get(conversationId);
        if (conversation == null) {
            throw notFound(conversationId, "Conversation");
        }
        boolean found = conversation.messages.stream().anyMatch(message -> messageId.equals(message.id));
        if (!found) {
            throw notFound(messageId, "Message");
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode getChatControls(String applicationId) {
        requireApplication(currentRegion(), applicationId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("responseScope", extras(applicationId).responseScope);
        return response;
    }

    public ObjectNode updateChatControls(String applicationId, JsonNode request) {
        requireApplication(currentRegion(), applicationId);
        if (request.hasNonNull("responseScope")) {
            extras(applicationId).responseScope = request.get("responseScope").asText();
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode deleteChatControls(String applicationId) {
        requireApplication(currentRegion(), applicationId);
        extras(applicationId).responseScope = "ENTERPRISE_CONTENT_ONLY";
        return objectMapper.createObjectNode();
    }

    public ObjectNode listConversations(String applicationId) {
        requireApplication(currentRegion(), applicationId);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("conversations");
        extras(applicationId).conversations.values().forEach(conversation -> {
            ObjectNode node = list.addObject();
            node.put("conversationId", conversation.id);
        });
        return response;
    }

    public ObjectNode deleteConversation(String applicationId, String conversationId) {
        requireApplication(currentRegion(), applicationId);
        if (extras(applicationId).conversations.remove(conversationId) == null) {
            throw notFound(conversationId, "Conversation");
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listMessages(String applicationId, String conversationId) {
        requireApplication(currentRegion(), applicationId);
        Conversation conversation = extras(applicationId).conversations.get(conversationId);
        if (conversation == null) {
            throw notFound(conversationId, "Conversation");
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("messages");
        for (Message message : conversation.messages) {
            ObjectNode node = list.addObject();
            node.put("messageId", message.id);
            node.put("type", message.type);
            node.put("body", message.body);
        }
        return response;
    }

    public ObjectNode listAttachments(String applicationId) {
        requireApplication(currentRegion(), applicationId);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("attachments");
        extras(applicationId).attachments.values().forEach(attachment -> {
            ObjectNode node = list.addObject();
            node.put("attachmentId", attachment.id);
            node.put("conversationId", attachment.conversationId);
        });
        return response;
    }

    public ObjectNode deleteAttachment(String applicationId, String conversationId, String attachmentId) {
        requireApplication(currentRegion(), applicationId);
        Attachment attachment = extras(applicationId).attachments.get(attachmentId);
        if (attachment == null || !conversationId.equals(attachment.conversationId)) {
            throw notFound(attachmentId, "Attachment");
        }
        extras(applicationId).attachments.remove(attachmentId);
        return objectMapper.createObjectNode();
    }

    public ObjectNode getMedia(String applicationId, String conversationId, String messageId, String mediaId) {
        requireApplication(currentRegion(), applicationId);
        Conversation conversation = extras(applicationId).conversations.get(conversationId);
        if (conversation == null) {
            throw notFound(conversationId, "Conversation");
        }
        boolean found = conversation.messages.stream().anyMatch(message -> messageId.equals(message.id));
        if (!found) {
            throw notFound(messageId, "Message");
        }
        throw notFound(mediaId, "Media");
    }

    public ObjectNode createUser(String applicationId, JsonNode request) {
        requireApplication(currentRegion(), applicationId);
        String userId = requireText(request, "userId");
        User user = new User();
        user.id = userId;
        user.aliases = copy(request.get("userAliases"));
        extras(applicationId).users.put(userId, user);
        return objectMapper.createObjectNode();
    }

    public ObjectNode getUser(String applicationId, String userId) {
        requireApplication(currentRegion(), applicationId);
        User user = extras(applicationId).users.get(userId);
        if (user == null) {
            throw notFound(userId, "User");
        }
        ObjectNode response = objectMapper.createObjectNode();
        if (user.aliases != null) {
            response.set("userAliases", user.aliases);
        } else {
            response.putArray("userAliases");
        }
        return response;
    }

    public ObjectNode updateUser(String applicationId, String userId, JsonNode request) {
        requireApplication(currentRegion(), applicationId);
        User user = extras(applicationId).users.get(userId);
        if (user == null) {
            throw notFound(userId, "User");
        }
        if (request.has("userAliasesToUpdate")) {
            user.aliases = copy(request.get("userAliasesToUpdate"));
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("userAliasesAdded");
        response.putArray("userAliasesUpdated");
        response.putArray("userAliasesDeleted");
        return response;
    }

    public ObjectNode deleteUser(String applicationId, String userId) {
        requireApplication(currentRegion(), applicationId);
        if (extras(applicationId).users.remove(userId) == null) {
            throw notFound(userId, "User");
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode getPolicy(String applicationId) {
        requireApplication(currentRegion(), applicationId);
        ObjectNode response = objectMapper.createObjectNode();
        if (extras(applicationId).policy != null) {
            response.put("policy", extras(applicationId).policy);
        }
        return response;
    }

    public ObjectNode associatePermission(String applicationId, JsonNode request) {
        requireApplication(currentRegion(), applicationId);
        String statementId = requireText(request, "statementId");
        extras(applicationId).permissions.put(statementId, copy(request));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("statement", statementId);
        return response;
    }

    public ObjectNode disassociatePermission(String applicationId, String statementId) {
        requireApplication(currentRegion(), applicationId);
        if (extras(applicationId).permissions.remove(statementId) == null) {
            throw notFound(statementId, "Statement");
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode createSubscription(String applicationId, JsonNode request) {
        Application application = requireApplication(currentRegion(), applicationId);
        JsonNode principal = request.get("principal");
        if (principal == null || principal.isNull()) {
            throw validation("principal is required.");
        }
        Subscription subscription = new Subscription();
        subscription.id = newId();
        subscription.arn = arn(currentRegion(), regionResolver.getAccountId(),
                "application/" + application.getApplicationId() + "/subscription/" + subscription.id);
        subscription.principal = copy(principal);
        subscription.type = requireText(request, "type");
        extras(applicationId).subscriptions.put(subscription.id, subscription);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("subscriptionId", subscription.id);
        response.put("subscriptionArn", subscription.arn);
        return response;
    }

    public ObjectNode updateSubscription(String applicationId, String subscriptionId, JsonNode request) {
        requireApplication(currentRegion(), applicationId);
        Subscription subscription = extras(applicationId).subscriptions.get(subscriptionId);
        if (subscription == null) {
            throw notFound(subscriptionId, "Subscription");
        }
        if (request.hasNonNull("type")) {
            subscription.type = request.get("type").asText();
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("subscriptionArn", subscription.arn);
        return response;
    }

    public ObjectNode cancelSubscription(String applicationId, String subscriptionId) {
        requireApplication(currentRegion(), applicationId);
        Subscription subscription = extras(applicationId).subscriptions.remove(subscriptionId);
        if (subscription == null) {
            throw notFound(subscriptionId, "Subscription");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("subscriptionArn", subscription.arn);
        return response;
    }

    public ObjectNode listSubscriptions(String applicationId) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("subscriptions");
        try {
            requireApplication(currentRegion(), applicationId);
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                return response;
            }
            throw e;
        }
        extras(applicationId).subscriptions.values().forEach(subscription -> {
            ObjectNode node = list.addObject();
            node.put("subscriptionId", subscription.id);
            node.put("subscriptionArn", subscription.arn);
            if (subscription.principal != null) {
                node.set("principal", subscription.principal);
            }
        });
        return response;
    }

    public ObjectNode batchPutDocument(String applicationId, String indexId, JsonNode request) {
        requireIndex(currentRegion(), applicationId, indexId);
        JsonNode documents = request.get("documents");
        if (documents == null || !documents.isArray()) {
            throw validation("documents is required.");
        }
        AppExtras extra = extras(applicationId);
        ArrayNode failed = objectMapper.createArrayNode();
        long now = now();
        for (JsonNode node : documents) {
            String id = textOrNull(node, "id");
            if (id == null) {
                failed.addObject().put("errorMessage", "Document id is required.");
                continue;
            }
            Document document = new Document();
            document.id = id;
            document.title = textOrNull(node, "title");
            document.body = documentBody(node);
            document.status = "INDEXED";
            document.createdAt = now;
            document.updatedAt = now;
            extra.documents.put(indexId + ":" + id, document);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("failedDocuments", failed);
        return response;
    }

    public ObjectNode batchDeleteDocument(String applicationId, String indexId, JsonNode request) {
        requireIndex(currentRegion(), applicationId, indexId);
        JsonNode documents = request.get("documents");
        if (documents == null || !documents.isArray()) {
            throw validation("documents is required.");
        }
        AppExtras extra = extras(applicationId);
        for (JsonNode node : documents) {
            String id = textOrNull(node, "documentId");
            if (id != null) {
                extra.documents.remove(indexId + ":" + id);
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("failedDocuments");
        return response;
    }

    public ObjectNode listDocuments(String applicationId, String indexId) {
        requireIndex(currentRegion(), applicationId, indexId);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("documentDetailList");
        String prefix = indexId + ":";
        extras(applicationId).documents.forEach((key, document) -> {
            if (key.startsWith(prefix)) {
                ObjectNode node = list.addObject();
                node.put("documentId", document.id);
                node.put("status", document.status);
                node.put("createdAt", document.createdAt);
                node.put("updatedAt", document.updatedAt);
            }
        });
        return response;
    }

    public ObjectNode getDocumentContent(String applicationId, String indexId, String documentId) {
        requireIndex(currentRegion(), applicationId, indexId);
        Document document = extras(applicationId).documents.get(indexId + ":" + documentId);
        if (document == null) {
            throw notFound(documentId, "Document");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("presignedUrl", "http://localhost:4566/qbusiness-documents/" + documentId);
        response.put("mimeType", "text/plain");
        return response;
    }

    public ObjectNode checkDocumentAccess(String applicationId, String indexId, String userId, String documentId) {
        requireIndex(currentRegion(), applicationId, indexId);
        Document document = extras(applicationId).documents.get(indexId + ":" + documentId);
        if (document == null) {
            throw notFound(documentId, "Document");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("hasAccess", true);
        return response;
    }

    public ObjectNode putGroup(String applicationId, String indexId, JsonNode request) {
        requireIndex(currentRegion(), applicationId, indexId);
        String groupName = requireText(request, "groupName");
        Group group = extras(applicationId).groups.computeIfAbsent(indexId + ":" + groupName, ignored -> new Group());
        group.name = groupName;
        group.updatedAt = now();
        return objectMapper.createObjectNode();
    }

    public ObjectNode getGroup(String applicationId, String indexId, String groupName) {
        requireIndex(currentRegion(), applicationId, indexId);
        Group group = extras(applicationId).groups.get(indexId + ":" + groupName);
        if (group == null) {
            throw notFound(groupName, "Group");
        }
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode status = response.putObject("status");
        status.put("status", "SUCCEEDED");
        status.put("lastUpdatedAt", group.updatedAt);
        return response;
    }

    public ObjectNode deleteGroup(String applicationId, String indexId, String groupName) {
        requireIndex(currentRegion(), applicationId, indexId);
        if (extras(applicationId).groups.remove(indexId + ":" + groupName) == null) {
            throw notFound(groupName, "Group");
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listGroups(String applicationId, String indexId) {
        requireIndex(currentRegion(), applicationId, indexId);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("items");
        String prefix = indexId + ":";
        extras(applicationId).groups.forEach((key, group) -> {
            if (key.startsWith(prefix)) {
                list.addObject().put("groupName", group.name);
            }
        });
        return response;
    }

    public ObjectNode createDataSource(String applicationId, String indexId, JsonNode request) {
        requireIndex(currentRegion(), applicationId, indexId);
        DataSource source = new DataSource();
        source.id = newId();
        source.displayName = requireText(request, "displayName");
        source.type = textOrDefault(request, "type", "CUSTOM");
        extras(applicationId).dataSources.put(indexId + ":" + source.id, source);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("dataSourceId", source.id);
        return response;
    }

    public ObjectNode startDataSourceSyncJob(String applicationId, String indexId, String dataSourceId) {
        DataSource source = requireDataSource(applicationId, indexId, dataSourceId);
        long now = now();
        SyncJob job = new SyncJob();
        job.executionId = newId();
        job.startTime = now;
        job.endTime = now;
        job.status = "SUCCEEDED";
        source.syncJobs.add(job);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("executionId", job.executionId);
        return response;
    }

    public ObjectNode stopDataSourceSyncJob(String applicationId, String indexId, String dataSourceId) {
        DataSource source = requireDataSource(applicationId, indexId, dataSourceId);
        SyncJob running = null;
        for (int i = source.syncJobs.size() - 1; i >= 0; i--) {
            SyncJob job = source.syncJobs.get(i);
            if ("SYNCING".equals(job.status)) {
                running = job;
                break;
            }
        }
        if (running == null) {
            throw conflict(dataSourceId, "DataSource",
                    "No sync job is currently in progress for data source " + dataSourceId);
        }
        running.status = "SUCCEEDED";
        running.endTime = now();
        return objectMapper.createObjectNode();
    }

    public ObjectNode listDataSourceSyncJobs(String applicationId, String indexId, String dataSourceId) {
        DataSource source = requireDataSource(applicationId, indexId, dataSourceId);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode history = response.putArray("history");
        for (int i = source.syncJobs.size() - 1; i >= 0; i--) {
            SyncJob job = source.syncJobs.get(i);
            ObjectNode node = history.addObject();
            node.put("executionId", job.executionId);
            node.put("startTime", job.startTime);
            if (job.endTime != null) {
                node.put("endTime", job.endTime);
            }
            node.put("status", job.status);
        }
        return response;
    }

    public ObjectNode createRetriever(String applicationId, JsonNode request) {
        requireApplication(currentRegion(), applicationId);
        Retriever retriever = new Retriever();
        retriever.id = newId();
        retriever.displayName = requireText(request, "displayName");
        retriever.type = requireText(request, "type");
        retriever.arn = arn(currentRegion(), regionResolver.getAccountId(),
                "application/" + applicationId + "/retriever/" + retriever.id);
        extras(applicationId).retrievers.put(retriever.id, retriever);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("retrieverId", retriever.id);
        response.put("retrieverArn", retriever.arn);
        return response;
    }

    public ObjectNode createWebExperience(String applicationId, JsonNode request) {
        requireApplication(currentRegion(), applicationId);
        WebExperience experience = new WebExperience();
        experience.id = newId();
        experience.title = textOrDefault(request, "title", "experience");
        experience.defaultEndpoint = "https://localhost:4566/qbusiness/" + applicationId + "/" + experience.id;
        experience.arn = arn(currentRegion(), regionResolver.getAccountId(),
                "application/" + applicationId + "/web-experience/" + experience.id);
        extras(applicationId).experiences.put(experience.id, experience);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("webExperienceId", experience.id);
        response.put("webExperienceArn", experience.arn);
        return response;
    }

    public ObjectNode createAnonymousWebExperienceUrl(String applicationId, String webExperienceId) {
        requireApplication(currentRegion(), applicationId);
        WebExperience experience = extras(applicationId).experiences.get(webExperienceId);
        if (experience == null) {
            throw notFound(webExperienceId, "WebExperience");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("anonymousUrl", experience.defaultEndpoint + "/anonymous");
        return response;
    }

    private DataSource requireDataSource(String applicationId, String indexId, String dataSourceId) {
        requireIndex(currentRegion(), applicationId, indexId);
        DataSource source = extras(applicationId).dataSources.get(indexId + ":" + dataSourceId);
        if (source == null) {
            throw notFound(dataSourceId, "DataSource");
        }
        return source;
    }

    private void applyIdentityCenterOutcome(Application application) {
        if (!DEFAULT_IDENTITY_TYPE.equals(application.getIdentityType())) {
            application.setStatus(STATUS_ACTIVE);
            application.setErrorCode(null);
            application.setErrorMessage(null);
            return;
        }
        application.setStatus(STATUS_FAILED);
        application.setErrorCode("InvalidRequest");
        application.setErrorMessage(
                "Unable to connect to the specified Identity Center instance.");
    }

    private Application findByDisplayName(String region, String displayName) {
        for (Application existing : store.scan(key -> key.startsWith(prefix(region)))) {
            if (displayName.equals(existing.getDisplayName())
                    && !STATUS_DELETING.equals(existing.getStatus())) {
                return existing;
            }
        }
        return null;
    }

    private Application requireApplication(String region, String applicationId) {
        if (applicationId == null || applicationId.isBlank()) {
            throw validation("applicationId is required.");
        }
        return store.get(storageKey(region, applicationId)).orElseThrow(
                () -> notFound(applicationId, "Application"));
    }

    private Index requireIndex(String region, String applicationId, String indexId) {
        Application application = requireApplication(region, applicationId);
        if (indexId == null || indexId.isBlank()) {
            throw validation("indexId is required.");
        }
        Index index = application.getIndexes().get(indexId);
        if (index == null) {
            throw notFound(indexId, "Index");
        }
        return index;
    }

    private AppExtras extras(String applicationId) {
        return extras.computeIfAbsent(applicationId, ignored -> new AppExtras());
    }

    private String currentRegion() {
        return regionResolver.getRegion();
    }

    private String answerFromDocuments(AppExtras extra, String userMessage) {
        List<Document> matches = matchingDocuments(extra, userMessage);
        if (!matches.isEmpty()) {
            return excerpt(matches.get(0).body, userMessage);
        }
        if (userMessage == null || userMessage.isBlank()) {
            return "How can I help?";
        }
        return "I could not find an answer in the indexed content.";
    }

    private List<Document> matchingDocuments(AppExtras extra, String queryText) {
        String needle = queryText == null ? "" : queryText.toLowerCase(Locale.ROOT).trim();
        List<Document> matches = new ArrayList<>();
        if (needle.isEmpty()) {
            return matches;
        }
        for (Document document : extra.documents.values()) {
            String haystack = ((document.title == null ? "" : document.title) + " " + document.body)
                    .toLowerCase(Locale.ROOT);
            if (haystack.contains(needle)) {
                matches.add(document);
            }
        }
        return matches;
    }

    private static String excerpt(String body, String queryText) {
        if (body == null || body.isBlank()) {
            return "";
        }
        if (queryText == null || queryText.isBlank()) {
            return body.length() > 240 ? body.substring(0, 240) : body;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        String needle = queryText.toLowerCase(Locale.ROOT);
        int at = lower.indexOf(needle);
        if (at < 0) {
            return body.length() > 240 ? body.substring(0, 240) : body;
        }
        int start = Math.max(0, at - 40);
        int end = Math.min(body.length(), at + needle.length() + 80);
        return body.substring(start, end);
    }

    private static String documentBody(JsonNode document) {
        JsonNode content = document.get("content");
        if (content == null || content.isNull() || content.isMissingNode()) {
            return "";
        }
        JsonNode blob = content.get("blob");
        if (blob == null || blob.isNull() || blob.isMissingNode()) {
            return "";
        }
        if (blob.isBinary()) {
            try {
                return new String(blob.binaryValue(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return blob.asText("");
            }
        }
        String raw = blob.asText("");
        try {
            return new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return raw;
        }
    }

    private JsonNode copy(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.deepCopy();
    }

    private String arn(String region, String account, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, resource).toString();
    }

    private String storageKey(String region, String id) {
        return regionResolver.getAccountId() + "::" + region + "::" + id;
    }

    private String prefix(String region) {
        return regionResolver.getAccountId() + "::" + region + "::";
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static void requireObject(JsonNode request, String label) {
        if (request == null || !request.isObject()) {
            throw validation(label + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw validation(field + " is required.");
        }
        return value;
    }

    private static String textOrDefault(JsonNode request, String field, String fallback) {
        String value = textOrNull(request, field);
        return value == null ? fallback : value;
    }

    private static String textOrNull(JsonNode request, String field) {
        if (request == null || !request.has(field)) {
            return null;
        }
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual() && !node.isNumber()) {
            if (node.isObject() || node.isArray()) {
                return null;
            }
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static AwsException notFound(String resourceId, String resourceType) {
        return new AwsException(
                "ResourceNotFoundException",
                resourceType + " " + resourceId + " could not be found.",
                404,
                Map.of("resourceId", resourceId, "resourceType", resourceType));
    }

    private static AwsException conflict(String resourceId, String resourceType, String message) {
        return new AwsException(
                "ConflictException",
                message,
                409,
                Map.of("resourceId", resourceId, "resourceType", resourceType));
    }

    private static AwsException validation(String message) {
        return new AwsException(
                "ValidationException",
                message,
                400,
                Map.of("reason", "FIELD_VALIDATION_FAILED"));
    }
}
