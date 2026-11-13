package io.floci.aws.services.qbusiness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.bedrockruntime.BedrockRuntimeService;
import io.floci.aws.services.qbusiness.QBusinessSearch.Hit;
import io.floci.aws.services.qbusiness.QBusinessSearch.Passage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Q Business chat and search. A question is answered from the passages of the application's index the user may
 * read, ranked by BM25, as the sentences that answer it with their sources cited, or by Floci's Bedrock runtime when
 * a model is configured. Chat controls apply as the service applies them: blocked phrases, topic rules, response
 * scope and creator mode. Plugins review an action, then run a custom plugin's OpenAPI operation against its server.
 */
@ApplicationScoped
public class QBusinessChat {

    static final String NO_ANSWER = "Sorry, I could not find relevant information to complete your request.";
    private static final String BLOCKED = "This message is blocked as it contains secure content.";

    private final QBusinessStore store;
    private final QBusinessService service;
    private final ObjectMapper mapper;
    private final Provider<BedrockRuntimeService> bedrock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Inject
    public QBusinessChat(QBusinessStore store, QBusinessService service, Provider<BedrockRuntimeService> bedrock) {
        this.store = store;
        this.service = service;
        this.mapper = store.mapper();
        this.bedrock = bedrock;
    }

    /** An answer: its text and the sources it cites. */
    record Answer(String text, ArrayNode attributions) {
    }

    // ================================================================ retrieval

    /** The application's native index, if its retriever is one. */
    String indexOf(String region, String appId) {
        for (ObjectNode retriever : store.list(region, QBusinessStore.RETRIEVER, appId + "/")) {
            String index = retriever.at("/configuration/nativeIndexConfiguration/indexId").asText("");
            if (!index.isEmpty()) {
                return index;
            }
        }
        List<ObjectNode> indices = store.list(region, QBusinessStore.INDEX, appId + "/");
        return indices.isEmpty() ? null : indices.get(0).get("indexId").asText();
    }

    /** The passages of an index a user may read, passing a filter, from the eligible data sources. */
    List<Passage> passages(String region, String appId, String indexId, String userId, JsonNode filter,
                           Set<String> eligible) {
        List<Passage> passages = new ArrayList<>();
        if (indexId == null) {
            return passages;
        }
        for (ObjectNode document : service.documents(region, appId, indexId)) {
            String status = document.path("status").asText();
            if (!status.equals("INDEXED") && !status.equals("UPDATED")) {
                continue;
            }
            ObjectNode meta = QBusinessStore.meta(document);
            String source = meta.path("dataSourceId").asText(null);
            if (eligible != null && (source == null || !eligible.contains(source))) {
                continue;
            }
            if (!QBusinessSearch.matches(filter, meta.path("attributes"))
                    || !service.readable(region, appId, indexId, document, userId)) {
                continue;
            }
            for (JsonNode text : meta.path("passages")) {
                passages.add(new Passage(document.get("documentId").asText(), meta.path("title").asText(),
                        meta.path("uri").asText(null), text.asText(), meta.path("attributes"), source));
            }
        }
        return passages;
    }

    /** Text from Floci's Bedrock runtime, with the configured model. */
    String generateText(String prompt) {
        ObjectNode request = mapper.createObjectNode();
        request.putArray("messages").addObject().put("role", "user").putArray("content").addObject()
                .put("text", prompt);
        ObjectNode response = bedrock.get().buildConverseResponse(store.config().model(), request);
        StringBuilder text = new StringBuilder();
        response.at("/output/message/content").forEach(block -> text.append(block.path("text").asText("")));
        return text.toString().trim();
    }

