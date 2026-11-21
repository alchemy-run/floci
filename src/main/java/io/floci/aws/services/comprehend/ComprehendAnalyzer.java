package io.floci.aws.services.comprehend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, rule-based text analysis behind Comprehend's detect
 * operations: no model runs, but the same text always gets the same answer,
 * and the answer follows the text. Tokens carry character offsets, so every
 * result reports {@code BeginOffset} / {@code EndOffset} into the input.
 * <p>
 * The rules are small lexicons and patterns:
 * <ul>
 *   <li>language: the share of each language's function words;</li>
 *   <li>part of speech: closed word classes, suffixes, and the previous tag;</li>
 *   <li>entities: dates, quantities, a gazetteer of places, organization
 *       suffixes, and capitalized names;</li>
 *   <li>key phrases: noun-phrase chunks over the part-of-speech tags;</li>
 *   <li>PII: patterns for emails, phones, card and account numbers, and
 *       names introduced by "my name is" or recognized as people;</li>
 *   <li>sentiment: a polarity lexicon with negation and intensifiers;</li>
 *   <li>toxicity: a lexicon per Comprehend toxicity label.</li>
 * </ul>
 */
final class ComprehendAnalyzer {
    private ComprehendAnalyzer() {}

    /** A word or punctuation mark with its offsets. */
    record Token(String text, int begin, int end) {
        String lower() {
            return text.toLowerCase(Locale.ROOT);
        }

        boolean capitalized() {
            return !text.isEmpty() && Character.isUpperCase(text.codePointAt(0));
        }
    }

    record Tagged(Token token, String tag) {}

    record Entity(String type, String text, int begin, int end, double score) {}

    record Phrase(String text, int begin, int end) {}

    record Sentiment(String label, double positive, double negative, double neutral, double mixed) {}

    private static final Pattern TOKEN = Pattern.compile(
            "[\\p{L}\\p{N}]+(?:['’.@\\-+][\\p{L}\\p{N}]+)*|[^\\s\\p{L}\\p{N}]");

