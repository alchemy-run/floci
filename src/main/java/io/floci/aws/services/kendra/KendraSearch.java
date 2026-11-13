package io.floci.aws.services.kendra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.kendra.KendraAnalyzer.Token;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Kendra's search engine over an index's documents and FAQs.
 *
 * <p>A query is natural language, or uses Kendra's query syntax: {@code "exact phrases"}, {@code AND}, {@code OR},
 * {@code NOT} and parentheses, a trailing {@code *} for any word starting with a prefix, and attribute conditions
 * such as {@code _category:guide} or {@code _created_at:>2024-01-01}. Malformed syntax answers a
 * {@code QUERY_LANGUAGE_INVALID_SYNTAX} warning and searches the words as natural language. A natural-language query
 * matches documents with any of its words that aren't stop words; a thesaurus adds the synonyms of each word (an
 * equivalence line adds them all, an explicit {@code a => b} line replaces a with b), highlighted as
 * {@code THESAURUS_SYNONYM}.
 *
 * <p>Documents are ranked by BM25 over their title (weighted double), body and searchable string attributes, with a
 * bonus when the words occur close together, the relevance tuning of the index's fields (freshness of dates, rank
 * order of numbers, value importance of strings, importance of matching fields), and the click and relevance
 * feedback given for the same query. Each result's confidence comes from how many of the query's words it matches
 * and its score against the best. A question-like query (one ending in {@code ?} or starting with a question word)
 * also gets an {@code ANSWER}: the best sentence with the words around the matches marked as the top answer, or a
 * table excerpt from a CSV or Excel document whose row matches. FAQ questions close to the query answer as
 * {@code QUESTION_ANSWER} results.
 */
final class KendraSearch {

    static final double K1 = 1.2;
    static final double B = 0.75;
    static final int EXCERPT_LENGTH = 250;
    static final int PASSAGE_WORDS = 200;

    /** A document as the engine reads it. */
    static final class Entry {
        final ObjectNode document;
        final String id;
        final String title;
        final String text;
        final ObjectNode attributes;
        final List<List<String>> table;
        final List<Token> titleTokens;
        final List<Token> bodyTokens;
        final Map<String, List<Token>> fieldTokens = new HashMap<>();

        Entry(ObjectNode document, String id, String title, String text, ObjectNode attributes,
              List<List<String>> table) {
            this.document = document;
            this.id = id;
            this.title = title;
            this.text = text == null ? "" : text;
            this.attributes = attributes;
            this.table = table;
            this.titleTokens = KendraAnalyzer.tokens(title);
            this.bodyTokens = KendraAnalyzer.tokens(this.text);
        }
    }

    /** An FAQ's question and answer. */
    record Faq(String faqId, String id, String question, String answer, ObjectNode attributes, String uri) {
    }

    /** A synonym rule's alternatives for a stem: phrases of stems, and whether the stem itself still matches. */
    record Synonyms(Map<String, List<List<String>>> alternatives, Set<String> replaced) {
        static Synonyms none() {
            return new Synonyms(Map.of(), Set.of());
        }
    }

    // ---------------------------------------------------------------- query syntax

    sealed interface Node permits Term, Phrase, Field, And, Or, Not {
    }

    /** A word: its stem and form, whether it's a prefix, and its synonyms (phrases of stems). */
    record Term(String stem, String word, boolean prefix, List<List<String>> synonyms, boolean keepSelf,
                int begin, int end) implements Node {
    }

    record Phrase(List<String> stems, int begin, int end) implements Node {
    }

    record Field(String key, String op, String value) implements Node {
    }

    record And(List<Node> nodes) implements Node {
    }

    record Or(List<Node> nodes) implements Node {
    }

    record Not(Node node) implements Node {
    }

    /** A parsed query, whether it used the query syntax, and whether that syntax was malformed. */
    record Parsed(Node root, boolean advanced, boolean invalid, List<Term> terms, List<Phrase> phrases) {
    }

    private record Lexeme(String kind, String text, int begin, int end) {
    }

