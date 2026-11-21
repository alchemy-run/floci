package io.floci.aws.services.bedrockagent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lexical relevance between a query and a passage, standing in for embedding
 * similarity in Retrieve and the reranking models: the cosine similarity of
 * their content-word counts, so a passage scores higher the more of the
 * query's words it shares. Function words are ignored and plural forms fold
 * into the singular. Scores are in {@code [0, 1]}.
 */
final class Relevance {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Set<String> STOPWORDS = Set.of("a", "an", "the", "and", "or", "but", "of", "to", "in",
            "on", "at", "for", "with", "by", "from", "is", "are", "was", "were", "be", "been", "it", "its", "this",
            "that", "these", "those", "as", "what", "which", "who", "how", "why", "when", "where", "do", "does",
            "did", "i", "you", "we", "they", "he", "she", "me", "my", "your", "our", "about", "can", "will");

    private Relevance() {}

    static double score(String query, String passage) {
        Map<String, Integer> q = counts(query);
        Map<String, Integer> p = counts(passage);
        if (q.isEmpty() || p.isEmpty()) {
            return 0;
        }
        double dot = 0;
        for (Map.Entry<String, Integer> term : q.entrySet()) {
            dot += term.getValue() * p.getOrDefault(term.getKey(), 0);
        }
        return dot == 0 ? 0 : Math.round(dot / (norm(q) * norm(p)) * 10_000) / 10_000.0;
    }

    private static Map<String, Integer> counts(String text) {
        Map<String, Integer> counts = new HashMap<>();
        Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (STOPWORDS.contains(word)) {
                continue;
            }
            counts.merge(stem(word), 1, Integer::sum);
        }
        return counts;
    }

    private static String stem(String word) {
        if (word.length() > 4 && word.endsWith("ies")) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    private static double norm(Map<String, Integer> counts) {
        double sum = 0;
        for (int count : counts.values()) {
            sum += (double) count * count;
        }
        return Math.sqrt(sum);
    }
}