    static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            tokens.add(new Token(matcher.group(), matcher.start(), matcher.end()));
        }
        return tokens;
    }

    // ── Language ───────────────────────────────────────────────────────────

    private static final Map<String, Set<String>> FUNCTION_WORDS = new LinkedHashMap<>();

    static {
        FUNCTION_WORDS.put("en", Set.of("the", "a", "an", "and", "or", "is", "are", "was", "were", "to", "of",
                "in", "on", "it", "this", "that", "with", "for", "my", "i", "you", "he", "she", "we", "they",
                "be", "have", "has", "at", "by", "from", "but", "not", "so", "very"));
        FUNCTION_WORDS.put("es", Set.of("el", "la", "los", "las", "un", "una", "y", "o", "es", "son", "de",
                "en", "que", "por", "para", "con", "mi", "yo", "tu", "muy", "pero", "no", "del", "al", "se"));
        FUNCTION_WORDS.put("fr", Set.of("le", "la", "les", "un", "une", "et", "ou", "est", "sont", "de", "des",
                "en", "que", "pour", "avec", "mon", "je", "tu", "très", "mais", "ne", "pas", "du", "au", "il"));
        FUNCTION_WORDS.put("de", Set.of("der", "die", "das", "ein", "eine", "und", "oder", "ist", "sind",
                "von", "in", "mit", "für", "mein", "ich", "du", "sehr", "aber", "nicht", "den", "dem", "zu"));
        FUNCTION_WORDS.put("it", Set.of("il", "lo", "la", "gli", "le", "un", "una", "e", "o", "è", "sono", "di",
                "in", "che", "per", "con", "mio", "io", "molto", "ma", "non", "del", "della"));
        FUNCTION_WORDS.put("pt", Set.of("o", "a", "os", "as", "um", "uma", "e", "ou", "é", "são", "de", "em",
                "que", "por", "para", "com", "meu", "eu", "muito", "mas", "não", "do", "da"));
    }

    /** Languages ranked by the share of their function words in the text; at least one entry. */
    static List<Map.Entry<String, Double>> languages(String text) {
        Map<String, Integer> hits = new LinkedHashMap<>();
        int words = 0;
        for (Token token : tokenize(text)) {
            if (!Character.isLetter(token.text().codePointAt(0))) {
                continue;
            }
            words++;
            for (Map.Entry<String, Set<String>> language : FUNCTION_WORDS.entrySet()) {
                if (language.getValue().contains(token.lower())) {
                    hits.merge(language.getKey(), 1, Integer::sum);
                }
            }
        }
        // Script decides before function words: CJK, Arabic, Devanagari text.
        String script = scriptLanguage(text);
        List<Map.Entry<String, Double>> ranked = new ArrayList<>();
        if (script != null) {
            ranked.add(Map.entry(script, 0.99));
            return ranked;
        }
        int total = hits.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            ranked.add(Map.entry("en", words == 0 ? 0.5 : 0.75));
            return ranked;
        }
        hits.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> ranked.add(Map.entry(e.getKey(), round(0.5 + 0.49 * e.getValue() / (double) total))));
        return ranked;
    }

    private static String scriptLanguage(String text) {
        int han = 0;
        int kana = 0;
        int hangul = 0;
        int arabic = 0;
        int devanagari = 0;
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            Character.UnicodeScript s = Character.UnicodeScript.of(c);
            switch (s) {
                case HAN -> han++;
                case HIRAGANA, KATAKANA -> kana++;
                case HANGUL -> hangul++;
                case ARABIC -> arabic++;
                case DEVANAGARI -> devanagari++;
                default -> { }
            }
            i += Character.charCount(c);
        }
        if (kana > 0) {
            return "ja";
        }
        if (hangul > 0) {
            return "ko";
        }
        if (han > 0) {
            return "zh";
        }
        if (arabic > 0) {
            return "ar";
        }
        if (devanagari > 0) {
            return "hi";
        }
        return null;
    }

    // ── Part of speech ─────────────────────────────────────────────────────

    private static final Set<String> DET = Set.of("the", "a", "an", "this", "that", "these", "those", "every",
            "each", "some", "any", "no", "all", "both", "another");
    private static final Set<String> PRON = Set.of("i", "you", "he", "she", "it", "we", "they", "me", "him",
            "her", "us", "them", "my", "your", "his", "its", "our", "their", "mine", "yours", "who", "what",
            "which", "myself", "yourself", "itself", "themselves", "someone", "something", "everyone");
    private static final Set<String> ADP = Set.of("on", "in", "at", "to", "of", "for", "with", "from", "by",
            "about", "into", "over", "under", "after", "before", "between", "through", "during", "without",
            "against", "among", "around", "near", "behind", "across", "since", "until", "upon");
    private static final Set<String> CCONJ = Set.of("and", "or", "but", "nor", "yet", "so");
    private static final Set<String> SCONJ = Set.of("because", "although", "though", "if", "while", "when",
            "whereas", "unless", "whether", "than");
    private static final Set<String> AUX = Set.of("is", "are", "was", "were", "be", "been", "being", "am",
            "has", "have", "had", "do", "does", "did", "will", "would", "can", "could", "should", "shall",
            "may", "might", "must");
    private static final Set<String> PART = Set.of("not", "n't", "to", "'s");
    private static final Set<String> INTJ = Set.of("oh", "wow", "hey", "hello", "hi", "ouch", "yes", "yeah");
    private static final Set<String> ADV = Set.of("very", "really", "too", "so", "also", "just", "still",
            "never", "always", "often", "here", "there", "now", "then", "yesterday", "today", "tomorrow",
            "quite", "almost", "already", "soon", "much", "well");
    private static final Set<String> ADJ = Set.of("good", "great", "bad", "new", "old", "big", "small",
            "gorgeous", "disappointing", "wonderful", "terrible", "late", "damaged", "happy", "sad", "two",
            "quarterly", "analyst", "fast", "slow", "beautiful", "awful", "excellent", "poor", "nice",
            "amazing", "horrible", "best", "worst", "first", "last", "high", "low", "long", "short");
    private static final Set<String> VERB = Set.of("sat", "sit", "love", "loved", "like", "liked", "hate",
            "hated", "works", "work", "worked", "moved", "move", "ordered", "order", "exceeded", "exceed",
            "arrived", "arrive", "go", "went", "come", "came", "make", "made", "see", "saw", "get", "got",
            "take", "took", "give", "gave", "know", "knew", "think", "thought", "want", "need", "use",
            "found", "find", "said", "say", "run", "ran", "buy", "bought", "attached", "deploys", "deploy");
    private static final Set<String> NUMBER_WORDS = Set.of("one", "two", "three", "four", "five", "six",
            "seven", "eight", "nine", "ten", "hundred", "thousand", "million", "billion");

    static List<Tagged> tag(String text) {
        List<Token> tokens = tokenize(text);
        List<Tagged> tagged = new ArrayList<>();
        String previous = null;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            String tag = tagOf(token, previous, i == 0 || ".!?".contains(tokens.get(i - 1).text()),
                    i + 1 < tokens.size() ? tokens.get(i + 1) : null);
            tagged.add(new Tagged(token, tag));
            previous = tag;
        }
        return tagged;
    }

    private static String tagOf(Token token, String previous, boolean sentenceStart, Token next) {
        String word = token.lower();
        int first = token.text().codePointAt(0);
        if (!Character.isLetterOrDigit(first)) {
            return token.text().matches("[$€£¥%&+=<>]") ? "SYM" : "PUNCT";
        }
        if (Character.isDigit(first) || NUMBER_WORDS.contains(word)) {
            return "NUM";
        }
        if (DET.contains(word)) {
            return "DET";
        }
        if (PRON.contains(word)) {
            return "PRON";
        }
        if (AUX.contains(word)) {
            return "AUX";
        }
        if (PART.contains(word) && !("to".equals(word) && next != null && DET.contains(next.lower()))) {
            return "to".equals(word) ? "PART" : "PART";
        }
        if (ADP.contains(word)) {
            return "ADP";
        }
        if (CCONJ.contains(word)) {
            return "CCONJ";
        }
        if (SCONJ.contains(word)) {
            return "SCONJ";
        }
        if (INTJ.contains(word)) {
            return "INTJ";
        }
        if (ADV.contains(word) || (word.endsWith("ly") && word.length() > 4)) {
            return "ADV";
        }
        if (token.capitalized() && !sentenceStart) {
            return "PROPN";
        }
        if (ADJ.contains(word)) {
            return "ADJ";
        }
        if (VERB.contains(word)) {
            return "VERB";
        }
        if (sentenceStart && token.capitalized() && next != null && Character.isLetter(next.text().codePointAt(0))
                && !DET.contains(next.lower()) && !AUX.contains(next.lower())) {
            // A capitalized first word followed by a verb-like word reads as a name ("Bob moved").
            if (VERB.contains(next.lower()) || next.lower().endsWith("ed")) {
                return "PROPN";
            }
        }
        if ("DET".equals(previous) || "ADJ".equals(previous) || "ADP".equals(previous)
                || "NUM".equals(previous) || "PRON".equals(previous) && word.length() > 2) {
            return word.endsWith("ous") || word.endsWith("ful") || word.endsWith("ive")
                    || word.endsWith("able") || word.endsWith("al") && !"ADP".equals(previous) ? "ADJ" : "NOUN";
        }
        if ("NOUN".equals(previous) || "PROPN".equals(previous) || "PRON".equals(previous)) {
            return word.endsWith("s") || word.endsWith("ed") || word.endsWith("ing") ? "VERB" : "NOUN";
        }
        if ("AUX".equals(previous)) {
            return word.endsWith("ing") || word.endsWith("ed") ? "VERB" : "ADJ";
        }
        if (word.endsWith("ing") || word.endsWith("ed") || word.endsWith("ize")) {
            return "VERB";
        }
        return "NOUN";
    }

    // ── Entities ───────────────────────────────────────────────────────────

    private static final Set<String> PLACES = Set.of("seattle", "london", "paris", "berlin", "tokyo", "new",
            "york", "san", "francisco", "chicago", "boston", "austin", "denver", "madrid", "rome", "sydney",
            "toronto", "vancouver", "dublin", "amsterdam", "beijing", "shanghai", "mumbai", "delhi", "moscow",
            "america", "usa", "canada", "mexico", "england", "france", "germany", "spain", "italy", "japan",
            "china", "india", "brazil", "australia", "ireland", "europe", "asia", "africa", "washington",
            "california", "texas", "oregon", "florida", "virginia", "portland", "atlanta", "miami");
    private static final Set<String> ORG_WORDS = Set.of("inc", "inc.", "corp", "corp.", "corporation",
            "llc", "ltd", "ltd.", "company", "co", "co.", "group", "university", "bank", "amazon", "google",
            "microsoft", "apple", "meta", "netflix", "aws", "ibm", "oracle", "intel", "tesla");
    private static final Set<String> MONTHS = Set.of("january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december", "monday", "tuesday",
            "wednesday", "thursday", "friday", "saturday", "sunday", "yesterday", "today", "tomorrow");
    private static final Set<String> NOT_NAMES = Set.of("i", "the", "a", "an", "my", "this", "that", "it",
            "we", "you", "they", "he", "she", "subject", "dear", "hello", "hi");

    static List<Entity> entities(String text) {
        List<Tagged> tagged = tag(text);
        List<Entity> entities = new ArrayList<>();
        for (int i = 0; i < tagged.size(); i++) {
            Token token = tagged.get(i).token();
            String word = token.lower();
            if (word.matches("(1[0-9]|20)\\d\\d") || MONTHS.contains(word)) {
                entities.add(new Entity("DATE", token.text(), token.begin(), token.end(), 0.99));
                continue;
            }
            if ("NUM".equals(tagged.get(i).tag())) {
                int end = i;
                if (end + 1 < tagged.size() && "NOUN".equals(tagged.get(end + 1).tag())) {
                    end++;
                }
                Token last = tagged.get(end).token();
                entities.add(new Entity("QUANTITY", text.substring(token.begin(), last.end()),
                        token.begin(), last.end(), 0.97));
                i = end;
                continue;
            }
            if (!token.capitalized() || NOT_NAMES.contains(word) || !Character.isLetter(token.text().codePointAt(0))) {
                continue;
            }
            boolean name = "PROPN".equals(tagged.get(i).tag()) || PLACES.contains(word) || ORG_WORDS.contains(word);
            if (!name) {
                continue;
            }
            int end = i;
            while (end + 1 < tagged.size() && tagged.get(end + 1).token().capitalized()
                    && Character.isLetter(tagged.get(end + 1).token().text().codePointAt(0))
                    && !NOT_NAMES.contains(tagged.get(end + 1).token().lower())) {
                end++;
            }
            Token last = tagged.get(end).token();
            String span = text.substring(token.begin(), last.end());
            String type = "PERSON";
            for (int j = i; j <= end; j++) {
                String part = tagged.get(j).token().lower();
                if (ORG_WORDS.contains(part)) {
                    type = "ORGANIZATION";
                    break;
                }
                if (PLACES.contains(part)) {
                    type = "LOCATION";
                }
            }
            // A lone acronym ("SSN", "API") names a thing, not a person.
            if ("PERSON".equals(type) && end == i && token.text().length() > 1
                    && token.text().equals(token.text().toUpperCase(Locale.ROOT))) {
                type = "OTHER";
            }
            entities.add(new Entity(type, span, token.begin(), last.end(), 0.95));
            i = end;
        }
        return entities;
    }

    // ── Key phrases ────────────────────────────────────────────────────────

    /** Noun-phrase chunks: optional determiner, modifiers, then one or more nouns. */
    static List<Phrase> keyPhrases(String text) {
        List<Tagged> tagged = tag(text);
        List<Phrase> phrases = new ArrayList<>();
        int i = 0;
        while (i < tagged.size()) {
            int start = i;
            if ("DET".equals(tagged.get(i).tag()) || "PRON".equals(tagged.get(i).tag())
                    && Set.of("my", "your", "his", "her", "its", "our", "their").contains(tagged.get(i).token().lower())) {
                i++;
            }
            while (i < tagged.size() && Set.of("ADJ", "NUM").contains(tagged.get(i).tag())) {
                i++;
            }
            int nounStart = i;
            while (i < tagged.size() && Set.of("NOUN", "PROPN").contains(tagged.get(i).tag())) {
                i++;
            }
            if (i > nounStart) {
                Token first = tagged.get(start).token();
                Token last = tagged.get(i - 1).token();
                phrases.add(new Phrase(text.substring(first.begin(), last.end()), first.begin(), last.end()));
            } else {
                i = start + 1;
            }
        }
        return phrases;
    }

    // ── PII ────────────────────────────────────────────────────────────────

    private record PiiPattern(String type, Pattern pattern) {}

    private static final List<PiiPattern> PII_PATTERNS = List.of(
            new PiiPattern("EMAIL", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")),
            new PiiPattern("URL", Pattern.compile("https?://[^\\s]+|www\\.[^\\s]+")),
            new PiiPattern("IP_ADDRESS", Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")),
            new PiiPattern("SSN", Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b")),
            new PiiPattern("CREDIT_DEBIT_NUMBER", Pattern.compile("\\b(?:\\d{4}[- ]?){3}\\d{4}\\b")),
            new PiiPattern("PHONE", Pattern.compile(
                    "(?<!\\d)(?:\\+?1[-. ]?)?\\(?\\d{3}\\)?[-. ]\\d{3}[-. ]\\d{4}(?!\\d)")),
            new PiiPattern("AWS_ACCESS_KEY", Pattern.compile("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b")),
            new PiiPattern("DATE_TIME", Pattern.compile(
                    "\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b|\\b\\d{4}-\\d{2}-\\d{2}\\b")));
    private static final Pattern NAME_INTRO = Pattern.compile(
            "(?i:my name is|i am|i'm|this is)\\s+(\\p{Lu}\\p{L}+(?:\\s+\\p{Lu}\\p{L}+)*)");
    private static final Pattern ADDRESS = Pattern.compile(
            "\\b\\d+\\s+(?:\\p{Lu}\\p{L}+\\s+)+(?:Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Lane|Ln|Drive|Dr)\\b\\.?");

    static List<Entity> pii(String text) {
        List<Entity> found = new ArrayList<>();
        for (PiiPattern pii : PII_PATTERNS) {
            Matcher matcher = pii.pattern().matcher(text);
            while (matcher.find()) {
                if (overlaps(found, matcher.start(), matcher.end())) {
                    continue;
                }
                found.add(new Entity(pii.type(), matcher.group(), matcher.start(), matcher.end(), 0.99));
            }
        }
        Matcher address = ADDRESS.matcher(text);
        while (address.find()) {
            if (!overlaps(found, address.start(), address.end())) {
                found.add(new Entity("ADDRESS", address.group(), address.start(), address.end(), 0.95));
            }
        }
        Matcher intro = NAME_INTRO.matcher(text);
        while (intro.find()) {
            if (!overlaps(found, intro.start(1), intro.end(1))) {
                found.add(new Entity("NAME", intro.group(1), intro.start(1), intro.end(1), 0.99));
            }
        }
        for (Entity entity : entities(text)) {
            if ("PERSON".equals(entity.type()) && !overlaps(found, entity.begin(), entity.end())) {
                found.add(new Entity("NAME", entity.text(), entity.begin(), entity.end(), 0.9));
            }
        }
        found.sort((a, b) -> Integer.compare(a.begin(), b.begin()));
        return found;
    }

    private static boolean overlaps(List<Entity> entities, int begin, int end) {
        return entities.stream().anyMatch(e -> begin < e.end() && e.begin() < end);
    }

    // ── Sentiment ──────────────────────────────────────────────────────────

    private static final Set<String> POSITIVE = Set.of("love", "loved", "loves", "like", "liked", "great",
            "good", "excellent", "amazing", "wonderful", "wonderfully", "fantastic", "gorgeous", "happy",
            "best", "awesome", "perfect", "nice", "beautiful", "delighted", "pleased", "enjoy", "enjoyed",
            "recommend", "superb", "brilliant", "glad", "thanks", "thank", "fast", "easy", "helpful",
            "impressive", "exceeded", "reliable", "fun", "satisfied", "outstanding", "kind", "friendly");
    private static final Set<String> NEGATIVE = Set.of("hate", "hated", "hates", "bad", "terrible",
            "awful", "horrible", "worst", "poor", "disappointing", "disappointed", "late", "damaged",
            "broken", "slow", "angry", "sad", "annoying", "useless", "fail", "failed", "failure", "problem",
            "problems", "issue", "issues", "bug", "bugs", "crash", "crashed", "wrong", "difficult", "ugly",
            "rude", "expensive", "refund", "never", "dislike", "unhappy", "frustrated", "frustrating");
    private static final Set<String> NEGATORS = Set.of("not", "no", "never", "n't", "hardly", "without");
    private static final Set<String> INTENSIFIERS = Set.of("very", "really", "so", "extremely", "incredibly",
            "absolutely", "totally");

    static Sentiment sentiment(String text) {
        double positive = 0;
        double negative = 0;
        List<Token> tokens = tokenize(text);
        for (int i = 0; i < tokens.size(); i++) {
            String word = tokens.get(i).lower();
            boolean pos = POSITIVE.contains(word);
            boolean neg = NEGATIVE.contains(word) && !"never".equals(word);
            if (!pos && !neg) {
                continue;
            }
            double weight = 1;
            boolean negated = false;
            for (int j = Math.max(0, i - 3); j < i; j++) {
                String before = tokens.get(j).lower();
                if (NEGATORS.contains(before) || before.endsWith("n't")) {
                    negated = !negated;
                }
                if (INTENSIFIERS.contains(before)) {
                    weight = 1.5;
                }
            }
            if (pos != negated) {
                positive += weight;
            } else {
                negative += weight;
            }
        }
        if (positive == 0 && negative == 0) {
            return new Sentiment("NEUTRAL", 0.05, 0.03, 0.9, 0.02);
        }
        double total = positive + negative;
        if (positive > 0 && negative > 0 && Math.min(positive, negative) / total >= 0.34) {
            return new Sentiment("MIXED", round(0.3 * positive / total), round(0.3 * negative / total), 0.05,
                    round(0.95 - 0.3));
        }
        if (positive >= negative) {
            double share = positive / total;
            return new Sentiment("POSITIVE", round(0.6 + 0.38 * share), round(0.02 + 0.1 * (1 - share)),
                    round(0.3 * (1 - share) + 0.01), 0.01);
        }
        double share = negative / total;
        return new Sentiment("NEGATIVE", round(0.02 + 0.1 * (1 - share)), round(0.6 + 0.38 * share),
                round(0.3 * (1 - share) + 0.01), 0.01);
    }

    /** The clauses of a text, split at sentence ends, "but", and semicolons, with their offsets. */
    static List<Phrase> clauses(String text) {
        List<Phrase> clauses = new ArrayList<>();
        Matcher splitter = Pattern.compile("[.!?;]+|,?\\s+(?i:but|however|although|whereas)\\s+").matcher(text);
        int start = 0;
        while (splitter.find()) {
            if (splitter.start() > start) {
                clauses.add(new Phrase(text.substring(start, splitter.start()), start, splitter.start()));
            }
            start = splitter.end();
        }
        if (start < text.length()) {
            clauses.add(new Phrase(text.substring(start), start, text.length()));
        }
        return clauses;
    }

    // ── Toxicity ───────────────────────────────────────────────────────────

    static final List<String> TOXICITY_LABELS = List.of("GRAPHIC", "HARASSMENT_OR_ABUSE", "HATE_SPEECH",
            "INSULT", "PROFANITY", "SEXUAL", "VIOLENCE_OR_THREAT");
    private static final Map<String, Set<String>> TOXIC_WORDS = Map.of(
            "INSULT", Set.of("idiot", "stupid", "dumb", "moron", "loser", "worthless", "pathetic", "ugly"),
            "PROFANITY", Set.of("damn", "hell", "crap", "shit", "fuck", "fucking", "bastard"),
            "HARASSMENT_OR_ABUSE", Set.of("shut", "nobody", "disgusting", "freak"),
            "VIOLENCE_OR_THREAT", Set.of("kill", "hurt", "destroy", "attack", "punch", "shoot", "die"),
            "HATE_SPEECH", Set.of("hate"),
            "GRAPHIC", Set.of("blood", "gore", "corpse"),
            "SEXUAL", Set.of("sexy", "nude", "naked"));

    /** Score per toxicity label (0.0–1.0) and the overall toxicity, in label order. */
    static Map<String, Double> toxicity(String text) {
        Map<String, Double> scores = new LinkedHashMap<>();
        List<Token> tokens = tokenize(text);
        double overall = 0.01;
        for (String label : TOXICITY_LABELS) {
            long hits = tokens.stream().filter(t -> TOXIC_WORDS.get(label).contains(t.lower())).count();
            double score = hits == 0 ? 0.01 : round(Math.min(0.99, 0.6 + 0.15 * hits));
            scores.put(label, score);
            overall = Math.max(overall, score);
        }
        scores.put("TOXICITY", overall);
        return scores;
    }

    static double round(double value) {
        return Math.round(value * 10_000) / 10_000.0;
    }
}