    /** Answers a question from ranked passages, citing them. */
    Answer answer(String query, List<Hit> hits, String indexId) {
        ArrayNode attributions = mapper.createArrayNode();
        if (hits.isEmpty()) {
            return new Answer(NO_ANSWER, attributions);
        }
        Map<String, Integer> citations = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder();
        List<String> sentences;
        if (store.config().generateAnswers()) {
            StringBuilder prompt = new StringBuilder("Answer the question using only these sources, citing them as "
                    + "[n].\n\n");
            for (int i = 0; i < hits.size(); i++) {
                prompt.append('[').append(i + 1).append("] ").append(hits.get(i).passage().text()).append("\n\n");
            }
            prompt.append("Question: ").append(query);
            sentences = List.of(generateText(prompt.toString()));
            hits.forEach(hit -> citations.putIfAbsent(hit.passage().documentId(), citations.size() + 1));
        } else {
            sentences = QBusinessSearch.answer(query, hits, 3);
            if (sentences.isEmpty()) {
                return new Answer(NO_ANSWER, attributions);
            }
        }
        for (String sentence : sentences) {
            Hit source = hits.get(0);
            for (Hit hit : hits) {
                if (hit.passage().text().contains(sentence)) {
                    source = hit;
                    break;
                }
            }
            int citation = citations.computeIfAbsent(source.passage().documentId(), id -> citations.size() + 1);
            int begin = text.length();
            text.append(sentence);
            if (!store.config().generateAnswers()) {
                text.append(" [").append(citation).append(']');
            }
            int end = text.length();
            text.append(' ');
            ObjectNode attribution = attribution(attributions, source, citation, indexId);
            ObjectNode segment = ((ArrayNode) attribution.get("textMessageSegments")).addObject()
                    .put("beginOffset", begin).put("endOffset", end);
            segment.putObject("snippetExcerpt").put("text", sentence);
        }
        return new Answer(text.toString().trim(), attributions);
    }

    private ObjectNode attribution(ArrayNode attributions, Hit hit, int citation, String indexId) {
        for (JsonNode existing : attributions) {
            if (existing.path("citationNumber").asInt() == citation) {
                return (ObjectNode) existing;
            }
        }
        Passage passage = hit.passage();
        ObjectNode attribution = attributions.addObject().put("title", passage.title())
                .put("snippet", passage.text().length() > 300 ? passage.text().substring(0, 300) + "..."
                        : passage.text())
                .put("citationNumber", citation).put("documentId", passage.documentId())
                .put("updatedAt", QBusinessStore.now());
        if (indexId != null) {
            attribution.put("indexId", indexId);
        }
        if (passage.uri() != null) {
            attribution.put("url", passage.uri());
        }
        if (passage.dataSourceId() != null) {
            attribution.put("datasourceId", passage.dataSourceId());
        }
        attribution.putArray("textMessageSegments");
        return attribution;
    }

    // ================================================================ chat controls

    private static boolean appliesTo(JsonNode rule, String user, Set<String> groups) {
        JsonNode included = rule.path("includedUsersAndGroups");
        JsonNode excluded = rule.path("excludedUsersAndGroups");
        if (member(excluded, user, groups)) {
            return false;
        }
        return included.isMissingNode() || included.isEmpty() || member(included, user, groups);
    }

    private static boolean member(JsonNode set, String user, Set<String> groups) {
        for (JsonNode id : set.path("userIds")) {
            if (id.asText().equalsIgnoreCase(user == null ? "" : user)) {
                return true;
            }
        }
        for (JsonNode group : set.path("userGroups")) {
            if (groups.contains(group.asText())) {
                return true;
            }
        }
        return false;
    }

    /** Whether a message is about a topic: it names the topic, or shares most terms with an example message. */
    private static boolean onTopic(JsonNode topic, String message) {
        Set<String> words = new HashSet<>(QBusinessSearch.terms(message));
        Set<String> name = new HashSet<>(QBusinessSearch.terms(topic.path("name").asText() + " "
                + topic.path("description").asText("")));
        name.retainAll(words);
        if (!name.isEmpty() && name.size() * 2 >= Math.min(2, QBusinessSearch.terms(topic.path("name").asText())
                .size())) {
            return true;
        }
        for (JsonNode example : topic.path("exampleChatMessages")) {
            Set<String> terms = new HashSet<>(QBusinessSearch.terms(example.asText()));
            int size = terms.size();
            terms.retainAll(words);
            if (size > 0 && terms.size() * 2 >= size) {
                return true;
            }
        }
        return false;
    }

    // ================================================================ conversations

    private ObjectNode conversation(String region, String appId, String id, String user) {
        ObjectNode conversation = store.get(region, QBusinessStore.CONVERSATION, appId + "/" + id)
                .orElseThrow(() -> QBusinessStore.notFound("conversation", id));
        if (!owner(user).equals(conversation.path("user").asText())) {
            throw QBusinessStore.notFound("conversation", id);
        }
        return conversation;
    }

    private static String owner(String user) {
        return user == null ? "" : user;
    }

