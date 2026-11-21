package io.floci.aws.services.lexv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.CsvParser;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The Test Workbench: test sets imported from the CSV test set format in S3 or generated from a bot's conversations,
 * executions that replay each conversation against a bot alias and compare intents, slots and agent prompts with what
 * the test set expects, the results at each level, an artifacts CSV, and discrepancy reports that check a test set's
 * intents and slots against a bot locale.
 */
@ApplicationScoped
public class LexTesting {

    private final LexStore store;
    private final LexBots bots;
    private final LexRuntime runtime;
    private final Instance<S3Service> s3;
    private final ObjectMapper mapper;

    @Inject
    public LexTesting(LexStore store, LexBots bots, LexRuntime runtime, Instance<S3Service> s3) {
        this.store = store;
        this.bots = bots;
        this.runtime = runtime;
        this.s3 = s3;
        this.mapper = store.mapper();
    }

    private static AwsException failure(String message) {
        return new AwsException("ImportFailure", message, 400);
    }

    // ================================================================ test sets

    /** Imports a test set from its CSV in S3; its identifier. */
    String importTestSet(String region, JsonNode spec) {
        String name = spec.path("testSetName").asText();
        LexStore.checkName("test set", name);
        LexStore.checkTags(spec.get("testSetTags"));
        JsonNode input = spec.path("importInputLocation");
        String csv;
        try {
            csv = new String(s3.get().getObject(input.path("s3BucketName").asText(), input.path("s3Path").asText())
                    .getData(), StandardCharsets.UTF_8);
        } catch (AwsException e) {
            throw failure("The test set file s3://" + input.path("s3BucketName").asText() + "/"
                    + input.path("s3Path").asText() + " can't be read: " + e.getMessage());
        }
        ArrayNode records = parse(csv);
        String testSetId = LexStore.id();
        ObjectNode set = store.record();
        set.put("testSetId", testSetId);
        set.put("testSetName", name);
        if (spec.hasNonNull("description")) {
            set.put("description", spec.get("description").asText());
        }
        set.put("modality", spec.path("modality").asText("Text"));
        set.put("roleArn", spec.path("roleArn").asText());
        set.put("numTurns", records.size());
        set.set("storageLocation", spec.path("storageLocation").deepCopy());
        double now = LexStore.epoch();
        set.put("creationDateTime", now);
        set.put("lastUpdatedDateTime", now);
        store.settle(set, "status", "Importing", "Ready");
        LexStore.meta(set).set("records", records);
        if (spec.get("testSetTags") instanceof ObjectNode tags) {
            LexStore.tags(set).setAll(tags);
        }
        store.put(region, LexStore.TEST_SET, testSetId, set);
        persist(region, set);
        return testSetId;
    }

    /** Writes a test set's CSV to its storage location. */
    private void persist(String region, ObjectNode set) {
        JsonNode location = set.path("storageLocation");
        String path = location.path("s3Path").asText("");
        String key = (path.isEmpty() || path.endsWith("/") ? path : path + "/") + set.path("testSetId").asText()
                + "/TestSet.csv";
        try {
            s3.get().putObject(location.path("s3BucketName").asText(), key,
                    csv(set).getBytes(StandardCharsets.UTF_8), "text/csv", Map.of());
        } catch (AwsException e) {
            throw failure("The test set can't be stored at s3://" + location.path("s3BucketName").asText() + "/"
                    + key + ": " + e.getMessage());
        }
    }

