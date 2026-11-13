package io.floci.aws.services.deadline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.floci.aws.services.deadline.DeadlineStore.invalid;

/**
 * Open Job Description templates (specification {@code jobtemplate-2023-09} and {@code environment-2023-09}), as
 * Deadline Cloud reads them when a job is submitted: the template is parsed from JSON or YAML and validated, the
 * submitted job parameters are checked against the template's parameter definitions (defaults filled in), the
 * job's name is resolved, and each step's parameter space expands into its tasks, by its combination expression.
 */
final class DeadlineTemplates {

    static final String JOB_SPEC = "jobtemplate-2023-09";
    static final String ENVIRONMENT_SPEC = "environment-2023-09";
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");
    private static final Pattern PARAM_REFERENCE = Pattern.compile("\\{\\{\\s*Param\\.([A-Za-z_][A-Za-z0-9_]*)\\s*}}");
    private static final Pattern RAW_PARAM_REFERENCE =
            Pattern.compile("\\{\\{\\s*RawParam\\.([A-Za-z_][A-Za-z0-9_]*)\\s*}}");
    private static final Set<String> PARAMETER_TYPES = Set.of("STRING", "INT", "FLOAT", "PATH");
    private static final Set<String> TASK_PARAMETER_TYPES = Set.of("INT", "FLOAT", "STRING", "PATH", "CHUNK[INT]");
    private static final int MAX_TASKS = 10000;

    private final ObjectMapper json;
    private final YAMLMapper yaml = new YAMLMapper();

    DeadlineTemplates(ObjectMapper json) {
        this.json = json;
    }

    /** A template parsed as its {@code templateType} says. */
    ObjectNode parse(String template, String type) {
        if (template == null || template.isBlank()) {
            throw invalid("template is required.");
        }
        try {
            JsonNode parsed = "YAML".equals(type) ? yaml.readTree(template) : json.readTree(template);
            if (!(parsed instanceof ObjectNode object)) {
                throw invalid("The template must be a " + type + " object.");
            }
            return json.convertValue(object, ObjectNode.class);
        } catch (JsonProcessingException e) {
            throw invalid("The template is not valid " + type + ": " + e.getOriginalMessage());
        }
    }

    /** Validates a job template's structure: its name, parameter definitions, environments and steps. */
    void validateJob(ObjectNode template) {
        if (!JOB_SPEC.equals(template.path("specificationVersion").asText())) {
            throw invalid("specificationVersion must be " + JOB_SPEC + ".");
        }
        requireText(template, "name", "The job template");
        Set<String> parameters = new HashSet<>();
        for (JsonNode definition : template.path("parameterDefinitions")) {
            String name = identifier(definition, "parameterDefinitions[].name");
            if (!parameters.add(name)) {
                throw invalid("Job parameter " + name + " is defined more than once.");
            }
            if (!PARAMETER_TYPES.contains(definition.path("type").asText())) {
                throw invalid("Job parameter " + name + " must be of type STRING, INT, FLOAT or PATH.");
            }
        }
        environments(template.path("jobEnvironments"), "jobEnvironments");
        JsonNode steps = template.path("steps");
        if (!steps.isArray() || steps.isEmpty()) {
            throw invalid("The job template must define at least one step.");
        }
        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        for (JsonNode step : steps) {
            String name = requireText(step, "name", "A step");
            if (dependencies.containsKey(name)) {
                throw invalid("Step " + name + " is defined more than once.");
            }
            JsonNode script = step.path("script");
            if (!script.isObject() || !script.path("actions").path("onRun").hasNonNull("command")) {
                throw invalid("Step " + name + " must define script.actions.onRun.command.");
            }
            Set<String> taskParameters = new HashSet<>();
            for (JsonNode definition : step.path("parameterSpace").path("taskParameterDefinitions")) {
                String parameter = identifier(definition, "taskParameterDefinitions[].name");
                if (!taskParameters.add(parameter)) {
                    throw invalid("Task parameter " + parameter + " of step " + name + " is defined more than once.");
                }
                if (!TASK_PARAMETER_TYPES.contains(definition.path("type").asText())) {
                    throw invalid("Task parameter " + parameter + " of step " + name
                            + " must be of type INT, FLOAT, STRING, PATH or CHUNK[INT].");
                }
                if (!definition.has("range")) {
                    throw invalid("Task parameter " + parameter + " of step " + name + " must define a range.");
                }
            }
            environments(step.path("stepEnvironments"), "stepEnvironments of step " + name);
            List<String> dependsOn = new ArrayList<>();
            for (JsonNode dependency : step.path("dependencies")) {
                dependsOn.add(requireText(dependency, "dependsOn", "A dependency of step " + name));
            }
            dependencies.put(name, dependsOn);
        }
        dependencies.forEach((step, dependsOn) -> {
            for (String target : dependsOn) {
                if (!dependencies.containsKey(target)) {
                    throw invalid("Step " + step + " depends on step " + target + ", which the template does not "
                            + "define.");
                }
                if (target.equals(step)) {
                    throw invalid("Step " + step + " depends on itself.");
                }
            }
        });
        acyclic(dependencies);
    }

