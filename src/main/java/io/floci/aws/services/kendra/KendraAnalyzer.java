package io.floci.aws.services.kendra;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * How Kendra reads text: words split on anything that isn't a letter or digit, lowercased and stripped of accents,
 * with English stop words marked and every word reduced to its stem (a light English stemmer: plurals, possessives,
 * {@code -ing}, {@code -ed}, {@code -ly} and the common derivational endings), so "indexes", "indexing" and
 * "indexed" all match "index".
 */
final class KendraAnalyzer {

    /** A word of a text: its stem, its lowercase form, where it is, and its position among the words. */
    record Token(String stem, String word, int start, int end, int position, boolean stop) {
    }

    static final Set<String> STOP_WORDS = Set.of("a", "an", "and", "are", "as", "at", "be", "but", "by", "do",
            "does", "for", "from", "had", "has", "have", "how", "i", "if", "in", "into", "is", "it", "its", "me",
            "my", "no", "not", "of", "on", "or", "our", "so", "such", "that", "the", "their", "them", "then",
            "there", "these", "they", "this", "to", "us", "was", "we", "were", "what", "when", "where", "which",
            "who", "whom", "why", "will", "with", "would", "you", "your", "can", "could", "should", "about", "all",
            "any", "been", "being", "did", "than", "too", "very", "just", "over", "under", "up", "out", "some");

    static final Set<String> QUESTION_WORDS = Set.of("who", "what", "when", "where", "why", "how", "which",
            "whom", "whose", "is", "are", "does", "do", "can", "could", "should", "was", "were", "will");

    private KendraAnalyzer() {
    }

    static List<Token> tokens(String text) {
        List<Token> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        int i = 0;
        int position = 0;
        int n = text.length();
        while (i < n) {
            int cp = text.codePointAt(i);
            if (!Character.isLetterOrDigit(cp)) {
                i += Character.charCount(cp);
                continue;
            }
            int start = i;
            while (i < n) {
                int c = text.codePointAt(i);
                boolean inner = (c == '\'' || c == '’') && i + 1 < n && Character.isLetter(text.codePointAt(i + 1))
                        && i > start;
                if (!Character.isLetterOrDigit(c) && !inner) {
                    break;
                }
                i += Character.charCount(c);
            }
            String word = fold(text.substring(start, i));
            tokens.add(new Token(stem(word), word, start, i, position++, STOP_WORDS.contains(word)));
        }
        return tokens;
    }

    /** Lowercase without accents. */
    static String fold(String word) {
        String lower = word.toLowerCase(Locale.ROOT).replace('’', '\'');
        String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "");
    }

    /** The stems of a text's words that aren't stop words. */
    static List<String> terms(String text) {
        List<String> terms = new ArrayList<>();
        for (Token token : tokens(text)) {
            if (!token.stop()) {
                terms.add(token.stem());
            }
        }
        return terms;
    }

    static boolean isQuestion(String text) {
        String trimmed = text.strip();
        if (trimmed.endsWith("?")) {
            return true;
        }
        List<Token> tokens = tokens(trimmed);
        return tokens.size() >= 2 && QUESTION_WORDS.contains(tokens.get(0).word());
    }

    /** A light English stemmer. */
    static String stem(String word) {
        String w = word;
        if (w.endsWith("'s")) {
            w = w.substring(0, w.length() - 2);
        } else if (w.endsWith("'")) {
            w = w.substring(0, w.length() - 1);
        }
        if (w.length() <= 3 || !w.chars().allMatch(Character::isLetter)) {
            return w;
        }
        // Plurals.
        if (w.endsWith("sses")) {
            w = w.substring(0, w.length() - 2);
        } else if (w.endsWith("ies") && w.length() > 4) {
            w = w.substring(0, w.length() - 3) + "y";
        } else if (w.endsWith("xes") || w.endsWith("ches") || w.endsWith("shes") || w.endsWith("zes")) {
            w = w.substring(0, w.length() - 2);
        } else if (w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is")) {
            w = w.substring(0, w.length() - 1);
        }
        // Verb endings.
        if (w.endsWith("eed")) {
            if (w.length() > 4) {
                w = w.substring(0, w.length() - 1);
            }
        } else if ((w.endsWith("ing") && hasVowel(w.substring(0, w.length() - 3)) && w.length() > 5)
                || (w.endsWith("ed") && hasVowel(w.substring(0, w.length() - 2)) && w.length() > 4)) {
            w = w.substring(0, w.length() - (w.endsWith("ing") ? 3 : 2));
            if (w.endsWith("at") || w.endsWith("bl") || w.endsWith("iz")) {
                w = w + "e";
            } else if (w.length() > 2 && w.charAt(w.length() - 1) == w.charAt(w.length() - 2)
                    && "lsz".indexOf(w.charAt(w.length() - 1)) < 0) {
                w = w.substring(0, w.length() - 1);
            }
        }
        // Derivational endings.
        String[][] endings = {{"ational", "ate"}, {"tional", "tion"}, {"ization", "ize"}, {"fulness", "ful"},
                {"iveness", "ive"}, {"ousness", "ous"}, {"ation", "ate"}, {"ments", "ment"}, {"ness", ""},
                {"ally", "al"}, {"ly", ""}};
        for (String[] ending : endings) {
            if (w.endsWith(ending[0]) && w.length() - ending[0].length() >= 3) {
                w = w.substring(0, w.length() - ending[0].length()) + ending[1];
                break;
            }
        }
        if (w.endsWith("e") && w.length() > 4 && !w.endsWith("ee")) {
            w = w.substring(0, w.length() - 1);
        }
        if (w.endsWith("y") && w.length() > 3 && !isVowel(w.charAt(w.length() - 2))) {
            w = w.substring(0, w.length() - 1) + "i";
        }
        return w;
    }

    private static boolean hasVowel(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (isVowel(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVowel(char c) {
        return "aeiouy".indexOf(c) >= 0;
    }

    /** Levenshtein distance, giving up past a bound. */
    static int distance(String a, String b, int bound) {
        if (Math.abs(a.length() - b.length()) > bound) {
            return bound + 1;
        }
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            int rowMin = current[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
                rowMin = Math.min(rowMin, current[j]);
            }
            if (rowMin > bound) {
                return bound + 1;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