    /** The records of a test set CSV. */
    private ArrayNode parse(String csv) {
        List<List<String>> rows = CsvParser.parseAll(csv.replace("\r\n", "\n"));
        if (rows.isEmpty()) {
            throw failure("The test set file is empty.");
        }
        List<String> header = rows.get(0);
        int line = index(header, "Line #");
        int conversation = index(header, "Conversation #");
        int source = index(header, "Source");
        int input = index(header, "Input");
        int intent = index(header, "Expected Output Intent");
        if (source < 0 || input < 0) {
            throw failure("The test set file needs Source and Input columns.");
        }
        ArrayNode records = mapper.createArrayNode();
        Map<String, Integer> turns = new LinkedHashMap<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.stream().allMatch(String::isBlank)) {
                continue;
            }
            ObjectNode record = records.addObject();
            long number = line >= 0 && !cell(row, line).isBlank() ? Long.parseLong(cell(row, line).trim())
                    : records.size();
            record.put("recordNumber", number);
            String conversationId = conversation >= 0 ? cell(row, conversation).trim() : "";
            if (!conversationId.isEmpty()) {
                record.put("conversationId", conversationId);
                int turn = turns.merge(conversationId, 1, Integer::sum) - 1;
                record.put("turnNumber", turn);
            }
            ObjectNode turn = record.putObject("turnSpecification");
            String text = cell(row, input);
            if ("Agent".equalsIgnoreCase(cell(row, source).trim())) {
                turn.putObject("agentTurn").put("agentPrompt", text);
                continue;
            }
            if (!"User".equalsIgnoreCase(cell(row, source).trim())) {
                throw failure("Line " + number + " has the source " + cell(row, source) + "; use User or Agent.");
            }
            ObjectNode user = turn.putObject("userTurn");
            ObjectNode in = user.putObject("input");
            if (text.startsWith("s3://")) {
                in.putObject("utteranceInput").putObject("audioInput").put("audioFileS3Location", text);
            } else {
                in.putObject("utteranceInput").put("textInput", text);
            }
            ObjectNode expected = user.putObject("expected");
            ObjectNode expectedIntent = expected.putObject("intent");
            expectedIntent.put("name", intent >= 0 ? cell(row, intent).trim() : "");
            ObjectNode slots = expectedIntent.putObject("slots");
            for (int c = 0; c < header.size(); c++) {
                String column = header.get(c).trim();
                String value = cell(row, c).trim();
                if (value.isEmpty()) {
                    continue;
                }
                String[] pair = value.split("\\s*=\\s*", 2);
                if (column.startsWith("Expected Output Slot") && pair.length == 2) {
                    slots.putObject(pair[0]).put("value", pair[1]);
                } else if (column.startsWith("Request Attribute") && pair.length == 2) {
                    in.withObject("/requestAttributes").put(pair[0], pair[1]);
                } else if (column.startsWith("Session Attribute") && pair.length == 2) {
                    in.withObject("/sessionState").withObject("/sessionAttributes").put(pair[0], pair[1]);
                } else if (column.startsWith("Input Context Tag")) {
                    in.withObject("/sessionState").withArray("activeContexts").addObject().put("name", value);
                } else if (column.startsWith("Expected Output Context Tag")) {
                    expected.withArray("activeContexts").addObject().put("name", value);
                } else if (column.startsWith("Expected Transcription") || column.startsWith("Expected Output "
                        + "Transcript")) {
                    expected.put("transcript", value);
                }
            }
        }
        if (records.isEmpty()) {
            throw failure("The test set file has no records.");
        }
        return records;
    }

    private static int index(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) {
            if (name.equalsIgnoreCase(header.get(i).trim())) {
                return i;
            }
        }
        return -1;
    }

    private static String cell(List<String> row, int index) {
        return index >= 0 && index < row.size() && row.get(index) != null ? row.get(index) : "";
    }

    /** A test set as the CSV test set format. */
    String csv(String region, String testSetId) {
        return csv(testSet(region, testSetId));
    }

    private String csv(ObjectNode set) {
        int slotColumns = 1;
        int requestColumns = 1;
        int sessionColumns = 1;
        int inputContexts = 1;
        int outputContexts = 1;
        for (JsonNode record : set.path("_").path("records")) {
            JsonNode user = record.path("turnSpecification").path("userTurn");
            slotColumns = Math.max(slotColumns, user.path("expected").path("intent").path("slots").size());
            requestColumns = Math.max(requestColumns, user.path("input").path("requestAttributes").size());
            sessionColumns = Math.max(sessionColumns, user.path("input").path("sessionState")
                    .path("sessionAttributes").size());
            inputContexts = Math.max(inputContexts, user.path("input").path("sessionState").path("activeContexts")
                    .size());
            outputContexts = Math.max(outputContexts, user.path("expected").path("activeContexts").size());
        }
        List<String> header = new ArrayList<>(List.of("Line #", "Conversation #", "Source", "Input",
                "Expected Output Intent"));
        for (int i = 1; i <= slotColumns; i++) {
            header.add("Expected Output Slot " + i);
        }
        for (int i = 1; i <= inputContexts; i++) {
            header.add("Input Context Tag " + i);
        }
        for (int i = 1; i <= outputContexts; i++) {
            header.add("Expected Output Context Tag " + i);
        }
        for (int i = 1; i <= requestColumns; i++) {
            header.add("Request Attribute " + i);
        }
        for (int i = 1; i <= sessionColumns; i++) {
            header.add("Session Attribute " + i);
        }
        StringBuilder out = new StringBuilder();
        row(out, header);
        for (JsonNode record : set.path("_").path("records")) {
            List<String> row = new ArrayList<>();
            row.add(record.path("recordNumber").asText());
            row.add(record.path("conversationId").asText(""));
            JsonNode turn = record.path("turnSpecification");
            if (turn.has("agentTurn")) {
                row.add("Agent");
                row.add(turn.path("agentTurn").path("agentPrompt").asText());
                while (row.size() < header.size()) {
                    row.add("");
                }
                row(out, row);
                continue;
            }
            JsonNode user = turn.path("userTurn");
            row.add("User");
            JsonNode utterance = user.path("input").path("utteranceInput");
            row.add(utterance.has("textInput") ? utterance.path("textInput").asText()
                    : utterance.path("audioInput").path("audioFileS3Location").asText());
            row.add(user.path("expected").path("intent").path("name").asText());
            List<String> slots = new ArrayList<>();
            user.path("expected").path("intent").path("slots").fields().forEachRemaining(e ->
                    slots.add(e.getKey() + " = " + e.getValue().path("value").asText()));
            pad(row, slots, slotColumns);
            List<String> in = new ArrayList<>();
            user.path("input").path("sessionState").path("activeContexts").forEach(c -> in.add(c.path("name")
                    .asText()));
            pad(row, in, inputContexts);
            List<String> outContexts = new ArrayList<>();
            user.path("expected").path("activeContexts").forEach(c -> outContexts.add(c.path("name").asText()));
            pad(row, outContexts, outputContexts);
            List<String> request = new ArrayList<>();
            user.path("input").path("requestAttributes").fields().forEachRemaining(e ->
                    request.add(e.getKey() + " = " + e.getValue().asText()));
            pad(row, request, requestColumns);
            List<String> session = new ArrayList<>();
            user.path("input").path("sessionState").path("sessionAttributes").fields().forEachRemaining(e ->
                    session.add(e.getKey() + " = " + e.getValue().asText()));
            pad(row, session, sessionColumns);
            row(out, row);
        }
        return out.toString();
    }

    private static void pad(List<String> row, List<String> values, int width) {
        for (int i = 0; i < width; i++) {
            row.add(i < values.size() ? values.get(i) : "");
        }
    }

    private static void row(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            String cell = cells.get(i);
            if (cell.contains(",") || cell.contains("\"") || cell.contains("\n")) {
                out.append('"').append(cell.replace("\"", "\"\"")).append('"');
            } else {
                out.append(cell);
            }
        }
        out.append('\n');
    }

    ObjectNode testSet(String region, String testSetId) {
        return store.live(region, LexStore.TEST_SET, testSetId)
                .filter(s -> !LexStore.meta(s).has("deleteAt"))
                .orElseThrow(() -> LexStore.notFound("The test set " + testSetId + " does not exist."));
    }

    private static final List<String> SET_FIELDS = List.of("testSetId", "testSetName", "description", "modality",
            "status", "roleArn", "numTurns", "storageLocation", "creationDateTime", "lastUpdatedDateTime");

    private ObjectNode summary(ObjectNode set) {
        ObjectNode out = mapper.createObjectNode();
        LexBots.copy(set, out, SET_FIELDS.toArray(String[]::new));
        return out;
    }

    public ObjectNode describeTestSet(ObjectNode in, String region) {
        return summary(testSet(region, in.path("testSetId").asText()));
    }

    public ObjectNode updateTestSet(ObjectNode in, String region) {
        String testSetId = in.path("testSetId").asText();
        ObjectNode set = store.live(region, LexStore.TEST_SET, testSetId)
                .filter(s -> !LexStore.meta(s).has("deleteAt"))
                .orElseThrow(() -> LexStore.precondition("The test set " + testSetId + " does not exist."));
        String name = in.path("testSetName").asText();
        LexStore.checkName("test set", name);
        set.put("testSetName", name);
        set.remove("description");
        if (in.hasNonNull("description")) {
            set.put("description", in.get("description").asText());
        }
        set.put("lastUpdatedDateTime", LexStore.epoch());
        store.put(region, LexStore.TEST_SET, testSetId, set);
        return summary(set);
    }

    public ObjectNode deleteTestSet(ObjectNode in, String region) {
        String testSetId = in.path("testSetId").asText();
        ObjectNode set = store.live(region, LexStore.TEST_SET, testSetId)
                .filter(s -> !LexStore.meta(s).has("deleteAt"))
                .orElseThrow(() -> LexStore.precondition("The test set " + testSetId + " does not exist."));
        for (ObjectNode execution : store.liveList(region, LexStore.TEST_EXECUTION, "")) {
            if (testSetId.equals(execution.path("testSetId").asText())
                    && "InProgress".equals(execution.path("testExecutionStatus").asText())) {
                throw LexStore.conflict("The test set " + testSetId + " has an execution in progress.");
            }
        }
        store.markDeleted(set, "status", "Deleting");
        store.put(region, LexStore.TEST_SET, testSetId, set);
        return mapper.createObjectNode();
    }

    public ObjectNode listTestSets(ObjectNode in, String region) {
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode set : store.liveList(region, LexStore.TEST_SET, "")) {
            summaries.add(summary(set));
        }
        LexStore.sort(summaries, in.path("sortBy"), Map.of("TestSetName", "testSetName", "LastUpdatedDateTime",
                "lastUpdatedDateTime"), "testSetName");
        ObjectNode out = mapper.createObjectNode();
        LexStore.page(summaries, in, out, "testSets", 10);
        return out;
    }

    public ObjectNode listTestSetRecords(ObjectNode in, String region) {
        ObjectNode set = testSet(region, in.path("testSetId").asText());
        List<ObjectNode> records = new ArrayList<>();
        set.path("_").path("records").forEach(r -> records.add((ObjectNode) r));
        ObjectNode out = mapper.createObjectNode();
        LexStore.page(records, in, out, "testSetRecords", 100);
        return out;
    }

    // ================================================================ generation

    public ObjectNode startTestSetGeneration(ObjectNode in, String region) {
        String name = in.path("testSetName").asText();
        LexStore.checkName("test set", name);
        LexStore.checkTags(in.get("testSetTags"));
        JsonNode source = in.path("generationDataSource").path("conversationLogsDataSource");
        String botId = source.path("botId").asText();
        String aliasId = source.path("botAliasId").asText();
        bots.bot(region, botId);
        bots.alias(region, botId, aliasId);
        String localeId = source.path("localeId").asText();
        JsonNode filter = source.path("filter");
        long start = (long) (filter.path("startTime").asDouble() * 1000);
        long end = (long) (filter.path("endTime").asDouble() * 1000);
        String mode = filter.path("inputMode").asText("Text");

        Map<String, List<JsonNode>> sessions = new LinkedHashMap<>();
        for (ObjectNode turn : store.list(region, LexStore.TURN, botId + "/")) {
            long at = turn.path("timestamp").asLong();
            if (!aliasId.equals(turn.path("botAliasId").asText()) || !localeId.equals(turn.path("localeId").asText())
                    || at < start || at > end || !mode.equals(turn.path("inputMode").asText())
                    || turn.path("utterance").asText().isEmpty()) {
                continue;
            }
            sessions.computeIfAbsent(turn.path("sessionId").asText(), k -> new ArrayList<>()).add(turn);
        }
        ArrayNode records = mapper.createArrayNode();
        int conversation = 0;
        for (List<JsonNode> turns : sessions.values()) {
            conversation++;
            int number = 0;
            for (JsonNode turn : turns) {
                ObjectNode record = records.addObject();
                record.put("recordNumber", records.size());
                if (turns.size() > 1) {
                    record.put("conversationId", String.valueOf(conversation));
                    record.put("turnNumber", number++);
                }
                ObjectNode user = record.putObject("turnSpecification").putObject("userTurn");
                user.putObject("input").putObject("utteranceInput").put("textInput", turn.path("utterance").asText());
                ObjectNode expected = user.putObject("expected").putObject("intent");
                expected.put("name", turn.path("intent").asText(LexStore.FALLBACK));
                expected.putObject("slots");
                for (JsonNode response : turn.path("botResponses")) {
                    if (turns.size() > 1 && "PlainText".equals(response.path("contentType").asText())) {
                        ObjectNode agent = records.addObject();
                        agent.put("recordNumber", records.size());
                        agent.put("conversationId", String.valueOf(conversation));
                        agent.put("turnNumber", number++);
                        agent.putObject("turnSpecification").putObject("agentTurn")
                                .put("agentPrompt", response.path("content").asText());
                    }
                }
            }
        }
        String generationId = LexStore.id();
        double now = LexStore.epoch();
        ObjectNode generation = store.record();
        generation.put("testSetGenerationId", generationId);
        generation.put("creationDateTime", now);
        generation.put("lastUpdatedDateTime", now);
        generation.put("testSetName", name);
        LexBots.copy(in, generation, "description", "storageLocation", "generationDataSource", "roleArn",
                "testSetTags");
        if (records.isEmpty()) {
            LexStore.meta(generation).putArray("failureReasons").add("No conversations matched the data source "
                    + "filter.");
            store.settle(generation, "testSetGenerationStatus", "Generating", "Failed");
        } else {
            String testSetId = LexStore.id();
            ObjectNode set = store.record();
            set.put("testSetId", testSetId);
            set.put("testSetName", name);
            if (in.hasNonNull("description")) {
                set.put("description", in.get("description").asText());
            }
            set.put("modality", "Speech".equals(mode) ? "Audio" : "Text");
            set.put("roleArn", in.path("roleArn").asText());
            set.put("numTurns", records.size());
            set.set("storageLocation", in.path("storageLocation").deepCopy());
            set.put("creationDateTime", now);
            set.put("lastUpdatedDateTime", now);
            store.settle(set, "status", "Importing", "Ready");
            LexStore.meta(set).set("records", records);
            if (in.get("testSetTags") instanceof ObjectNode tags) {
                LexStore.tags(set).setAll(tags);
            }
            try {
                persist(region, set);
                store.put(region, LexStore.TEST_SET, testSetId, set);
                generation.put("testSetId", testSetId);
                store.settle(generation, "testSetGenerationStatus", "Generating", "Ready");
            } catch (AwsException e) {
                LexStore.meta(generation).putArray("failureReasons").add(e.getMessage());
                store.settle(generation, "testSetGenerationStatus", "Generating", "Failed");
            }
        }
        store.put(region, LexStore.TEST_SET_GENERATION, generationId, generation);
        ObjectNode out = LexStore.view(generation);
        out.remove(List.of("lastUpdatedDateTime", "testSetId"));
        return out;
    }

    public ObjectNode describeTestSetGeneration(ObjectNode in, String region) {
        String generationId = in.path("testSetGenerationId").asText();
        ObjectNode generation = store.live(region, LexStore.TEST_SET_GENERATION, generationId)
                .orElseThrow(() -> LexStore.notFound("The test set generation " + generationId
                        + " does not exist."));
        ObjectNode out = LexStore.view(generation);
        out.remove("testSetTags");
        if (!"Ready".equals(generation.path("testSetGenerationStatus").asText())) {
            out.remove("testSetId");
        }
        ArrayNode reasons = out.putArray("failureReasons");
        if ("Failed".equals(generation.path("testSetGenerationStatus").asText())) {
            generation.path("_").path("failureReasons").forEach(reasons::add);
        }
        return out;
    }

    // ================================================================ executions

    public ObjectNode startTestExecution(ObjectNode in, String region) {
        String testSetId = in.path("testSetId").asText();
        ObjectNode set = testSet(region, testSetId);
        if (!"Ready".equals(set.path("status").asText())) {
            throw LexStore.conflict("The test set " + testSetId + " is " + set.path("status").asText() + ".");
        }
        JsonNode target = in.path("target").path("botAliasTarget");
        LexRuntime.Target bot;
        try {
            bot = runtime.target(region, target.path("botId").asText(), target.path("botAliasId").asText(),
                    target.path("localeId").asText());
        } catch (AwsException e) {
            throw LexStore.notFound(e.getMessage());
        }
        String executionId = LexStore.id();
        ArrayNode results = execute(set, bot, executionId);
        ObjectNode execution = store.record();
        execution.put("testExecutionId", executionId);
        double now = LexStore.epoch();
        execution.put("creationDateTime", now);
        execution.put("lastUpdatedDateTime", now);
        execution.put("testSetId", testSetId);
        execution.put("testSetName", set.path("testSetName").asText());
        execution.set("target", in.path("target").deepCopy());
        execution.put("apiMode", in.path("apiMode").asText());
        execution.put("testExecutionModality", in.path("testExecutionModality").asText(set.path("modality")
                .asText("Text")));
        store.settle(execution, "testExecutionStatus", "InProgress", "Completed");
        LexStore.meta(execution).set("results", results);
        store.put(region, LexStore.TEST_EXECUTION, executionId, execution);
        ObjectNode out = mapper.createObjectNode();
        LexBots.copy(execution, out, "testExecutionId", "creationDateTime", "testSetId", "target", "apiMode",
                "testExecutionModality");
        return out;
    }

    /** Replays each conversation against the alias and records each turn's comparison. */
    private ArrayNode execute(ObjectNode set, LexRuntime.Target target, String executionId) {
        ArrayNode results = mapper.createArrayNode();
        Map<String, String> sessions = new LinkedHashMap<>();
        Map<String, ObjectNode> lastResponses = new LinkedHashMap<>();
        for (JsonNode record : set.path("_").path("records")) {
            String conversationId = record.path("conversationId").asText(null);
            String key = conversationId == null ? "record-" + record.path("recordNumber").asText() : conversationId;
            String sessionId = sessions.computeIfAbsent(key, k -> "test-" + executionId + "-" + k
                    .replaceAll("[^A-Za-z0-9_.:-]", "-"));
            ObjectNode item = results.addObject();
            item.put("recordNumber", record.path("recordNumber").asLong());
            if (conversationId != null) {
                item.put("conversationId", conversationId);
            }
            ObjectNode turnResult = item.putObject("turnResult");
            JsonNode turn = record.path("turnSpecification");
            if (turn.has("agentTurn")) {
                ObjectNode agent = turnResult.putObject("agent");
                agent.put("expectedAgentPrompt", turn.path("agentTurn").path("agentPrompt").asText());
                ObjectNode last = lastResponses.get(key);
                if (last != null) {
                    StringBuilder prompt = new StringBuilder();
                    for (JsonNode message : last.path("messages")) {
                        if (!prompt.isEmpty()) {
                            prompt.append(' ');
                        }
                        prompt.append(message.path("content").asText());
                    }
                    if (!prompt.isEmpty()) {
                        agent.put("actualAgentPrompt", prompt.toString());
                    }
                    agent.put("actualIntent", last.path("sessionState").path("intent").path("name").asText(""));
                    if (last.path("sessionState").path("dialogAction").hasNonNull("slotToElicit")) {
                        agent.put("actualElicitedSlot", last.path("sessionState").path("dialogAction")
                                .get("slotToElicit").asText());
                    }
                }
                continue;
            }
            JsonNode user = turn.path("userTurn");
            ObjectNode userResult = turnResult.putObject("user");
            userResult.set("input", user.path("input").deepCopy());
            userResult.set("expectedOutput", user.path("expected").deepCopy());
            JsonNode utterance = user.path("input").path("utteranceInput");
            String text = utterance.path("textInput").asText(null);
            try {
                if (text == null) {
                    String location = utterance.path("audioInput").path("audioFileS3Location").asText();
                    String[] parts = location.replaceFirst("^s3://", "").split("/", 2);
                    byte[] audio = s3.get().getObject(parts[0], parts.length > 1 ? parts[1] : "").getData();
                    text = runtime.transcriptOf(audio);
                }
                ObjectNode state = null;
                JsonNode inputState = user.path("input").path("sessionState");
                if (inputState.isObject() && !inputState.isEmpty()) {
                    ObjectNode prior = lastResponses.containsKey(key)
                            ? (ObjectNode) lastResponses.get(key).path("sessionState").deepCopy()
                            : mapper.createObjectNode();
                    if (inputState.has("sessionAttributes")) {
                        prior.withObject("/sessionAttributes").setAll((ObjectNode) inputState.get(
                                "sessionAttributes"));
                    }
                    if (inputState.has("activeContexts")) {
                        ArrayNode contexts = prior.putArray("activeContexts");
                        inputState.path("activeContexts").forEach(c -> contexts.addObject()
                                .put("name", c.path("name").asText())
                                .set("timeToLive", mapper.createObjectNode().put("timeToLiveInSeconds", 600)
                                        .put("turnsToLive", 20)));
                        contexts.forEach(c -> ((ObjectNode) c).putObject("contextAttributes"));
                    }
                    state = prior;
                }
                ObjectNode response = runtime.converse(target, sessionId, text, utterance.has("textInput")
                                ? "Text" : "Speech", state, user.path("input").get("requestAttributes"),
                        "text/plain; charset=utf-8", null);
                lastResponses.put(key, response);
                ObjectNode actual = userResult.putObject("actualOutput");
                ObjectNode actualIntent = actual.putObject("intent");
                JsonNode intent = response.path("sessionState").path("intent");
                actualIntent.put("name", intent.path("name").asText(""));
                ObjectNode slots = actualIntent.putObject("slots");
                intent.path("slots").fields().forEachRemaining(e -> {
                    JsonNode value = e.getValue().path("value").path("interpretedValue");
                    if (value.isTextual()) {
                        slots.putObject(e.getKey()).put("value", value.asText());
                    }
                });
                ArrayNode contexts = actual.putArray("activeContexts");
                response.path("sessionState").path("activeContexts").forEach(c -> contexts.addObject()
                        .put("name", c.path("name").asText()));
                actual.put("transcript", text);
                String expectedIntent = user.path("expected").path("intent").path("name").asText();
                boolean intentMatch = expectedIntent.isEmpty()
                        || expectedIntent.equals(actualIntent.path("name").asText());
                boolean slotMatch = true;
                Iterator<Map.Entry<String, JsonNode>> expectedSlots = user.path("expected").path("intent")
                        .path("slots").fields();
                while (expectedSlots.hasNext()) {
                    Map.Entry<String, JsonNode> slot = expectedSlots.next();
                    String want = slot.getValue().path("value").asText();
                    String got = slots.path(slot.getKey()).path("value").asText(null);
                    slotMatch &= got != null && want.toLowerCase(Locale.ROOT).equals(got.toLowerCase(Locale.ROOT));
                }
                boolean contextMatch = true;
                for (JsonNode context : user.path("expected").path("activeContexts")) {
                    boolean present = false;
                    for (JsonNode c : contexts) {
                        present |= context.path("name").asText().equals(c.path("name").asText());
                    }
                    contextMatch &= present;
                }
                userResult.put("intentMatchResult", intentMatch ? "Matched" : "Mismatched");
                userResult.put("slotMatchResult", slotMatch ? "Matched" : "Mismatched");
                userResult.put("endToEndResult", intentMatch && slotMatch && contextMatch ? "Matched"
                        : "Mismatched");
                if (utterance.has("audioInput")) {
                    String expectedTranscript = user.path("expected").path("transcript").asText(null);
                    userResult.put("speechTranscriptionResult", expectedTranscript == null
                            || expectedTranscript.equalsIgnoreCase(text) ? "Matched" : "Mismatched");
                }
            } catch (AwsException e) {
                userResult.putObject("errorDetails").put("errorCode", e.getErrorCode())
                        .put("errorMessage", e.getMessage());
                userResult.put("endToEndResult", "ExecutionError");
                userResult.put("intentMatchResult", "ExecutionError");
                userResult.put("slotMatchResult", "ExecutionError");
            }
        }
        for (String sessionId : sessions.values()) {
            store.delete(target.region(), LexStore.SESSION, target.botId() + "/" + target.aliasId() + "/"
                    + target.localeId() + "/" + sessionId);
        }
        Map<String, String> conversationResults = new TreeMap<>();
        for (JsonNode item : results) {
            String conversationId = item.path("conversationId").asText(null);
            String result = item.path("turnResult").path("user").path("endToEndResult").asText(null);
            if (conversationId == null || result == null) {
                continue;
            }
            conversationResults.merge(conversationId, result, LexTesting::worse);
        }
        for (JsonNode item : results) {
            String conversationId = item.path("conversationId").asText(null);
            if (conversationId != null && item.path("turnResult").has("user")) {
                ((ObjectNode) item.path("turnResult").path("user")).putObject("conversationLevelResult")
                        .put("endToEndResult", conversationResults.get(conversationId));
            }
        }
        return results;
    }

    private static String worse(String a, String b) {
        if ("ExecutionError".equals(a) || "ExecutionError".equals(b)) {
            return "ExecutionError";
        }
        return "Mismatched".equals(a) || "Mismatched".equals(b) ? "Mismatched" : "Matched";
    }

    private ObjectNode execution(String region, String executionId) {
        return store.live(region, LexStore.TEST_EXECUTION, executionId)
                .orElseThrow(() -> LexStore.notFound("The test execution " + executionId + " does not exist."));
    }

    public ObjectNode describeTestExecution(ObjectNode in, String region) {
        ObjectNode out = LexStore.view(execution(region, in.path("testExecutionId").asText()));
        out.putArray("failureReasons");
        return out;
    }

    public ObjectNode listTestExecutions(ObjectNode in, String region) {
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode execution : store.liveList(region, LexStore.TEST_EXECUTION, "")) {
            summaries.add(LexStore.view(execution));
        }
        LexStore.sort(summaries, in.path("sortBy"), Map.of("TestSetName", "testSetName", "CreationDateTime",
                "creationDateTime"), "creationDateTime");
        ObjectNode out = mapper.createObjectNode();
        LexStore.page(summaries, in, out, "testExecutions", 10);
        return out;
    }

    public ObjectNode listTestExecutionResultItems(ObjectNode in, String region) {
        ObjectNode execution = execution(region, in.path("testExecutionId").asText());
        JsonNode results = execution.path("_").path("results");
        JsonNode filter = in.path("resultFilterBy");
        String type = filter.path("resultTypeFilter").asText();
        List<ObjectNode> items = new ArrayList<>();
        String container;
        switch (type) {
            case "OverallTestResults" -> {
                container = "overallTestResults";
                for (boolean multi : List.of(false, true)) {
                    Map<String, Integer> counts = new TreeMap<>();
                    Map<String, Integer> speech = new TreeMap<>();
                    int total = 0;
                    for (JsonNode item : results) {
                        JsonNode user = item.path("turnResult").path("user");
                        if (!user.isObject() || item.has("conversationId") != multi) {
                            continue;
                        }
                        total++;
                        counts.merge(user.path("endToEndResult").asText(), 1, Integer::sum);
                        if (user.has("speechTranscriptionResult")) {
                            speech.merge(user.path("speechTranscriptionResult").asText(), 1, Integer::sum);
                        }
                    }
                    if (total == 0) {
                        continue;
                    }
                    ObjectNode item = mapper.createObjectNode();
                    item.put("multiTurnConversation", multi);
                    item.put("totalResultCount", total);
                    if (!speech.isEmpty()) {
                        item.set("speechTranscriptionResultCounts", mapper.valueToTree(speech));
                    }
                    item.set("endToEndResultCounts", mapper.valueToTree(counts));
                    items.add(item);
                }
            }
            case "ConversationLevelTestResults" -> {
                container = "conversationLevelTestResults";
                Map<String, ObjectNode> conversations = new LinkedHashMap<>();
                for (JsonNode item : results) {
                    JsonNode user = item.path("turnResult").path("user");
                    String conversationId = item.path("conversationId").asText(null);
                    if (conversationId == null || !user.isObject()) {
                        continue;
                    }
                    ObjectNode conversation = conversations.computeIfAbsent(conversationId, id -> {
                        ObjectNode c = mapper.createObjectNode();
                        c.put("conversationId", id);
                        c.put("endToEndResult", user.path("conversationLevelResult").path("endToEndResult")
                                .asText());
                        c.putArray("intentClassificationResults");
                        c.putArray("slotResolutionResults");
                        return c;
                    });
                    String intent = user.path("expectedOutput").path("intent").path("name").asText();
                    conversation.withArray("intentClassificationResults").addObject().put("intentName", intent)
                            .put("matchResult", user.path("intentMatchResult").asText());
                    user.path("expectedOutput").path("intent").path("slots").fieldNames().forEachRemaining(slot ->
                            conversation.withArray("slotResolutionResults").addObject().put("intentName", intent)
                                    .put("slotName", slot).put("matchResult", user.path("slotMatchResult").asText()));
                }
                String wanted = filter.path("conversationLevelTestResultsFilterBy").path("endToEndResult")
                        .asText(null);
                for (ObjectNode conversation : conversations.values()) {
                    if (wanted == null || wanted.equals(conversation.path("endToEndResult").asText())) {
                        items.add(conversation);
                    }
                }
            }
            case "IntentClassificationTestResults" -> {
                container = "intentClassificationTestResults";
                Map<String, ObjectNode> byIntent = new TreeMap<>();
                for (JsonNode item : results) {
                    JsonNode user = item.path("turnResult").path("user");
                    if (!user.isObject()) {
                        continue;
                    }
                    boolean multi = item.has("conversationId");
                    String intent = user.path("expectedOutput").path("intent").path("name").asText();
                    ObjectNode entry = byIntent.computeIfAbsent(intent + "/" + multi, k -> {
                        ObjectNode e = mapper.createObjectNode();
                        e.put("intentName", intent);
                        e.put("multiTurnConversation", multi);
                        ObjectNode counts = e.putObject("resultCounts");
                        counts.put("totalResultCount", 0);
                        counts.putObject("intentMatchResultCounts");
                        return e;
                    });
                    ObjectNode counts = (ObjectNode) entry.get("resultCounts");
                    counts.put("totalResultCount", counts.path("totalResultCount").asInt() + 1);
                    ObjectNode match = (ObjectNode) counts.get("intentMatchResultCounts");
                    String result = user.path("intentMatchResult").asText();
                    match.put(result, match.path(result).asInt() + 1);
                }
                items.addAll(byIntent.values());
            }
            case "SlotResolutionTestResults" -> {
                container = "intentLevelSlotResolutionTestResults";
                Map<String, Map<String, int[]>> byIntent = new TreeMap<>();
                Map<String, Boolean> multiTurn = new LinkedHashMap<>();
                for (JsonNode item : results) {
                    JsonNode user = item.path("turnResult").path("user");
                    if (!user.isObject()) {
                        continue;
                    }
                    String intent = user.path("expectedOutput").path("intent").path("name").asText();
                    String key = intent + "/" + item.has("conversationId");
                    multiTurn.put(key, item.has("conversationId"));
                    Map<String, int[]> slots = byIntent.computeIfAbsent(key, k -> new TreeMap<>());
                    user.path("expectedOutput").path("intent").path("slots").fields().forEachRemaining(e -> {
                        String want = e.getValue().path("value").asText();
                        String got = user.path("actualOutput").path("intent").path("slots").path(e.getKey())
                                .path("value").asText(null);
                        int[] counts = slots.computeIfAbsent(e.getKey(), s -> new int[3]);
                        counts[0]++;
                        if (user.has("errorDetails")) {
                            counts[2]++;
                        } else if (got != null && got.equalsIgnoreCase(want)) {
                            counts[1]++;
                        }
                    });
                }
                byIntent.forEach((key, slots) -> {
                    ObjectNode entry = mapper.createObjectNode();
                    entry.put("intentName", key.substring(0, key.lastIndexOf('/')));
                    entry.put("multiTurnConversation", multiTurn.get(key));
                    ArrayNode list = entry.putArray("slotResolutionResults");
                    slots.forEach((slot, counts) -> {
                        ObjectNode result = list.addObject();
                        result.put("slotName", slot);
                        ObjectNode resultCounts = result.putObject("resultCounts");
                        resultCounts.put("totalResultCount", counts[0]);
                        ObjectNode match = resultCounts.putObject("slotMatchResultCounts");
                        match.put("Matched", counts[1]);
                        match.put("Mismatched", counts[0] - counts[1] - counts[2]);
                        if (counts[2] > 0) {
                            match.put("ExecutionError", counts[2]);
                        }
                    });
                    items.add(entry);
                });
            }
            case "UtteranceLevelResults" -> {
                container = "utteranceLevelTestResults";
                results.forEach(item -> items.add((ObjectNode) item));
            }
            default -> throw LexStore.validation("The result type filter " + type + " is not valid.");
        }
        ObjectNode page = mapper.createObjectNode();
        LexStore.page(items, in, page, "items", 100);
        ObjectNode out = mapper.createObjectNode();
        ObjectNode wrapper = out.putObject("testExecutionResults").putObject(container);
        wrapper.set("items", page.get("items"));
        if (page.has("nextToken")) {
            out.set("nextToken", page.get("nextToken"));
        }
        return out;
    }

    public ObjectNode getTestExecutionArtifactsUrl(ObjectNode in, String region) {
        ObjectNode execution = execution(region, in.path("testExecutionId").asText());
        if (!"Completed".equals(execution.path("testExecutionStatus").asText())) {
            throw LexStore.validation("The test execution has not completed.");
        }
        StringBuilder csv = new StringBuilder("Line #,Conversation #,Source,Input,Expected Output Intent,"
                + "Actual Output Intent,Intent Match Result,Slot Match Result,End-to-end Result\n");
        for (JsonNode item : execution.path("_").path("results")) {
            JsonNode turn = item.path("turnResult");
            List<String> row = new ArrayList<>();
            row.add(item.path("recordNumber").asText());
            row.add(item.path("conversationId").asText(""));
            if (turn.has("agent")) {
                row.addAll(List.of("Agent", turn.path("agent").path("expectedAgentPrompt").asText(), "",
                        turn.path("agent").path("actualIntent").asText(""), "", "", ""));
            } else {
                JsonNode user = turn.path("user");
                row.addAll(List.of("User", user.path("input").path("utteranceInput").path("textInput").asText(""),
                        user.path("expectedOutput").path("intent").path("name").asText(""),
                        user.path("actualOutput").path("intent").path("name").asText(""),
                        user.path("intentMatchResult").asText(""), user.path("slotMatchResult").asText(""),
                        user.path("endToEndResult").asText("")));
            }
            row(csv, row);
        }
        String url = store.putFile(LexPorting.zip(Map.of("TestExecutionResults.csv",
                csv.toString().getBytes(StandardCharsets.UTF_8))), "application/zip", "artifacts.zip");
        ObjectNode out = mapper.createObjectNode();
        out.put("testExecutionId", execution.path("testExecutionId").asText());
        out.put("downloadArtifactsUrl", url);
        return out;
    }

    // ================================================================ discrepancy reports

    public ObjectNode createTestSetDiscrepancyReport(ObjectNode in, String region) {
        String testSetId = in.path("testSetId").asText();
        ObjectNode set = testSet(region, testSetId);
        JsonNode target = in.path("target").path("botAliasTarget");
        LexRuntime.Target bot;
        try {
            bot = runtime.target(region, target.path("botId").asText(), target.path("botAliasId").asText(),
                    target.path("localeId").asText());
        } catch (AwsException e) {
            throw LexStore.notFound(e.getMessage());
        }
        ObjectNode errors = mapper.createObjectNode();
        ArrayNode intents = errors.putArray("intentDiscrepancies");
        ArrayNode slots = errors.putArray("slotDiscrepancies");
        Set<String> seen = new HashSet<>();
        for (JsonNode record : set.path("_").path("records")) {
            JsonNode expected = record.path("turnSpecification").path("userTurn").path("expected").path("intent");
            String name = expected.path("name").asText("");
            if (name.isEmpty()) {
                continue;
            }
            JsonNode intent = null;
            for (JsonNode candidate : bot.model().path("intents")) {
                if (name.equals(candidate.path("intentName").asText())) {
                    intent = candidate;
                }
            }
            if (intent == null) {
                if (seen.add("intent/" + name)) {
                    intents.addObject().put("intentName", name).put("errorMessage", "The intent " + name
                            + " is not in the bot locale.");
                }
                continue;
            }
            List<String> slotNames = new ArrayList<>();
            LexNlu.slotsOf(bot.model(), intent).forEach(s -> slotNames.add(s.path("slotName").asText()));
            final String intentName = name;
            expected.path("slots").fieldNames().forEachRemaining(slot -> {
                if (!slotNames.contains(slot) && seen.add("slot/" + intentName + "/" + slot)) {
                    slots.addObject().put("intentName", intentName).put("slotName", slot)
                            .put("errorMessage", "The slot " + slot + " is not in the intent " + intentName + ".");
                }
            });
        }
        String reportId = LexStore.id();
        ObjectNode report = store.record();
        report.put("testSetDiscrepancyReportId", reportId);
        report.put("testSetId", testSetId);
        double now = LexStore.epoch();
        report.put("creationDateTime", now);
        report.put("lastUpdatedDataTime", now);
        report.set("target", in.path("target").deepCopy());
        store.settle(report, "testSetDiscrepancyReportStatus", "InProgress", "Completed");
        LexStore.meta(report).set("errors", errors);
        LexStore.meta(report).put("url", store.putFile(errors.toString().getBytes(StandardCharsets.UTF_8),
                "application/json", reportId + ".json"));
        store.put(region, LexStore.DISCREPANCY, reportId, report);
        ObjectNode out = mapper.createObjectNode();
        LexBots.copy(report, out, "testSetDiscrepancyReportId", "creationDateTime", "testSetId", "target");
        return out;
    }

    public ObjectNode describeTestSetDiscrepancyReport(ObjectNode in, String region) {
        String reportId = in.path("testSetDiscrepancyReportId").asText();
        ObjectNode report = store.live(region, LexStore.DISCREPANCY, reportId)
                .orElseThrow(() -> LexStore.notFound("The discrepancy report " + reportId + " does not exist."));
        ObjectNode out = LexStore.view(report);
        out.putArray("failureReasons");
        if ("Completed".equals(report.path("testSetDiscrepancyReportStatus").asText())) {
            out.set("testSetDiscrepancyTopErrors", report.path("_").path("errors").deepCopy());
            out.put("testSetDiscrepancyRawOutputUrl", report.path("_").path("url").asText());
        }
        return out;
    }
}