    /** Validates a queue environment template and returns the environment's name. */
    String validateEnvironment(ObjectNode template) {
        if (!ENVIRONMENT_SPEC.equals(template.path("specificationVersion").asText())) {
            throw invalid("specificationVersion must be " + ENVIRONMENT_SPEC + ".");
        }
        JsonNode environment = template.path("environment");
        if (!environment.isObject()) {
            throw invalid("The environment template must define an environment.");
        }
        String name = requireText(environment, "name", "The environment");
        if (!environment.has("script") && !environment.has("variables")) {
            throw invalid("Environment " + name + " must define a script or variables.");
        }
        return name;
    }

    private void environments(JsonNode environments, String where) {
        Set<String> names = new HashSet<>();
        for (JsonNode environment : environments) {
            String name = requireText(environment, "name", "An environment in " + where);
            if (!names.add(name)) {
                throw invalid("Environment " + name + " is defined more than once in " + where + ".");
            }
            if (!environment.has("script") && !environment.has("variables")) {
                throw invalid("Environment " + name + " must define a script or variables.");
            }
        }
    }

    private static void acyclic(Map<String, List<String>> dependencies) {
        Map<String, Integer> state = new HashMap<>();
        for (String start : dependencies.keySet()) {
            Deque<String[]> stack = new ArrayDeque<>();
            if (state.getOrDefault(start, 0) == 0) {
                stack.push(new String[] {start, "0"});
            }
            while (!stack.isEmpty()) {
                String[] frame = stack.peek();
                String step = frame[0];
                int index = Integer.parseInt(frame[1]);
                state.put(step, 1);
                List<String> next = dependencies.getOrDefault(step, List.of());
                if (index < next.size()) {
                    frame[1] = String.valueOf(index + 1);
                    String target = next.get(index);
                    int targetState = state.getOrDefault(target, 0);
                    if (targetState == 1) {
                        throw invalid("The step dependencies form a cycle through step " + target + ".");
                    }
                    if (targetState == 0) {
                        stack.push(new String[] {target, "0"});
                    }
                } else {
                    state.put(step, 2);
                    stack.pop();
                }
            }
        }
    }

    // ---------------------------------------------------------------- job parameters

    /**
     * The job's parameters: each submitted value checked against its definition, and each definition's default
     * filled in. Values are {@code {"<type>": "<value>"}} objects, as Deadline returns them.
     */
    ObjectNode resolveParameters(ObjectNode template, JsonNode submitted) {
        Map<String, JsonNode> definitions = new LinkedHashMap<>();
        for (JsonNode definition : template.path("parameterDefinitions")) {
            definitions.put(definition.path("name").asText(), definition);
        }
        ObjectNode resolved = json.createObjectNode();
        if (submitted != null && submitted.isObject()) {
            submitted.properties().forEach(entry -> {
                JsonNode definition = definitions.get(entry.getKey());
                if (definition == null && !entry.getKey().startsWith("deadline:")) {
                    throw invalid("The job template defines no parameter " + entry.getKey() + ".");
                }
                if (definition == null) {
                    resolved.set(entry.getKey(), entry.getValue().deepCopy());
                    return;
                }
                resolved.set(entry.getKey(), checked(definition, entry.getValue()));
            });
        }
        definitions.forEach((name, definition) -> {
            if (resolved.has(name)) {
                return;
            }
            if (!definition.has("default")) {
                throw invalid("Job parameter " + name + " is required: it has no default.");
            }
            String type = definition.path("type").asText().toLowerCase();
            resolved.set(name, checked(definition, json.createObjectNode().put(type,
                    definition.get("default").asText())));
        });
        return resolved;
    }