    private ObjectNode message(String region, String appId, String conversationId, String type, String body,
                               ArrayNode attributions, ArrayNode attachments, JsonNode review, JsonNode execution) {
        String id = UUID.randomUUID().toString();
        ObjectNode record = store.record();
        record.put("messageId", id).put("body", body).put("time", QBusinessStore.now()).put("type", type);
        if (attributions != null) {
            record.set("sourceAttribution", attributions);
        }
        if (attachments != null && !attachments.isEmpty()) {
            record.set("attachments", attachments);
        }
        if (review != null) {
            record.set("actionReview", review);
        }
        if (execution != null) {
            record.set("actionExecution", execution);
        }
        store.put(region, QBusinessStore.MESSAGE, appId + "/" + conversationId + "/"
                + String.format("%019d", System.nanoTime()) + "-" + id, record);
        return record;
    }

    private void checkLicense(String region, ObjectNode app, String user) {
        if (!"AWS_IAM_IDC".equals(app.path("identityType").asText()) || user == null
                || "ENABLED".equals(app.at("/autoSubscriptionConfiguration/autoSubscribe").asText())) {
            return;
        }
        for (ObjectNode subscription : store.list(region, QBusinessStore.SUBSCRIPTION, QBusinessStore.id(app) + "/")) {
            if (user.equals(subscription.at("/principal/user").asText())
                    || !subscription.at("/principal/group").asText("").isEmpty()) {
                return;
            }
        }
        throw new AwsException("LicenseNotFoundException", "The user " + user + " has no Q Business subscription.",
                400);
    }

    // ================================================================ ChatSync