    static Parsed parse(String query, Synonyms synonyms) {
        List<Lexeme> lexemes = new ArrayList<>();
        boolean invalid = false;
        boolean advanced = false;
        int i = 0;
        while (i < query.length()) {
            char c = query.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '(' || c == ')') {
                lexemes.add(new Lexeme(String.valueOf(c), String.valueOf(c), i, i + 1));
                advanced = true;
                i++;
            } else if (c == '"') {
                int close = query.indexOf('"', i + 1);
                if (close < 0) {
                    invalid = true;
                    close = query.length();
                }
                lexemes.add(new Lexeme("phrase", query.substring(i + 1, close), i + 1, close));
                advanced = true;
                i = Math.min(query.length(), close + 1);
            } else {
                int start = i;
                while (i < query.length() && !Character.isWhitespace(query.charAt(i)) && query.charAt(i) != '('
                        && query.charAt(i) != ')') {
                    if (query.charAt(i) == '"') {
                        int close = query.indexOf('"', i + 1);
                        i = close < 0 ? query.length() : close + 1;
                    } else {
                        i++;
                    }
                }
                String word = query.substring(start, i);
                if (word.equals("AND") || word.equals("OR") || word.equals("NOT")) {
                    lexemes.add(new Lexeme(word, word, start, i));
                    advanced = true;
                } else if (word.matches("_?[A-Za-z][A-Za-z0-9_\\-]*:.+")) {
                    lexemes.add(new Lexeme("field", word, start, i));
                    advanced = true;
                } else {
                    if (word.endsWith("*") && word.length() > 1) {
                        advanced = true;
                    }
                    lexemes.add(new Lexeme("word", word, start, i));
                }
            }
        }
        List<Term> terms = new ArrayList<>();
        List<Phrase> phrases = new ArrayList<>();
        Node root = null;
        if (advanced && !invalid) {
            try {
                int[] at = {0};
                root = or(lexemes, at, synonyms, terms, phrases);
                if (at[0] != lexemes.size()) {
                    throw new IllegalArgumentException("unexpected " + lexemes.get(at[0]).text());
                }
            } catch (IllegalArgumentException e) {
                invalid = true;
                root = null;
                terms.clear();
                phrases.clear();
            }
        }
        if (root == null) {
            List<Node> words = new ArrayList<>();
            for (Token token : KendraAnalyzer.tokens(query)) {
                Term term = term(token.word(), token.start(), token.end(), false, synonyms);
                terms.add(term);
                words.add(term);
            }
            List<Node> content = words.stream().filter(n -> !KendraAnalyzer.STOP_WORDS.contains(((Term) n).word()))
                    .toList();
            root = new Or(content.isEmpty() ? words : content);
            advanced = false;
        }
        return new Parsed(root, advanced, invalid, terms, phrases);
    }

    private static Term term(String word, int begin, int end, boolean prefix, Synonyms synonyms) {
        String folded = KendraAnalyzer.fold(word);
        String stem = prefix ? folded : KendraAnalyzer.stem(folded);
        List<List<String>> alternatives = prefix ? List.of() : synonyms.alternatives().getOrDefault(stem, List.of());
        return new Term(stem, folded, prefix, alternatives, prefix || !synonyms.replaced().contains(stem), begin, end);
    }

    private static Node or(List<Lexeme> lx, int[] at, Synonyms syn, List<Term> terms, List<Phrase> phrases) {
        List<Node> nodes = new ArrayList<>();
        nodes.add(and(lx, at, syn, terms, phrases));
        while (at[0] < lx.size()) {
            String kind = lx.get(at[0]).kind();
            if (kind.equals(")")) {
                break;
            }
            if (kind.equals("OR")) {
                at[0]++;
            }
            nodes.add(and(lx, at, syn, terms, phrases));
        }
        return nodes.size() == 1 ? nodes.get(0) : new Or(nodes);
    }

    private static Node and(List<Lexeme> lx, int[] at, Synonyms syn, List<Term> terms, List<Phrase> phrases) {
        List<Node> nodes = new ArrayList<>();
        nodes.add(unary(lx, at, syn, terms, phrases));
        while (at[0] < lx.size() && lx.get(at[0]).kind().equals("AND")) {
            at[0]++;
            nodes.add(unary(lx, at, syn, terms, phrases));
        }
        return nodes.size() == 1 ? nodes.get(0) : new And(nodes);
    }

    private static Node unary(List<Lexeme> lx, int[] at, Synonyms syn, List<Term> terms, List<Phrase> phrases) {
        if (at[0] >= lx.size()) {
            throw new IllegalArgumentException("missing operand");
        }
        Lexeme lexeme = lx.get(at[0]++);
        switch (lexeme.kind()) {
            case "NOT" -> {
                return new Not(unary(lx, at, syn, terms, phrases));
            }
            case "(" -> {
                Node inner = or(lx, at, syn, terms, phrases);
                if (at[0] >= lx.size() || !lx.get(at[0]).kind().equals(")")) {
                    throw new IllegalArgumentException("unbalanced parenthesis");
                }
                at[0]++;
                return inner;
            }
            case "phrase" -> {
                List<String> stems = KendraAnalyzer.tokens(lexeme.text()).stream().map(Token::stem).toList();
                if (stems.isEmpty()) {
                    throw new IllegalArgumentException("empty phrase");
                }
                Phrase phrase = new Phrase(stems, lexeme.begin(), lexeme.end());
                phrases.add(phrase);
                return phrase;
            }
            case "field" -> {
                String text = lexeme.text();
                int colon = text.indexOf(':');
                String key = text.substring(0, colon);
                String rest = text.substring(colon + 1);
                String op = ":";
                for (String candidate : List.of(">=", "<=", ">", "<")) {
                    if (rest.startsWith(candidate)) {
                        op = candidate;
                        rest = rest.substring(candidate.length());
                        break;
                    }
                }
                if (rest.startsWith("\"") && rest.endsWith("\"") && rest.length() >= 2) {
                    rest = rest.substring(1, rest.length() - 1);
                }
                if (rest.isEmpty()) {
                    throw new IllegalArgumentException("empty field value");
                }
                return new Field(key, op, rest);
            }
            case "word" -> {
                String word = lexeme.text();
                boolean prefix = word.endsWith("*");
                String bare = prefix ? word.substring(0, word.length() - 1) : word;
                List<Token> tokens = KendraAnalyzer.tokens(bare);
                if (tokens.isEmpty()) {
                    throw new IllegalArgumentException("empty word");
                }
                List<Node> parts = new ArrayList<>();
                for (int k = 0; k < tokens.size(); k++) {
                    Token token = tokens.get(k);
                    Term term = term(token.word(), lexeme.begin() + token.start(), lexeme.begin() + token.end(),
                            prefix && k == tokens.size() - 1, syn);
                    terms.add(term);
                    parts.add(term);
                }
                return parts.size() == 1 ? parts.get(0) : new And(parts);
            }
            default -> throw new IllegalArgumentException("unexpected " + lexeme.text());
        }
    }

    // ---------------------------------------------------------------- matching

    /** The field of a document a term can match: title, body or a searchable attribute. */
    static final class Fields {
        final List<List<Token>> fields = new ArrayList<>();
        final List<Double> boosts = new ArrayList<>();
    }

    static boolean termIn(Term term, List<Token> tokens) {
        return countTerm(term, tokens) > 0;
    }

    static int countTerm(Term term, List<Token> tokens) {
        int count = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (term.keepSelf() && (term.prefix() ? token.word().startsWith(term.stem())
                    : token.stem().equals(term.stem()))) {
                count++;
                continue;
            }
            for (List<String> synonym : term.synonyms()) {
                if (phraseAt(synonym, tokens, i)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    static boolean phraseAt(List<String> stems, List<Token> tokens, int at) {
        if (at + stems.size() > tokens.size()) {
            return false;
        }
        for (int k = 0; k < stems.size(); k++) {
            if (!tokens.get(at + k).stem().equals(stems.get(k))) {
                return false;
            }
        }
        return true;
    }

    static int countPhrase(List<String> stems, List<Token> tokens) {
        int count = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (phraseAt(stems, tokens, i)) {
                count++;
            }
        }
        return count;
    }

    static List<List<Token>> searchable(Entry entry, Map<String, ObjectNode> fieldConfigs) {
        List<List<Token>> fields = new ArrayList<>();
        fields.add(entry.titleTokens);
        fields.add(entry.bodyTokens);
        entry.attributes.fields().forEachRemaining(e -> {
            ObjectNode config = fieldConfigs.get(e.getKey());
            if (config != null && config.path("Search").path("Searchable").asBoolean(false)
                    && !e.getKey().equals("_document_title")) {
                fields.add(entry.fieldTokens.computeIfAbsent(e.getKey(), k -> KendraAnalyzer.tokens(
                        String.join(" ", stringValues(e.getValue())))));
            }
        });
        return fields;
    }

    static boolean matches(Node node, Entry entry, Map<String, ObjectNode> fieldConfigs) {
        return switch (node) {
            case Term t -> searchable(entry, fieldConfigs).stream().anyMatch(f -> termIn(t, f));
            case Phrase p -> searchable(entry, fieldConfigs).stream().anyMatch(f -> countPhrase(p.stems(), f) > 0);
            case Field f -> fieldMatches(f, entry);
            case And a -> a.nodes().stream().allMatch(n -> matches(n, entry, fieldConfigs));
            case Or o -> o.nodes().stream().anyMatch(n -> matches(n, entry, fieldConfigs));
            case Not n -> !matches(n.node(), entry, fieldConfigs);
        };
    }

    private static boolean fieldMatches(Field field, Entry entry) {
        JsonNode value = field.key().equals("_document_title")
                ? ObjectMapperHolder.MAPPER.createObjectNode().put("StringValue", entry.title == null ? "" : entry.title)
                : entry.attributes.get(field.key());
        if (value == null) {
            return false;
        }
        if (value.has("LongValue") || value.has("DateValue")) {
            double actual = value.has("LongValue") ? value.get("LongValue").asDouble()
                    : value.get("DateValue").asDouble();
            Double wanted = value.has("DateValue") ? dateOf(field.value()) : numberOf(field.value());
            if (wanted == null) {
                return false;
            }
            return compare(Double.compare(actual, wanted), field.op());
        }
        for (String s : stringValues(value)) {
            if (field.op().equals(":")) {
                List<String> want = KendraAnalyzer.terms(field.value());
                List<String> have = KendraAnalyzer.terms(s);
                if (s.equalsIgnoreCase(field.value()) || !want.isEmpty() && have.containsAll(want)) {
                    return true;
                }
            } else if (compare(s.compareToIgnoreCase(field.value()), field.op())) {
                return true;
            }
        }
        return false;
    }

    private static boolean compare(int c, String op) {
        return switch (op) {
            case ">" -> c > 0;
            case ">=" -> c >= 0;
            case "<" -> c < 0;
            case "<=" -> c <= 0;
            default -> c == 0;
        };
    }

    private static Double numberOf(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A date written as ISO-8601 (a day, or a day and time) or epoch seconds, as epoch seconds. */
    static Double dateOf(String text) {
        try {
            if (text.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return (double) LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC)
                        .toEpochSecond();
            }
            return Instant.parse(text).toEpochMilli() / 1000.0;
        } catch (RuntimeException e) {
            return numberOf(text);
        }
    }

    static List<String> stringValues(JsonNode value) {
        List<String> values = new ArrayList<>();
        if (value == null) {
            return values;
        }
        if (value.hasNonNull("StringValue")) {
            values.add(value.get("StringValue").asText());
        }
        if (value.has("StringListValue")) {
            value.get("StringListValue").forEach(v -> values.add(v.asText()));
        }
        return values;
    }

    // ---------------------------------------------------------------- scoring

    /** A scored document and how many of the query's words it matched. */
    record Hit(Entry entry, double score, double coverage) {
    }

    /** Every positive word of a query: its terms and phrases outside NOT. */
    private static void positives(Node node, List<Node> out) {
        switch (node) {
            case Term t -> out.add(t);
            case Phrase p -> out.add(p);
            case Field ignored -> {
            }
            case And a -> a.nodes().forEach(n -> positives(n, out));
            case Or o -> o.nodes().forEach(n -> positives(n, out));
            case Not ignored -> {
            }
        }
    }

    static List<Hit> score(Parsed parsed, List<Entry> entries, Map<String, ObjectNode> fieldConfigs,
                           List<ObjectNode> relevance, Map<String, Double> feedback) {
        List<Node> words = new ArrayList<>();
        positives(parsed.root(), words);
        List<Node> scoring = words.stream().filter(n -> !(n instanceof Term t)
                || !KendraAnalyzer.STOP_WORDS.contains(t.word())).toList();
        if (scoring.isEmpty()) {
            scoring = words;
        }
        int n = Math.max(1, entries.size());
        double[] averages = new double[3];
        for (Entry e : entries) {
            averages[0] += e.titleTokens.size();
            averages[1] += e.bodyTokens.size();
        }
        averages[0] = Math.max(1, averages[0] / n);
        averages[1] = Math.max(1, averages[1] / n);
        Map<Node, Double> idf = new HashMap<>();
        for (Node word : scoring) {
            int df = 0;
            for (Entry e : entries) {
                if (matches(word, e, fieldConfigs)) {
                    df++;
                }
            }
            idf.put(word, Math.log(1 + (n - df + 0.5) / (df + 0.5)));
        }
        List<Hit> hits = new ArrayList<>();
        for (Entry entry : entries) {
            if (!matches(parsed.root(), entry, fieldConfigs)) {
                continue;
            }
            double score = 0;
            int matched = 0;
            List<List<Token>> fields = searchable(entry, fieldConfigs);
            for (Node word : scoring) {
                double wordScore = 0;
                for (int f = 0; f < fields.size(); f++) {
                    List<Token> tokens = fields.get(f);
                    int tf = word instanceof Term t ? countTerm(t, tokens) : countPhrase(((Phrase) word).stems(), tokens);
                    if (tf == 0) {
                        continue;
                    }
                    double average = f == 0 ? averages[0] : f == 1 ? averages[1] : 4;
                    double norm = tf * (K1 + 1) / (tf + K1 * (1 - B + B * tokens.size() / average));
                    double boost = f == 0 ? 2.0 : f == 1 ? 1.0 : 1.5;
                    wordScore += boost * norm * fieldImportance(entry, f, relevance, fieldConfigs);
                }
                if (wordScore > 0) {
                    matched++;
                    double weight = word instanceof Phrase p ? 1.0 + 0.5 * p.stems().size() : 1.0;
                    score += idf.get(word) * wordScore * weight;
                }
            }
            if (score == 0 && !scoring.isEmpty() && !parsed.advanced()) {
                continue;
            }
            if (scoring.isEmpty()) {
                score = 1;
            }
            score *= proximity(scoring, entry.bodyTokens);
            score *= tuning(entry, relevance);
            score *= feedback.getOrDefault(entry.id, 1.0);
            double coverage = scoring.isEmpty() ? 1 : (double) matched / scoring.size();
            hits.add(new Hit(entry, score, coverage));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(h -> h.entry().id));
        return hits;
    }

    /** The importance of the field a word matched in, for relevance tuning of searchable attributes. */
    private static double fieldImportance(Entry entry, int field, List<ObjectNode> relevance,
                                          Map<String, ObjectNode> fieldConfigs) {
        String name = field == 0 ? "_document_title" : field == 1 ? "_document_body" : null;
        if (name == null) {
            return 1.0;
        }
        for (ObjectNode config : relevance) {
            if (name.equals(config.path("Name").asText()) && config.path("Relevance").has("Importance")) {
                return 0.5 + config.path("Relevance").path("Importance").asInt(1) / 10.0;
            }
        }
        return 1.0;
    }

    /** A bonus when every scoring word occurs within a few words of the others. */
    private static double proximity(List<Node> words, List<Token> tokens) {
        List<Term> terms = words.stream().filter(Term.class::isInstance).map(Term.class::cast).toList();
        if (terms.size() < 2) {
            return 1.0;
        }
        int window = terms.size() + 4;
        Map<Term, Integer> last = new HashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            for (Term term : terms) {
                if (termIn(term, List.of(tokens.get(i)))) {
                    last.put(term, i);
                }
            }
            if (last.size() == terms.size()) {
                int min = last.values().stream().min(Integer::compare).orElse(i);
                if (i - min < window) {
                    return 1.3;
                }
            }
        }
        return 1.0;
    }

    /** The index's (or query's) relevance tuning of a document's attribute values. */
    private static double tuning(Entry entry, List<ObjectNode> relevance) {
        double factor = 1.0;
        double now = Instant.now().toEpochMilli() / 1000.0;
        for (ObjectNode config : relevance) {
            JsonNode tuning = config.path("Relevance");
            String name = config.path("Name").asText();
            JsonNode value = entry.attributes.get(name);
            if (value == null || tuning.isMissingNode()) {
                continue;
            }
            double importance = tuning.path("Importance").asInt(1) / 10.0;
            if (tuning.path("Freshness").asBoolean(false) && value.has("DateValue")) {
                double seconds = durationSeconds(tuning.path("Duration").asText("25920000s"));
                double age = Math.max(0, now - value.get("DateValue").asDouble());
                factor *= 1 + importance * Math.max(0, 1 - age / seconds);
            } else if (tuning.has("ValueImportanceMap")) {
                for (String s : stringValues(value)) {
                    JsonNode weight = tuning.get("ValueImportanceMap").get(s);
                    if (weight != null) {
                        factor *= 1 + weight.asInt() / 10.0;
                    }
                }
            } else if (tuning.has("RankOrder") && value.has("LongValue")) {
                double v = value.get("LongValue").asDouble();
                double scaled = v / (1 + Math.abs(v));
                factor *= 1 + importance * ("ASCENDING".equals(tuning.get("RankOrder").asText()) ? 1 - scaled
                        : scaled) * 0.5;
            }
        }
        return factor;
    }

    static double durationSeconds(String duration) {
        try {
            return Math.max(1, Double.parseDouble(duration.replaceAll("[^0-9.]", "")));
        } catch (NumberFormatException e) {
            return 25_920_000;
        }
    }

    static String confidence(Hit hit, double best) {
        double relative = best <= 0 ? 0 : hit.score() / best;
        double mix = 0.6 * hit.coverage() + 0.4 * relative;
        return mix >= 0.85 ? "VERY_HIGH" : mix >= 0.65 ? "HIGH" : mix >= 0.4 ? "MEDIUM" : "LOW";
    }

    // ---------------------------------------------------------------- excerpts

    /** Where a query's words occur in a text, and whether each is a synonym. */
    record Mark(int begin, int end, boolean synonym) {
    }

    static List<Mark> marks(List<Token> tokens, Parsed parsed) {
        List<Mark> marks = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            boolean marked = false;
            for (Term term : parsed.terms()) {
                if (KendraAnalyzer.STOP_WORDS.contains(term.word()) && parsed.terms().size() > 1) {
                    continue;
                }
                if (term.keepSelf() && (term.prefix() ? token.word().startsWith(term.stem())
                        : token.stem().equals(term.stem()))) {
                    marks.add(new Mark(token.start(), token.end(), false));
                    marked = true;
                    break;
                }
                for (List<String> synonym : term.synonyms()) {
                    if (phraseAt(synonym, tokens, i)) {
                        marks.add(new Mark(token.start(), tokens.get(i + synonym.size() - 1).end(), true));
                        marked = true;
                        break;
                    }
                }
                if (marked) {
                    break;
                }
            }
            if (!marked) {
                for (Phrase phrase : parsed.phrases()) {
                    if (phraseAt(phrase.stems(), tokens, i)) {
                        marks.add(new Mark(token.start(), tokens.get(i + phrase.stems().size() - 1).end(), false));
                        break;
                    }
                }
            }
        }
        return marks;
    }

    /** Text with highlights at each mark that falls inside {@code [from, to)}, offsets made relative. */
    static ObjectNode highlighted(ObjectMapper mapper, String text, int from, int to, List<Mark> marks,
                                  String prefix, String suffix, Mark top) {
        ObjectNode node = mapper.createObjectNode();
        String body = prefix + text.substring(from, to) + suffix;
        node.put("Text", body);
        ArrayNode highlights = node.putArray("Highlights");
        int shift = prefix.length() - from;
        for (Mark mark : marks) {
            if (mark.begin() >= from && mark.end() <= to) {
                ObjectNode h = highlights.addObject();
                h.put("BeginOffset", mark.begin() + shift).put("EndOffset", mark.end() + shift)
                        .put("TopAnswer", false).put("Type", mark.synonym() ? "THESAURUS_SYNONYM" : "STANDARD");
            }
        }
        if (top != null && top.begin() >= from && top.end() <= to) {
            ObjectNode h = highlights.addObject();
            h.put("BeginOffset", top.begin() + shift).put("EndOffset", top.end() + shift).put("TopAnswer", true)
                    .put("Type", "STANDARD");
        }
        return node;
    }

    /** A sentence of a text: where it is. */
    record Span(int begin, int end) {
    }

    static List<Span> sentences(String text) {
        List<Span> spans = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean stop = c == '\n' || (c == '.' || c == '!' || c == '?')
                    && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1)));
            if (stop) {
                if (i + 1 > start && !text.substring(start, i + 1).isBlank()) {
                    spans.add(trim(text, start, i + 1));
                }
                start = i + 1;
            }
        }
        if (start < text.length() && !text.substring(start).isBlank()) {
            spans.add(trim(text, start, text.length()));
        }
        return spans;
    }

    private static Span trim(String text, int begin, int end) {
        while (begin < end && Character.isWhitespace(text.charAt(begin))) {
            begin++;
        }
        while (end > begin && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return new Span(begin, end);
    }

    /** The sentence holding the most query words (weighted by rarity), or the first one. */
    static Span bestSentence(String text, List<Mark> marks) {
        List<Span> sentences = sentences(text);
        if (sentences.isEmpty()) {
            return new Span(0, 0);
        }
        Span best = sentences.get(0);
        int bestCount = 0;
        for (Span s : sentences) {
            Set<String> distinct = new HashSet<>();
            for (Mark m : marks) {
                if (m.begin() >= s.begin() && m.end() <= s.end()) {
                    distinct.add(text.substring(m.begin(), m.end()).toLowerCase(Locale.ROOT));
                }
            }
            if (distinct.size() > bestCount) {
                bestCount = distinct.size();
                best = s;
            }
        }
        return best;
    }

    static ObjectNode excerpt(ObjectMapper mapper, String text, List<Mark> marks) {
        Span best = bestSentence(text, marks);
        int from = best.begin();
        int to = Math.min(text.length(), from + EXCERPT_LENGTH);
        if (to < text.length()) {
            int space = text.lastIndexOf(' ', to);
            if (space > from + EXCERPT_LENGTH / 2) {
                to = space;
            }
        }
        return highlighted(mapper, text, from, to, marks, from > 0 ? "..." : "", to < text.length() ? "..." : "",
                null);
    }

    // ---------------------------------------------------------------- answers

    /** An extracted answer: its sentence and the words that answer the question. */
    record Answer(Hit hit, Span sentence, Mark top, double strength) {
    }

    static Answer answer(Parsed parsed, List<Hit> hits) {
        List<Term> content = parsed.terms().stream().filter(t -> !KendraAnalyzer.STOP_WORDS.contains(t.word()))
                .toList();
        if (content.isEmpty()) {
            return null;
        }
        Answer best = null;
        for (Hit hit : hits.subList(0, Math.min(5, hits.size()))) {
            String text = hit.entry().text;
            List<Token> tokens = hit.entry().bodyTokens;
            for (Span sentence : sentences(text)) {
                List<Token> inside = tokens.stream().filter(t -> t.start() >= sentence.begin()
                        && t.end() <= sentence.end()).toList();
                Set<Term> found = new LinkedHashSet<>();
                int lastMatch = -1;
                int firstMatch = -1;
                for (int i = 0; i < inside.size(); i++) {
                    for (Term term : content) {
                        if (termIn(term, List.of(inside.get(i)))) {
                            found.add(term);
                            lastMatch = i;
                            if (firstMatch < 0) {
                                firstMatch = i;
                            }
                        }
                    }
                }
                double strength = (double) found.size() / content.size();
                if (strength < 0.6 || inside.isEmpty()) {
                    continue;
                }
                Mark top = answerSpan(inside, content, lastMatch, firstMatch);
                if (top == null) {
                    continue;
                }
                if (best == null || strength > best.strength()) {
                    best = new Answer(hit, sentence, top, strength);
                }
            }
        }
        return best;
    }

    /** The run of words after the last match (or before the first) that aren't the question's own words. */
    private static Mark answerSpan(List<Token> inside, List<Term> content, int lastMatch, int firstMatch) {
        Predicate<Token> isQuestionWord = t -> content.stream().anyMatch(term -> termIn(term, List.of(t)));
        int start = lastMatch + 1;
        while (start < inside.size() && inside.get(start).stop()) {
            start++;
        }
        int end = start;
        while (end < inside.size() && !isQuestionWord.test(inside.get(end)) && end - start < 8) {
            end++;
        }
        if (end > start) {
            return new Mark(inside.get(start).start(), inside.get(end - 1).end(), false);
        }
        end = firstMatch - 1;
        while (end >= 0 && inside.get(end).stop()) {
            end--;
        }
        start = end;
        while (start > 0 && !isQuestionWord.test(inside.get(start - 1)) && end - start < 7
                && !inside.get(start - 1).stop()) {
            start--;
        }
        if (end >= 0 && start >= 0 && start <= end) {
            return new Mark(inside.get(start).start(), inside.get(end).end(), false);
        }
        return null;
    }

    /** The best-matching rows of a table, with the header first, or null when no row matches. */
    record TableAnswer(Hit hit, List<String> header, List<List<String>> rows, int topColumn, int totalRows) {
    }

    static TableAnswer table(Parsed parsed, List<Hit> hits) {
        List<Term> content = parsed.terms().stream().filter(t -> !KendraAnalyzer.STOP_WORDS.contains(t.word()))
                .toList();
        if (content.isEmpty()) {
            return null;
        }
        for (Hit hit : hits.subList(0, Math.min(5, hits.size()))) {
            List<List<String>> table = hit.entry().table;
            if (table == null || table.size() < 2) {
                continue;
            }
            List<String> header = table.get(0);
            int topColumn = -1;
            for (int c = 0; c < header.size(); c++) {
                List<Token> cell = KendraAnalyzer.tokens(header.get(c));
                if (content.stream().anyMatch(t -> termIn(t, cell))) {
                    topColumn = c;
                }
            }
            List<List<String>> matched = new ArrayList<>();
            int bestCount = 0;
            for (List<String> row : table.subList(1, table.size())) {
                int count = 0;
                for (String cell : row) {
                    List<Token> tokens = KendraAnalyzer.tokens(cell);
                    count += (int) content.stream().filter(t -> termIn(t, tokens)).count();
                }
                if (count > bestCount) {
                    bestCount = count;
                    matched.clear();
                    matched.add(row);
                } else if (count == bestCount && count > 0 && matched.size() < 5) {
                    matched.add(row);
                }
            }
            if (bestCount > 0) {
                return new TableAnswer(hit, header, matched, topColumn, table.size() - 1);
            }
        }
        return null;
    }

    static ObjectNode tableExcerpt(ObjectMapper mapper, TableAnswer answer, Parsed parsed) {
        ObjectNode excerpt = mapper.createObjectNode();
        ArrayNode rows = excerpt.putArray("Rows");
        ArrayNode headerCells = rows.addObject().putArray("Cells");
        for (String cell : answer.header()) {
            headerCells.addObject().put("Value", cell.isEmpty() ? " " : cell).put("TopAnswer", false)
                    .put("Highlighted", false).put("Header", true);
        }
        for (int r = 0; r < answer.rows().size(); r++) {
            ArrayNode cells = rows.addObject().putArray("Cells");
            List<String> row = answer.rows().get(r);
            for (int c = 0; c < row.size(); c++) {
                List<Token> tokens = KendraAnalyzer.tokens(row.get(c));
                boolean hit = parsed.terms().stream().anyMatch(t -> !KendraAnalyzer.STOP_WORDS.contains(t.word())
                        && termIn(t, tokens));
                cells.addObject().put("Value", row.get(c).isEmpty() ? " " : row.get(c))
                        .put("TopAnswer", r == 0 && c == answer.topColumn()).put("Highlighted", hit)
                        .put("Header", false);
            }
        }
        excerpt.put("TotalNumberOfRows", answer.totalRows());
        return excerpt;
    }

    /** FAQs whose question is close to the query, best first. */
    static List<Map.Entry<Faq, Double>> faqs(String query, List<Faq> faqs) {
        Set<String> q = new LinkedHashSet<>(KendraAnalyzer.terms(query));
        List<Map.Entry<Faq, Double>> found = new ArrayList<>();
        if (q.isEmpty()) {
            return found;
        }
        for (Faq faq : faqs) {
            Set<String> question = new LinkedHashSet<>(KendraAnalyzer.terms(faq.question()));
            if (question.isEmpty()) {
                continue;
            }
            Set<String> shared = new HashSet<>(q);
            shared.retainAll(question);
            double similarity = 2.0 * shared.size() / (q.size() + question.size());
            if (similarity >= 0.5) {
                found.add(Map.entry(faq, similarity));
            }
        }
        found.sort(Map.Entry.<Faq, Double>comparingByValue().reversed());
        return found;
    }

    // ---------------------------------------------------------------- filters

    /** Whether a document passes an attribute filter; {@code _user_id} and {@code _group_ids} always pass. */
    static boolean filter(JsonNode filter, Entry entry) {
        if (filter == null || filter.isNull() || filter.isEmpty()) {
            return true;
        }
        if (filter.has("AndAllFilters")) {
            for (JsonNode f : filter.get("AndAllFilters")) {
                if (!filter(f, entry)) {
                    return false;
                }
            }
        }
        if (filter.has("OrAllFilters")) {
            boolean any = false;
            for (JsonNode f : filter.get("OrAllFilters")) {
                any |= filter(f, entry);
            }
            if (!any) {
                return false;
            }
        }
        if (filter.has("NotFilter") && filter(filter.get("NotFilter"), entry)) {
            return false;
        }
        for (String op : List.of("EqualsTo", "ContainsAll", "ContainsAny", "GreaterThan", "GreaterThanOrEquals",
                "LessThan", "LessThanOrEquals")) {
            JsonNode attribute = filter.get(op);
            if (attribute != null && !leaf(op, attribute, entry)) {
                return false;
            }
        }
        return true;
    }

    private static boolean leaf(String op, JsonNode attribute, Entry entry) {
        String key = attribute.path("Key").asText();
        if (key.equals("_user_id") || key.equals("_group_ids")) {
            return true;
        }
        JsonNode wanted = attribute.path("Value");
        JsonNode actual = key.equals("_document_title")
                ? ObjectMapperHolder.MAPPER.createObjectNode().put("StringValue", entry.title == null ? "" : entry.title)
                : entry.attributes.get(key);
        if (actual == null) {
            return false;
        }
        switch (op) {
            case "EqualsTo" -> {
                if (wanted.has("StringListValue")) {
                    return new HashSet<>(stringValues(actual)).equals(new HashSet<>(stringValues(wanted)));
                }
                if (wanted.has("StringValue")) {
                    return stringValues(actual).contains(wanted.get("StringValue").asText());
                }
                return number(actual) != null && number(actual).equals(number(wanted));
            }
            case "ContainsAll" -> {
                return new HashSet<>(stringValues(actual)).containsAll(stringValues(wanted));
            }
            case "ContainsAny" -> {
                Set<String> have = new HashSet<>(stringValues(actual));
                return stringValues(wanted).stream().anyMatch(have::contains);
            }
            default -> {
                Double a = number(actual);
                Double w = number(wanted);
                if (a == null || w == null) {
                    return false;
                }
                int c = Double.compare(a, w);
                return switch (op) {
                    case "GreaterThan" -> c > 0;
                    case "GreaterThanOrEquals" -> c >= 0;
                    case "LessThan" -> c < 0;
                    default -> c <= 0;
                };
            }
        }
    }

    private static Double number(JsonNode value) {
        if (value.has("LongValue")) {
            return value.get("LongValue").asDouble();
        }
        if (value.has("DateValue")) {
            JsonNode date = value.get("DateValue");
            return date.isNumber() ? date.asDouble() : dateOf(date.asText());
        }
        return null;
    }

    /** Whether a filter names an attribute anywhere in it. */
    static boolean names(JsonNode filter, String key) {
        if (filter == null || filter.isNull()) {
            return false;
        }
        for (String op : List.of("EqualsTo", "ContainsAll", "ContainsAny", "GreaterThan", "GreaterThanOrEquals",
                "LessThan", "LessThanOrEquals")) {
            if (filter.has(op) && key.equals(filter.get(op).path("Key").asText())) {
                return true;
            }
        }
        for (String group : List.of("AndAllFilters", "OrAllFilters")) {
            if (filter.has(group)) {
                for (JsonNode f : filter.get(group)) {
                    if (names(f, key)) {
                        return true;
                    }
                }
            }
        }
        return filter.has("NotFilter") && names(filter.get("NotFilter"), key);
    }

    /** The values a filter gives for {@code _user_id} or {@code _group_ids}. */
    static void principals(JsonNode filter, String key, Set<String> out) {
        if (filter == null || filter.isNull()) {
            return;
        }
        for (String op : List.of("EqualsTo", "ContainsAll", "ContainsAny")) {
            if (filter.has(op) && key.equals(filter.get(op).path("Key").asText())) {
                out.addAll(stringValues(filter.get(op).path("Value")));
            }
        }
        for (String group : List.of("AndAllFilters", "OrAllFilters")) {
            if (filter.has(group)) {
                filter.get(group).forEach(f -> principals(f, key, out));
            }
        }
    }

    // ---------------------------------------------------------------- facets

    static ArrayNode facets(ObjectMapper mapper, JsonNode requested, List<Entry> entries,
                            Map<String, ObjectNode> fieldConfigs) {
        ArrayNode results = mapper.createArrayNode();
        if (requested == null) {
            return results;
        }
        for (JsonNode facet : requested) {
            String key = facet.path("DocumentAttributeKey").asText();
            ObjectNode config = fieldConfigs.get(key);
            if (config == null) {
                throw KendraStore.validation("The facet " + key + " is not a document attribute of the index.");
            }
            if (!config.path("Search").path("Facetable").asBoolean(false)) {
                throw KendraStore.validation("The document attribute " + key + " is not facetable.");
            }
            results.add(facet(mapper, facet, key, config.path("Type").asText(), entries, fieldConfigs));
        }
        return results;
    }

    private static ObjectNode facet(ObjectMapper mapper, JsonNode facet, String key, String type, List<Entry> entries,
                                    Map<String, ObjectNode> fieldConfigs) {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        Map<String, List<Entry>> members = new LinkedHashMap<>();
        for (Entry entry : entries) {
            JsonNode value = entry.attributes.get(key);
            if (value == null) {
                continue;
            }
            List<JsonNode> each = new ArrayList<>();
            if (value.has("StringListValue")) {
                value.get("StringListValue").forEach(v -> each.add(mapper.createObjectNode().put("StringValue",
                        v.asText())));
            } else {
                each.add(value);
            }
            for (JsonNode v : each) {
                String id = v.toString();
                values.putIfAbsent(id, v);
                members.computeIfAbsent(id, k -> new ArrayList<>()).add(entry);
            }
        }
        List<String> ordered = new ArrayList<>(values.keySet());
        ordered.sort(Comparator.<String>comparingInt(id -> members.get(id).size()).reversed());
        int max = facet.has("MaxResults") ? facet.get("MaxResults").asInt() : 10;
        if (max > 0 && ordered.size() > max) {
            ordered = ordered.subList(0, max);
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("DocumentAttributeKey", key).put("DocumentAttributeValueType", type);
        ArrayNode pairs = result.putArray("DocumentAttributeValueCountPairs");
        for (String id : ordered) {
            ObjectNode pair = pairs.addObject();
            pair.set("DocumentAttributeValue", values.get(id).deepCopy());
            pair.put("Count", members.get(id).size());
            if (facet.has("Facets") && !facet.get("Facets").isEmpty()) {
                ArrayNode nested = pair.putArray("FacetResults");
                for (JsonNode inner : facet.get("Facets")) {
                    String innerKey = inner.path("DocumentAttributeKey").asText();
                    ObjectNode config = fieldConfigs.get(innerKey);
                    if (config == null || !config.path("Search").path("Facetable").asBoolean(false)) {
                        throw KendraStore.validation("The document attribute " + innerKey + " is not facetable.");
                    }
                    nested.add(facet(mapper, inner, innerKey, config.path("Type").asText(), members.get(id),
                            fieldConfigs));
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- spelling

    static ArrayNode spellCorrections(ObjectMapper mapper, String query, List<Entry> entries) {
        Map<String, Integer> vocabulary = new HashMap<>();
        Set<String> stems = new HashSet<>();
        for (Entry entry : entries) {
            Set<String> seen = new HashSet<>();
            for (List<Token> tokens : List.of(entry.titleTokens, entry.bodyTokens)) {
                for (Token token : tokens) {
                    stems.add(token.stem());
                    if (seen.add(token.word())) {
                        vocabulary.merge(token.word(), 1, Integer::sum);
                    }
                }
            }
        }
        ArrayNode corrections = mapper.createArrayNode();
        StringBuilder suggested = new StringBuilder();
        int cursor = 0;
        for (Token token : KendraAnalyzer.tokens(query)) {
            if (token.stop() || token.word().length() < 3 || stems.contains(token.stem())
                    || !token.word().chars().allMatch(Character::isLetter)) {
                continue;
            }
            int bound = token.word().length() <= 4 ? 1 : 2;
            String best = null;
            int bestDistance = bound + 1;
            int bestFrequency = 0;
            for (Map.Entry<String, Integer> word : vocabulary.entrySet()) {
                int d = KendraAnalyzer.distance(token.word(), word.getKey(), bound);
                if (d <= bound && (d < bestDistance || d == bestDistance && word.getValue() > bestFrequency)) {
                    best = word.getKey();
                    bestDistance = d;
                    bestFrequency = word.getValue();
                }
            }
            if (best != null) {
                suggested.append(query, cursor, token.start()).append(best);
                cursor = token.end();
                corrections.addObject().put("BeginOffset", token.start()).put("EndOffset", token.end())
                        .put("Term", query.substring(token.start(), token.end())).put("CorrectedTerm", best);
            }
        }
        ArrayNode out = mapper.createArrayNode();
        if (!corrections.isEmpty()) {
            suggested.append(query.substring(cursor));
            ObjectNode correction = out.addObject();
            correction.put("SuggestedQueryText", suggested.toString());
            correction.set("Corrections", corrections);
        }
        return out;
    }

    // ---------------------------------------------------------------- passages

    /** A passage of a document for Retrieve. */
    record Passage(Entry entry, int begin, int end, double score, double coverage) {
    }

    static List<Passage> passages(Parsed parsed, List<Hit> hits) {
        List<Passage> passages = new ArrayList<>();
        List<Node> words = new ArrayList<>();
        positives(parsed.root(), words);
        List<Node> scoring = words.stream().filter(n -> !(n instanceof Term t)
                || !KendraAnalyzer.STOP_WORDS.contains(t.word())).toList();
        for (Hit hit : hits) {
            List<Token> tokens = hit.entry().bodyTokens;
            if (tokens.isEmpty()) {
                continue;
            }
            for (int start = 0; start < tokens.size(); start += PASSAGE_WORDS) {
                List<Token> window = tokens.subList(start, Math.min(tokens.size(), start + PASSAGE_WORDS));
                int matched = 0;
                double score = 0;
                for (Node word : scoring) {
                    int tf = word instanceof Term t ? countTerm(t, window) : countPhrase(((Phrase) word).stems(),
                            window);
                    if (tf > 0) {
                        matched++;
                        score += tf * (K1 + 1) / (tf + K1);
                    }
                }
                if (matched == 0 && !scoring.isEmpty()) {
                    continue;
                }
                double coverage = scoring.isEmpty() ? 1 : (double) matched / scoring.size();
                passages.add(new Passage(hit.entry(), window.get(0).start(), window.get(window.size() - 1).end(),
                        score * (1 + coverage) * Math.log(2 + hit.score()), coverage));
            }
        }
        passages.sort(Comparator.comparingDouble(Passage::score).reversed());
        return passages;
    }

    /** A shared mapper for the small value nodes the engine builds. */
    static final class ObjectMapperHolder {
        static final ObjectMapper MAPPER = new ObjectMapper();

        private ObjectMapperHolder() {
        }
    }

    static AwsException invalid(String message) {
        return KendraStore.validation(message);
    }
}