    private ObjectNode checked(JsonNode definition, JsonNode value) {
        String name = definition.path("name").asText();
        String type = definition.path("type").asText();
        String member = type.toLowerCase();
        if (!value.isObject() || value.size() != 1 || !value.has(member)) {
            throw invalid("Job parameter " + name + " must be a " + member + " value.");
        }
        String text = value.get(member).asText();
        switch (type) {
            case "INT" -> {
                long number;
                try {
                    number = Long.parseLong(text.strip());
                } catch (NumberFormatException e) {
                    throw invalid("Job parameter " + name + " must be an integer, not " + text + ".");
                }
                bounds(definition, name, number);
            }
            case "FLOAT" -> {
                double number;
                try {
                    number = Double.parseDouble(text.strip());
                } catch (NumberFormatException e) {
                    throw invalid("Job parameter " + name + " must be a number, not " + text + ".");
                }
                bounds(definition, name, number);
            }
            default -> {
                if (definition.has("minLength") && text.length() < definition.get("minLength").asInt()) {
                    throw invalid("Job parameter " + name + " must be at least " + definition.get("minLength")
                            .asInt() + " characters.");
                }
                if (definition.has("maxLength") && text.length() > definition.get("maxLength").asInt()) {
                    throw invalid("Job parameter " + name + " must be at most " + definition.get("maxLength")
                            .asInt() + " characters.");
                }
            }
        }
        if (definition.path("allowedValues").isArray() && !definition.get("allowedValues").isEmpty()) {
            boolean allowed = false;
            for (JsonNode candidate : definition.get("allowedValues")) {
                allowed |= candidate.asText().equals(text);
            }
            if (!allowed) {
                throw invalid("Job parameter " + name + " must be one of its allowedValues, not " + text + ".");
            }
        }
        return json.createObjectNode().put(member, text);
    }

    private static void bounds(JsonNode definition, String name, double number) {
        if (definition.has("minValue") && number < definition.get("minValue").asDouble()) {
            throw invalid("Job parameter " + name + " must be at least " + definition.get("minValue").asText() + ".");
        }
        if (definition.has("maxValue") && number > definition.get("maxValue").asDouble()) {
            throw invalid("Job parameter " + name + " must be at most " + definition.get("maxValue").asText() + ".");
        }
    }

    /** A format string with its {@code {{Param.X}}} and {@code {{RawParam.X}}} references resolved. */
    static String resolve(String format, JsonNode parameters) {
        String resolved = substitute(format, PARAM_REFERENCE, parameters);
        return substitute(resolved, RAW_PARAM_REFERENCE, parameters);
    }