    /** A chat turn, shared by ChatSync and Chat. */
    public ObjectNode chatSync(JsonNode in, String region) {
        String appId = QBusinessService.req(in, "applicationId");
        ObjectNode app = service.activeApplication(region, appId);
        String user = QBusinessService.opt(in, "userId");
        checkLicense(region, app, user);
        ObjectNode controls = service.controls(region, appId);
        String indexId = indexOf(region, appId);
        Set<String> groups = new HashSet<>();
        in.path("userGroups").forEach(g -> groups.add(g.asText()));
        if (indexId != null) {
            groups.addAll(service.groups(region, appId, indexId, service.identities(region, appId, indexId, user)));
        }
        String text = in.path("userMessage").asText("");
        JsonNode execution = in.has("actionExecution") ? in.get("actionExecution") : null;
        if (text.isBlank() && execution == null && !in.path("attachments").isArray()
                && !in.has("authChallengeResponse")) {
            throw QBusinessStore.validation("A chat turn needs a userMessage, attachments or an actionExecution.");
        }
        ObjectNode conversation;
        String conversationId = QBusinessService.opt(in, "conversationId");
        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
            conversation = store.record();
            conversation.put("conversationId", conversationId).put("title", text.isBlank() ? "New conversation"
                    : text.length() > 50 ? text.substring(0, 50) : text).put("startTime", QBusinessStore.now())
                    .put("user", owner(user));
            store.put(region, QBusinessStore.CONVERSATION, appId + "/" + conversationId, conversation);
        } else {
            conversation = conversation(region, appId, conversationId, user);
            String parent = QBusinessService.opt(in, "parentMessageId");
            if (parent == null) {
                throw QBusinessStore.validation("Continuing a conversation needs its parentMessageId.");
            }
            if (store.list(region, QBusinessStore.MESSAGE, appId + "/" + conversationId + "/").stream()
                    .noneMatch(m -> parent.equals(m.path("messageId").asText()))) {
                throw QBusinessStore.notFound("message", parent);
            }
        }
        ArrayNode failed = mapper.createArrayNode();
        ArrayNode attached = attachments(region, app, conversationId, in.path("attachments"), failed);
        ObjectNode userMessage = message(region, appId, conversationId, "USER", text, null, attached, null, execution);
        ObjectNode out = mapper.createObjectNode().put("conversationId", conversationId)
                .put("userMessageId", userMessage.get("messageId").asText());
        if (!failed.isEmpty()) {
            out.set("failedAttachments", failed);
        }
        String mode = QBusinessService.opt(in, "chatMode");
        if (mode == null) {
            mode = in.at("/chatModeConfiguration/pluginConfiguration/pluginId").isMissingNode() ? "RETRIEVAL_MODE"
                    : "PLUGIN_MODE";
        }
        Answer answer;
        JsonNode review = null;
        String blocked = blockedBy(controls, text, user, groups);
        Set<String> eligible = eligible(controls, text, user, groups);
        if (blocked != null) {
            answer = new Answer(blocked, mapper.createArrayNode());
        } else if ("PLUGIN_MODE".equals(mode) || execution != null) {
            String pluginId = execution != null ? execution.path("pluginId").asText()
                    : in.at("/chatModeConfiguration/pluginConfiguration/pluginId").asText("");
            if (pluginId.isEmpty()) {
                throw QBusinessStore.validation("PLUGIN_MODE needs chatModeConfiguration.pluginConfiguration.pluginId.");
            }
            ObjectNode plugin = service.plugin(region, appId, pluginId);
            if (!"READY".equals(plugin.path("buildStatus").asText()) || !"ENABLED".equals(plugin.path("state")
                    .asText())) {
                throw QBusinessStore.conflict("plugin", pluginId, "The plugin isn't READY and ENABLED.");
            }
            if (execution != null) {
                answer = new Answer(execute(plugin, execution), mapper.createArrayNode());
            } else {
                review = review(plugin, text);
                answer = new Answer("I can run " + review.path("pluginType").asText() + " action for you. Review "
                        + "the details, then confirm.", mapper.createArrayNode());
            }
        } else if ("CREATOR_MODE".equals(mode)) {
            if (!"ENABLED".equals(controls.at("/creatorModeConfiguration/creatorModeControl").asText())) {
                throw QBusinessStore.validation("Creator mode is disabled for the application.");
            }
            answer = new Answer(generateText(context(region, appId, conversationId) + text), mapper.createArrayNode());
        } else {
            List<Passage> passages = passages(region, appId, indexId, user, in.path("attributeFilter"), eligible);
            passages.addAll(attachmentPassages(region, appId, conversationId));
            List<Hit> hits = QBusinessSearch.rank(text, passages, 5);
            answer = answer(text, hits, indexId);
            if (answer.text().equals(NO_ANSWER) && "EXTENDED_KNOWLEDGE_ENABLED".equals(controls
                    .path("responseScope").asText())) {
                answer = new Answer(generateText(text), mapper.createArrayNode());
            }
        }
        ObjectNode system = message(region, appId, conversationId, "SYSTEM", answer.text(), answer.attributions(),
                null, review, null);
        out.put("systemMessage", answer.text()).put("systemMessageId", system.get("messageId").asText());
        out.set("sourceAttributions", answer.attributions());
        if (review != null) {
            out.set("actionReview", review);
        }
        return out;
    }

    private String blockedBy(ObjectNode controls, String text, String user, Set<String> groups) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (JsonNode phrase : controls.at("/blockedPhrases/blockedPhrases")) {
            if (lower.contains(phrase.asText().toLowerCase(Locale.ROOT))) {
                return controls.at("/blockedPhrases/systemMessageOverride").asText(BLOCKED);
            }
        }
        for (JsonNode topic : controls.path("topicConfigurations")) {
            if (!onTopic(topic, text)) {
                continue;
            }
            for (JsonNode rule : topic.path("rules")) {
                if ("CONTENT_BLOCKER_RULE".equals(rule.path("ruleType").asText()) && appliesTo(rule, user, groups)) {
                    return rule.at("/ruleConfiguration/contentBlockerRule/systemMessageOverride").asText(BLOCKED);
                }
            }
        }
        return null;
    }

    private Set<String> eligible(ObjectNode controls, String text, String user, Set<String> groups) {
        for (JsonNode topic : controls.path("topicConfigurations")) {
            if (!onTopic(topic, text)) {
                continue;
            }
            for (JsonNode rule : topic.path("rules")) {
                if ("CONTENT_RETRIEVAL_RULE".equals(rule.path("ruleType").asText()) && appliesTo(rule, user, groups)) {
                    Set<String> sources = new HashSet<>();
                    rule.at("/ruleConfiguration/contentRetrievalRule/eligibleDataSources").forEach(source ->
                            sources.add(source.path("dataSourceId").asText()));
                    return sources;
                }
            }
        }
        return null;
    }

    private String context(String region, String appId, String conversationId) {
        StringBuilder context = new StringBuilder();
        for (ObjectNode message : store.list(region, QBusinessStore.MESSAGE, appId + "/" + conversationId + "/")) {
            context.append(message.path("type").asText()).append(": ").append(message.path("body").asText())
                    .append('\n');
        }
        return context.toString();
    }

    // ================================================================ attachments

    private ArrayNode attachments(String region, ObjectNode app, String conversationId, JsonNode inputs,
                                  ArrayNode failed) {
        ArrayNode attached = mapper.createArrayNode();
        if (!inputs.isArray() || inputs.isEmpty()) {
            return attached;
        }
        String appId = QBusinessStore.id(app);
        boolean enabled = "ENABLED".equals(app.at("/attachmentsConfiguration/attachmentsControlMode").asText());
        for (JsonNode input : inputs) {
            String id = UUID.randomUUID().toString();
            ObjectNode output = mapper.createObjectNode().put("name", input.path("name").asText("attachment"))
                    .put("attachmentId", id).put("conversationId", conversationId);
            byte[] data;
            String name = input.path("name").asText("attachment");
            if (input.has("copyFrom")) {
                String sourceConversation = input.at("/copyFrom/conversation/conversationId").asText();
                String sourceId = input.at("/copyFrom/conversation/attachmentId").asText();
                ObjectNode source = store.get(region, QBusinessStore.ATTACHMENT, appId + "/" + sourceConversation
                        + "/" + sourceId).orElse(null);
                data = source == null ? null : Base64.getDecoder().decode(QBusinessStore.meta(source).path("data")
                        .asText());
                name = source == null ? name : source.path("name").asText();
                output.put("name", name);
            } else {
                try {
                    data = Base64.getDecoder().decode(input.path("data").asText(""));
                } catch (IllegalArgumentException e) {
                    data = null;
                }
            }
            String error = !enabled ? "Attachments are disabled for the application."
                    : data == null ? "The attachment can't be read." : data.length > 10 * 1024 * 1024
                    ? "The attachment is larger than 10 MB." : null;
            if (error != null) {
                output.put("status", "FAILED").putObject("error").put("errorCode", "InvalidRequest")
                        .put("errorMessage", error);
                failed.add(output);
                continue;
            }
            String type = QBusinessService.typeOf(name);
            ObjectNode record = store.record();
            record.put("attachmentId", id).put("conversationId", conversationId).put("name", name)
                    .put("fileType", type == null ? "PLAIN_TEXT" : type).put("fileSize", data.length)
                    .put("md5chksum", md5(data)).put("createdAt", QBusinessStore.now()).put("status", "SUCCESS");
            if (input.has("copyFrom")) {
                record.set("copyFrom", input.get("copyFrom").deepCopy());
            }
            QBusinessStore.meta(record).put("data", Base64.getEncoder().encodeToString(data));
            try {
                ArrayNode passages = QBusinessStore.meta(record).putArray("passages");
                QBusinessSearch.passages(QBusinessSearch.text(data, type)).forEach(passages::add);
            } catch (AwsException e) {
                QBusinessStore.meta(record).putArray("passages");
            }
            store.put(region, QBusinessStore.ATTACHMENT, appId + "/" + conversationId + "/" + id, record);
            output.put("status", "SUCCESS");
            attached.add(output);
        }
        return attached;
    }

    private List<Passage> attachmentPassages(String region, String appId, String conversationId) {
        List<Passage> passages = new ArrayList<>();
        for (ObjectNode attachment : store.list(region, QBusinessStore.ATTACHMENT, appId + "/" + conversationId + "/")) {
            for (JsonNode text : QBusinessStore.meta(attachment).path("passages")) {
                passages.add(new Passage(attachment.get("attachmentId").asText(), attachment.get("name").asText(),
                        null, text.asText(), mapper.createArrayNode(), null));
            }
        }
        return passages;
    }

    private static String md5(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public ObjectNode listAttachments(JsonNode in, String region) {
        String appId = QBusinessService.req(in, "applicationId");
        service.application(region, appId);
        String conversationId = QBusinessService.opt(in, "conversationId");
        String user = QBusinessService.opt(in, "userId");
        List<ObjectNode> views = new ArrayList<>();
        if (conversationId != null) {
            conversation(region, appId, conversationId, user);
        }
        for (ObjectNode attachment : store.list(region, QBusinessStore.ATTACHMENT, appId + "/"
                + (conversationId == null ? "" : conversationId + "/"))) {
            ObjectNode conversation = store.get(region, QBusinessStore.CONVERSATION, appId + "/"
                    + attachment.path("conversationId").asText()).orElse(null);
            if (conversation != null && owner(user).equals(conversation.path("user").asText())) {
                views.add(service.copy(attachment, "attachmentId", "conversationId", "name", "copyFrom", "fileType",
                        "fileSize", "md5chksum", "createdAt", "status", "error"));
            }
        }
        return service.page(in, views, "attachments");
    }

    public ObjectNode deleteAttachment(JsonNode in, String region) {
        String appId = QBusinessService.req(in, "applicationId");
        service.application(region, appId);
        String conversationId = QBusinessService.req(in, "conversationId");
        conversation(region, appId, conversationId, QBusinessService.opt(in, "userId"));
        String id = QBusinessService.req(in, "attachmentId");
        String key = appId + "/" + conversationId + "/" + id;
        if (store.get(region, QBusinessStore.ATTACHMENT, key).isEmpty()) {
            throw QBusinessStore.notFound("attachment", id);
        }
        store.delete(region, QBusinessStore.ATTACHMENT, key);
        return mapper.createObjectNode();
    }

    public ObjectNode getMedia(JsonNode in, String region) {
        String appId = QBusinessService.req(in, "applicationId");
        service.application(region, appId);
        String conversationId = QBusinessService.req(in, "conversationId");
        store.get(region, QBusinessStore.CONVERSATION, appId + "/" + conversationId)
                .orElseThrow(() -> QBusinessStore.notFound("conversation", conversationId));
        String messageId = QBusinessService.req(in, "messageId");
        ObjectNode message = store.list(region, QBusinessStore.MESSAGE, appId + "/" + conversationId + "/").stream()
                .filter(m -> messageId.equals(m.path("messageId").asText())).findFirst()
                .orElseThrow(() -> QBusinessStore.notFound("message", messageId));
        String mediaId = QBusinessService.req(in, "mediaId");
        for (JsonNode attribution : message.path("sourceAttribution")) {
            for (JsonNode segment : attribution.path("textMessageSegments")) {
                if (mediaId.equals(segment.path("mediaId").asText())) {
                    ObjectNode attachment = store.get(region, QBusinessStore.ATTACHMENT, appId + "/" + conversationId
                            + "/" + mediaId).orElseThrow(() -> QBusinessStore.notFound("media", mediaId));
                    return mapper.createObjectNode().put("mediaBytes", QBusinessStore.meta(attachment).path("data")
                            .asText()).put("mediaMimeType", segment.path("mediaMimeType").asText());
                }
            }
        }
        throw QBusinessStore.notFound("media", mediaId);
    }

    // ================================================================ conversations

    public ObjectNode listConversations(JsonNode in, String region) {
        String appId = QBusinessService.req(in, "applicationId");
        service.application(region, appId);
        String user = owner(QBusinessService.opt(in, "userId"));
        List<ObjectNode> views = new ArrayList<>();
        for (ObjectNode conversation : store.list(region, QBusinessStore.CONVERSATION, appId + "/")) {
            if (user.equals(conversation.path("user").asText())) {
                views.add(service.copy(conversation, "conversationId", "title", "startTime"));
            }
        }
        Collections.reverse(views);
        return service.page(in, views, "conversations");
    }

    public ObjectNode deleteConversation(JsonNode in, String region) {
        String appId = QBusinessService.req(in, "applicationId");
        service.application(region, appId);
        String id = QBusinessService.req(in, "conversationId");
        conversation(region, appId, id, QBusinessService.opt(in, "userId"));
        for (String kind : List.of(QBusinessStore.MESSAGE, QBusinessStore.ATTACHMENT)) {
            for (ObjectNode child : store.list(region, kind, appId + "/" + id + "/")) {
                store.delete(region, kind, QBusinessStore.id(child));
            }
        }
        store.delete(region, QBusinessStore.CONVERSATION, appId + "/" + id);
        return mapper.createObjectNode();
    }

    public ObjectNode listMessages(JsonNode in, String region) {
        String appId = QBusinessService.req(in, "applicationId");
        service.application(region, appId);
        String id = QBusinessService.req(in, "conversationId");
        conversation(region, appId, id, QBusinessService.opt(in, "userId"));
        List<ObjectNode> views = new ArrayList<>();
        for (ObjectNode message : store.list(region, QBusinessStore.MESSAGE, appId + "/" + id + "/")) {
            views.add(service.copy(message, "messageId", "body", "time", "type", "attachments", "sourceAttribution",
                    "actionReview", "actionExecution"));
        }
        Collections.reverse(views);
        return service.page(in, views, "messages");
    }

    public ObjectNode putFeedback(JsonNode in, String region) {
        String appId = QBusinessService.req(in, "applicationId");
        service.application(region, appId);
        String conversationId = QBusinessService.req(in, "conversationId");
        conversation(region, appId, conversationId, QBusinessService.opt(in, "userId"));
        String messageId = QBusinessService.req(in, "messageId");
        for (ObjectNode message : store.list(region, QBusinessStore.MESSAGE, appId + "/" + conversationId + "/")) {
            if (messageId.equals(message.path("messageId").asText())) {
                if (!"SYSTEM".equals(message.path("type").asText())) {
                    throw QBusinessStore.validation("Feedback is for system messages.");
                }
                ObjectNode meta = QBusinessStore.meta(message);
                if (in.has("messageUsefulness")) {
                    meta.set("usefulness", in.get("messageUsefulness").deepCopy());
                }
                if (in.has("messageCopiedAt")) {
                    meta.set("copiedAt", in.get("messageCopiedAt"));
                }
                service.save(message);
                return mapper.createObjectNode();
            }
        }
        throw QBusinessStore.notFound("message", messageId);
    }

    // ================================================================ search

    public ObjectNode searchRelevantContent(JsonNode in, String region) {
        String appId = QBusinessService.req(in, "applicationId");
        service.activeApplication(region, appId);
        String retrieverId = in.at("/contentSource/retriever/retrieverId").asText("");
        if (retrieverId.isEmpty()) {
            throw QBusinessStore.validation("contentSource.retriever.retrieverId is required.");
        }
        ObjectNode retriever = service.retriever(region, appId, retrieverId);
        String indexId = retriever.at("/configuration/nativeIndexConfiguration/indexId").asText(null);
        String query = QBusinessService.req(in, "queryText");
        List<Passage> passages = passages(region, appId, indexId, QBusinessService.opt(in, "userId"),
                in.path("attributeFilter"), null);
        List<Hit> hits = QBusinessSearch.rank(query, passages, 100);
        List<ObjectNode> views = new ArrayList<>();
        for (Hit hit : hits) {
            ObjectNode view = mapper.createObjectNode().put("content", hit.passage().text())
                    .put("documentId", hit.passage().documentId()).put("documentTitle", hit.passage().title());
            if (hit.passage().uri() != null) {
                view.put("documentUri", hit.passage().uri());
            }
            view.set("documentAttributes", hit.passage().attributes());
            view.putObject("scoreAttributes").put("scoreConfidence", QBusinessSearch.confidence(hit, query));
            views.add(view);
        }
        return service.page(in, views, "relevantContent");
    }

    // ================================================================ plugins

    /** The OpenAPI operation that best fits a request, by the terms it shares with the operation's description. */
    private Map.Entry<String, JsonNode> operation(ObjectNode plugin, String request) {
        JsonNode document = service.openApi(plugin.get("customPluginConfiguration"));
        Set<String> wanted = new HashSet<>(QBusinessSearch.terms(request));
        Map.Entry<String, JsonNode> best = null;
        int bestScore = -1;
        for (Map.Entry<String, JsonNode> path : document.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> method : path.getValue().properties()) {
                JsonNode operation = method.getValue();
                Set<String> terms = new HashSet<>(QBusinessSearch.terms(operation.path("operationId").asText("")
                        .replaceAll("([a-z])([A-Z])", "$1 $2") + " " + operation.path("summary").asText("") + " "
                        + operation.path("description").asText("") + " " + path.getKey()));
                terms.retainAll(wanted);
                if (terms.size() > bestScore) {
                    bestScore = terms.size();
                    ObjectNode chosen = operation.deepCopy();
                    ((ObjectNode) chosen).put("_method", method.getKey().toUpperCase(Locale.ROOT))
                            .put("_path", path.getKey());
                    best = Map.entry(operation.path("operationId").asText(method.getKey() + " " + path.getKey()),
                            chosen);
                }
            }
        }
        return best;
    }

    private ObjectNode review(ObjectNode plugin, String request) {
        ObjectNode review = mapper.createObjectNode().put("pluginId", plugin.get("pluginId").asText())
                .put("pluginType", plugin.get("type").asText()).put("payloadFieldNameSeparator", ".");
        ObjectNode payload = review.putObject("payload");
        if ("CUSTOM".equals(plugin.path("type").asText())) {
            Map.Entry<String, JsonNode> operation = operation(plugin, request);
            if (operation == null) {
                throw QBusinessStore.validation("The plugin's API has no operations.");
            }
            int order = 0;
            for (JsonNode parameter : operation.getValue().path("parameters")) {
                payload.putObject(parameter.path("name").asText()).put("displayName", parameter.path("name").asText())
                        .put("displayOrder", order++).put("displayDescription", parameter.path("description")
                                .asText("")).put("type", type(parameter.path("schema"))).put("required",
                                parameter.path("required").asBoolean(false));
            }
            JsonNode body = operation.getValue().at("/requestBody/content/application~1json/schema");
            Set<String> required = new HashSet<>();
            body.path("required").forEach(r -> required.add(r.asText()));
            for (Map.Entry<String, JsonNode> property : body.path("properties").properties()) {
                payload.putObject(property.getKey()).put("displayName", property.getKey()).put("displayOrder", order++)
                        .put("displayDescription", property.getValue().path("description").asText(""))
                        .put("type", type(property.getValue())).put("required", required.contains(property.getKey()));
            }
            payload.putObject("_operation").put("displayName", "Operation").put("type", "STRING")
                    .put("required", true).put("displayOrder", order).put("displayDescription", operation.getKey());
        } else {
            String[] action = QBusinessService.typeActions(plugin.get("type").asText()).get(0);
            payload.putObject("_operation").put("displayName", action[1]).put("type", "STRING").put("required", true)
                    .put("displayDescription", action[0]);
            payload.putObject("summary").put("displayName", "Summary").put("type", "STRING").put("required", true)
                    .put("displayOrder", 1);
        }
        return review;
    }

    private static String type(JsonNode schema) {
        return switch (schema.path("type").asText("string")) {
            case "integer", "number" -> "NUMBER";
            case "boolean" -> "BOOLEAN";
            case "array" -> "ARRAY";
            default -> "STRING";
        };
    }

    /** Runs a reviewed action: a custom plugin's operation is called on its server, with the payload. */
    private String execute(ObjectNode plugin, JsonNode execution) {
        if (!"CUSTOM".equals(plugin.path("type").asText())) {
            throw new AwsException("ExternalResourceException", "Floci can't reach the "
                    + plugin.path("type").asText() + " service the plugin acts on.", 424);
        }
        String server = plugin.path("serverUrl").asText("");
        JsonNode document = service.openApi(plugin.get("customPluginConfiguration"));
        if (server.isEmpty()) {
            server = document.at("/servers/0/url").asText("");
        }
        if (server.isEmpty()) {
            throw QBusinessStore.validation("The plugin has no serverUrl.");
        }
        Map<String, String> values = new LinkedHashMap<>();
        execution.path("payload").properties().forEach(field -> values.put(field.getKey(),
                field.getValue().path("value").isTextual() ? field.getValue().path("value").asText()
                        : field.getValue().path("value").toString()));
        String chosen = values.remove("_operation");
        Map.Entry<String, JsonNode> operation = null;
        for (Map.Entry<String, JsonNode> path : document.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> method : path.getValue().properties()) {
                String id = method.getValue().path("operationId").asText(method.getKey() + " " + path.getKey());
                if (id.equals(chosen) || operation == null) {
                    ObjectNode copy = method.getValue().deepCopy();
                    copy.put("_method", method.getKey().toUpperCase(Locale.ROOT)).put("_path", path.getKey());
                    operation = Map.entry(id, copy);
                    if (id.equals(chosen)) {
                        break;
                    }
                }
            }
        }
        if (operation == null) {
            throw QBusinessStore.validation("The plugin's API has no operations.");
        }
        String path = operation.getValue().path("_path").asText();
        StringBuilder query = new StringBuilder();
        for (JsonNode parameter : operation.getValue().path("parameters")) {
            String name = parameter.path("name").asText();
            String value = values.remove(name);
            if (value == null) {
                if (parameter.path("required").asBoolean()) {
                    throw QBusinessStore.validation("The action needs " + name + ".");
                }
                continue;
            }
            if ("path".equals(parameter.path("in").asText())) {
                path = path.replace("{" + name + "}", URLEncoder.encode(value, StandardCharsets.UTF_8));
            } else if ("query".equals(parameter.path("in").asText())) {
                query.append(query.length() == 0 ? '?' : '&').append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                        .append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        }
        ObjectNode body = mapper.createObjectNode();
        values.forEach(body::put);
        String method = operation.getValue().path("_method").asText();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(server.replaceAll("/+$", "") + path + query))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
        request.method(method, method.equals("GET") || method.equals("DELETE") ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString()));
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            String text = response.body() == null ? "" : response.body();
            if (response.statusCode() >= 400) {
                throw new AwsException("ExternalResourceException", "The plugin's server answered "
                        + response.statusCode() + ": " + truncate(text), 424);
            }
            return "Done: " + operation.getKey() + " returned " + response.statusCode()
                    + (text.isBlank() ? "." : ": " + truncate(text));
        } catch (IOException e) {
            throw new AwsException("ExternalResourceException", "The plugin's server can't be reached: "
                    + e.getMessage(), 424);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AwsException("ExternalResourceException", "The action was interrupted.", 424);
        }
    }

    private static String truncate(String text) {
        return text.length() > 1000 ? text.substring(0, 1000) + "..." : text;
    }
}