    private static String substitute(String format, Pattern pattern, JsonNode parameters) {
        Matcher matcher = pattern.matcher(format);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            JsonNode value = parameters.path(matcher.group(1));
            if (!value.isObject() || value.isEmpty()) {
                throw invalid("The template refers to undefined job parameter " + matcher.group(1) + ".");
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value.elements().next().asText()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    // ---------------------------------------------------------------- parameter spaces

    /**
     * The tasks of a step: one per element of its parameter space, each the task parameters
     * {@code {"<name>": {"<type>": "<value>"}}}. A step without a parameter space runs one task.
     */
    List<ObjectNode> tasks(JsonNode step, JsonNode parameters) {
        JsonNode space = step.path("parameterSpace");
        JsonNode definitions = space.path("taskParameterDefinitions");
        if (!definitions.isArray() || definitions.isEmpty()) {
            return List.of(json.createObjectNode());
        }
        Map<String, List<ObjectNode>> values = new LinkedHashMap<>();
        for (JsonNode definition : definitions) {
            values.put(definition.path("name").asText(), values(definition, parameters, step.path("name").asText()));
        }
        String combination = space.path("combination").asText(null);
        List<Map<String, ObjectNode>> combined = combination == null
                ? product(new ArrayList<>(values.keySet()), values)
                : new Combination(combination, values, step.path("name").asText()).parse();
        if (combined.size() > MAX_TASKS) {
            throw invalid("Step " + step.path("name").asText() + " has " + combined.size() + " tasks, more than "
                    + MAX_TASKS + ".");
        }
        List<ObjectNode> tasks = new ArrayList<>();
        for (Map<String, ObjectNode> combinationValues : combined) {
            ObjectNode task = json.createObjectNode();
            for (String name : values.keySet()) {
                if (combinationValues.containsKey(name)) {
                    task.set(name, combinationValues.get(name));
                }
            }
            tasks.add(task);
        }
        return tasks;
    }

    private List<ObjectNode> values(JsonNode definition, JsonNode parameters, String step) {
        String name = definition.path("name").asText();
        String type = definition.path("type").asText();
        JsonNode range = definition.get("range");
        List<String> items = new ArrayList<>();
        if (range.isArray()) {
            for (JsonNode item : range) {
                items.add(resolve(item.asText(), parameters));
            }
        } else {
            String expression = resolve(range.asText(), parameters);
            if (type.equals("INT") || type.equals("CHUNK[INT]")) {
                for (long value : intRange(expression, name, step)) {
                    items.add(String.valueOf(value));
                }
            } else {
                items.add(expression);
            }
        }
        if (items.isEmpty()) {
            throw invalid("Task parameter " + name + " of step " + step + " has an empty range.");
        }
        List<ObjectNode> result = new ArrayList<>();
        if (type.equals("CHUNK[INT]")) {
            int size = Math.max(1, definition.path("chunks").path("defaultTaskCount").asInt(1));
            for (int start = 0; start < items.size(); start += size) {
                List<String> chunk = items.subList(start, Math.min(items.size(), start + size));
                String value = chunk.size() == 1 ? chunk.get(0) : chunk.get(0) + "-" + chunk.get(chunk.size() - 1);
                result.add(json.createObjectNode().put("chunkInt", value));
            }
            return result;
        }
        for (String item : items) {
            switch (type) {
                case "INT" -> {
                    try {
                        Long.parseLong(item.strip());
                    } catch (NumberFormatException e) {
                        throw invalid("Task parameter " + name + " of step " + step + " has a non-integer value "
                                + item + ".");
                    }
                }
                case "FLOAT" -> {
                    try {
                        new BigDecimal(item.strip());
                    } catch (NumberFormatException e) {
                        throw invalid("Task parameter " + name + " of step " + step + " has a non-numeric value "
                                + item + ".");
                    }
                }
                default -> {
                }
            }
            result.add(json.createObjectNode().put(type.toLowerCase(), item));
        }
        return result;
    }

    /** An Open Job Description integer range expression: {@code 1-10}, {@code 1-10:2}, {@code 1,3,5-7}. */
    static List<Long> intRange(String expression, String name, String step) {
        List<Long> result = new ArrayList<>();
        for (String part : expression.split(",")) {
            String piece = part.strip();
            if (piece.isEmpty()) {
                continue;
            }
            Matcher range = Pattern.compile("^(-?\\d+)\\s*-\\s*(-?\\d+)(?:\\s*:\\s*(-?\\d+))?$").matcher(piece);
            try {
                if (range.matches()) {
                    long start = Long.parseLong(range.group(1));
                    long end = Long.parseLong(range.group(2));
                    long stepSize = range.group(3) == null ? (start <= end ? 1 : -1) : Long.parseLong(range.group(3));
                    if (stepSize == 0 || (end - start) * stepSize < 0) {
                        throw invalid("Task parameter " + name + " of step " + step + " has an invalid range "
                                + piece + ".");
                    }
                    for (long value = start; stepSize > 0 ? value <= end : value >= end; value += stepSize) {
                        result.add(value);
                        if (result.size() > MAX_TASKS) {
                            throw invalid("Task parameter " + name + " of step " + step + " has more than " + MAX_TASKS
                                    + " values.");
                        }
                    }
                } else {
                    result.add(Long.parseLong(piece));
                }
            } catch (NumberFormatException e) {
                throw invalid("Task parameter " + name + " of step " + step + " has an invalid range " + piece + ".");
            }
        }
        return result;
    }

    private static List<Map<String, ObjectNode>> product(List<String> names, Map<String, List<ObjectNode>> values) {
        List<Map<String, ObjectNode>> result = new ArrayList<>();
        result.add(new LinkedHashMap<>());
        for (String name : names) {
            List<Map<String, ObjectNode>> next = new ArrayList<>();
            for (Map<String, ObjectNode> partial : result) {
                for (ObjectNode value : values.get(name)) {
                    Map<String, ObjectNode> extended = new LinkedHashMap<>(partial);
                    extended.put(name, value);
                    next.add(extended);
                }
            }
            result = next;
        }
        return result;
    }

    /**
     * A combination expression: {@code A * B} is the product of its operands, {@code (A, B)} associates its operands
     * element by element (they must be the same length), and every task parameter appears exactly once.
     */
    private static final class Combination {
        private final String text;
        private final Map<String, List<ObjectNode>> values;
        private final String step;
        private final Set<String> used = new HashSet<>();
        private int position;

        Combination(String text, Map<String, List<ObjectNode>> values, String step) {
            this.text = text;
            this.values = values;
            this.step = step;
        }

        List<Map<String, ObjectNode>> parse() {
            List<Map<String, ObjectNode>> result = product();
            skip();
            if (position != text.length()) {
                throw error();
            }
            if (!used.equals(values.keySet())) {
                throw invalid("The combination of step " + step + " must use every task parameter exactly once.");
            }
            return result;
        }

        private List<Map<String, ObjectNode>> product() {
            List<Map<String, ObjectNode>> result = term();
            while (peek('*')) {
                position++;
                List<Map<String, ObjectNode>> right = term();
                List<Map<String, ObjectNode>> next = new ArrayList<>();
                for (Map<String, ObjectNode> left : result) {
                    for (Map<String, ObjectNode> value : right) {
                        Map<String, ObjectNode> merged = new LinkedHashMap<>(left);
                        merged.putAll(value);
                        next.add(merged);
                    }
                }
                result = next;
            }
            return result;
        }

        private List<Map<String, ObjectNode>> term() {
            if (peek('(')) {
                position++;
                List<List<Map<String, ObjectNode>>> parts = new ArrayList<>();
                parts.add(product());
                while (peek(',')) {
                    position++;
                    parts.add(product());
                }
                if (!peek(')')) {
                    throw error();
                }
                position++;
                int size = parts.get(0).size();
                for (List<Map<String, ObjectNode>> part : parts) {
                    if (part.size() != size) {
                        throw invalid("The associated parameters of step " + step + " must have ranges of the same "
                                + "length.");
                    }
                }
                List<Map<String, ObjectNode>> result = new ArrayList<>();
                for (int index = 0; index < size; index++) {
                    Map<String, ObjectNode> merged = new LinkedHashMap<>();
                    for (List<Map<String, ObjectNode>> part : parts) {
                        merged.putAll(part.get(index));
                    }
                    result.add(merged);
                }
                return result;
            }
            skip();
            int start = position;
            while (position < text.length() && (Character.isLetterOrDigit(text.charAt(position))
                    || text.charAt(position) == '_')) {
                position++;
            }
            String name = text.substring(start, position);
            if (!values.containsKey(name) || !used.add(name)) {
                throw invalid("The combination of step " + step + " names " + (name.isEmpty() ? "nothing"
                        : "task parameter " + name) + " where a single, defined task parameter is expected.");
            }
            List<Map<String, ObjectNode>> result = new ArrayList<>();
            for (ObjectNode value : values.get(name)) {
                Map<String, ObjectNode> single = new LinkedHashMap<>();
                single.put(name, value);
                result.add(single);
            }
            return result;
        }

        private boolean peek(char expected) {
            skip();
            return position < text.length() && text.charAt(position) == expected;
        }

        private void skip() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        private RuntimeException error() {
            return invalid("The combination of step " + step + " is not a valid expression: " + text);
        }
    }

    // ---------------------------------------------------------------- host requirements

    /** A step's host requirements as the capabilities Deadline requires of a worker. */
    ObjectNode requiredCapabilities(JsonNode step) {
        ObjectNode capabilities = json.createObjectNode();
        ArrayNode amounts = capabilities.putArray("amounts");
        ArrayNode attributes = capabilities.putArray("attributes");
        JsonNode requirements = step.path("hostRequirements");
        for (JsonNode amount : requirements.path("amounts")) {
            ObjectNode entry = amounts.addObject().put("name", amount.path("name").asText());
            if (amount.has("min")) {
                entry.put("min", amount.get("min").asDouble());
            }
            if (amount.has("max")) {
                entry.put("max", amount.get("max").asDouble());
            }
        }
        for (JsonNode attribute : requirements.path("attributes")) {
            ObjectNode entry = attributes.addObject().put("name", attribute.path("name").asText());
            for (String member : List.of("anyOf", "allOf")) {
                if (attribute.has(member)) {
                    entry.set(member, attribute.get(member).deepCopy());
                }
            }
        }
        return capabilities;
    }

    private static String requireText(JsonNode node, String field, String what) {
        if (!node.hasNonNull(field) || node.get(field).asText().isBlank()) {
            throw invalid(what + " must define " + field + ".");
        }
        return node.get(field).asText();
    }

    private static String identifier(JsonNode node, String field) {
        String name = node.path("name").asText();
        if (!IDENTIFIER.matcher(name).matches()) {
            throw invalid(field + " " + name + " is not a valid identifier.");
        }
        return name;
    }
}
