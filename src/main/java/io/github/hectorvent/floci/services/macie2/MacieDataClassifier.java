package io.github.hectorvent.floci.services.macie2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates Macie data identifiers against decoded object text. Custom data identifiers use
 * their own regex, keywords, ignore words and maximum match distance. Floci implements a
 * documented subset of the managed data identifiers, listed in {@link #MANAGED}.
 */
final class MacieDataClassifier {

    static final String CREDENTIALS = "CREDENTIALS";
    static final String FINANCIAL = "FINANCIAL_INFORMATION";
    static final String PERSONAL = "PERSONAL_INFORMATION";
    /** Macie reports at most this many occurrence locations per detection. */
    static final int MAX_OCCURRENCES = 15;

    private MacieDataClassifier() {}

    /**
     * A managed data identifier. {@code severities} holds the severity for 1, 2 to 99, and 100
     * or more occurrences, from the Macie severity scoring tables.
     */
    record ManagedIdentifier(String id, String category, boolean recommended, Pattern pattern,
                             List<String> keywords, int maximumMatchDistance, Predicate<String> validator,
                             List<String> severities) {}

    /** One identifier to evaluate: a managed identifier ({@code category} set) or a custom one. */
    record Rule(String id, String name, String arn, String category, Pattern pattern, List<String> keywords,
                List<String> ignoreWords, int maximumMatchDistance, Predicate<String> validator) {

        boolean custom() {
            return category == null;
        }
    }

    record Occurrence(long line, long startColumn) {}

    record Detection(Rule rule, long count, List<Occurrence> occurrences) {}

    record Evaluation(List<Detection> detections, boolean partial) {}

    static final List<ManagedIdentifier> MANAGED = List.of(
            new ManagedIdentifier("AWS_CREDENTIALS", CREDENTIALS, true,
                    Pattern.compile("(?<![A-Za-z0-9/+=])[A-Za-z0-9/+=]{40}(?![A-Za-z0-9/+=])"),
                    List.of("aws_secret_access_key", "aws secret access key", "secret_access_key",
                            "secretaccesskey", "aws_secret_key"),
                    50, MacieDataClassifier::looksLikeSecretKey, List.of("High", "High", "High")),
            new ManagedIdentifier("CREDIT_CARD_NUMBER", FINANCIAL, true,
                    Pattern.compile("(?<![0-9])(?:[0-9][ -]?){12,18}[0-9](?![0-9])"),
                    List.of("credit card", "card number", "card no", "cc number", "ccn", "visa", "mastercard",
                            "amex", "american express", "discover", "payment card"),
                    50, MacieDataClassifier::luhn, List.of("High", "High", "High")),
            new ManagedIdentifier("EMAIL_ADDRESS", PERSONAL, false,
                    Pattern.compile("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}"),
                    List.of(), 0, value -> true, List.of("Low", "Medium", "High")),
            new ManagedIdentifier("OPENSSH_PRIVATE_KEY", CREDENTIALS, true,
                    Pattern.compile("-----BEGIN OPENSSH PRIVATE KEY-----"),
                    List.of(), 0, value -> true, List.of("High", "High", "High")),
            new ManagedIdentifier("PGP_PRIVATE_KEY", CREDENTIALS, true,
                    Pattern.compile("-----BEGIN PGP PRIVATE KEY BLOCK-----"),
                    List.of(), 0, value -> true, List.of("High", "High", "High")),
            new ManagedIdentifier("PKCS", CREDENTIALS, true,
                    Pattern.compile("-----BEGIN (?:RSA |DSA |EC |ENCRYPTED )?PRIVATE KEY-----"),
                    List.of(), 0, value -> true, List.of("High", "High", "High")),
            new ManagedIdentifier("USA_SOCIAL_SECURITY_NUMBER", PERSONAL, true,
                    Pattern.compile("(?<![0-9])(?!000|666|9[0-9]{2})[0-9]{3}-(?!00)[0-9]{2}-(?!0000)[0-9]{4}(?![0-9])"),
                    List.of("ssn", "social security", "ss#", "ssn#", "socialsecurity"),
                    50, value -> true, List.of("High", "High", "High")));

    static ManagedIdentifier managed(String id) {
        for (ManagedIdentifier identifier : MANAGED) {
            if (identifier.id().equals(id)) {
                return identifier;
            }
        }
        return null;
    }

    static Rule rule(ManagedIdentifier identifier) {
        return new Rule(identifier.id(), identifier.id(), null, identifier.category(), identifier.pattern(),
                identifier.keywords(), List.of(), identifier.maximumMatchDistance(), identifier.validator());
    }

    static String managedSeverity(String id, long count) {
        ManagedIdentifier identifier = managed(id);
        List<String> severities = identifier == null ? List.of("Medium", "Medium", "Medium") : identifier.severities();
        return count >= 100 ? severities.get(2) : count >= 2 ? severities.get(1) : severities.get(0);
    }

    /** Evaluates every rule; matches of any allow-list pattern are ignored, as Macie does. */
    static Evaluation evaluate(String text, List<Rule> rules, List<Pattern> allowList) {
        long[] lineStarts = lineStarts(text);
        List<Detection> detections = new ArrayList<>();
        boolean partial = false;
        for (Rule rule : rules) {
            long count = 0;
            List<Occurrence> occurrences = new ArrayList<>();
            try {
                Matcher matcher = rule.pattern().matcher(new BoundedText(text));
                while (matcher.find()) {
                    if (matcher.end() == matcher.start()) {
                        continue;
                    }
                    String match = text.substring(matcher.start(), matcher.end());
                    if (!rule.validator().test(match) || containsIgnoredWord(match, rule.ignoreWords())
                            || allowed(match, allowList)
                            || (!rule.keywords().isEmpty() && !hasPrecedingKeyword(text, matcher.start(),
                                    matcher.end(), rule.keywords(), rule.maximumMatchDistance()))) {
                        continue;
                    }
                    count++;
                    if (occurrences.size() < MAX_OCCURRENCES) {
                        int line = lineIndex(lineStarts, matcher.start());
                        occurrences.add(new Occurrence(line + 1L,
                                text.codePointCount((int) lineStarts[line], matcher.start()) + 1L));
                    }
                }
            } catch (RegexBudgetExceeded | StackOverflowError e) {
                partial = true;
            }
            if (count > 0) {
                detections.add(new Detection(rule, count, occurrences));
            }
        }
        return new Evaluation(detections, partial);
    }

    static boolean hasPrecedingKeyword(String text, int start, int end, List<String> keywords, int distance) {
        int windowStart = Math.max(0, end - 2 * distance - 200);
        String window = text.substring(windowStart, start).toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            String needle = keyword.toLowerCase(Locale.ROOT);
            int from = window.length();
            while (from >= 0) {
                int index = window.lastIndexOf(needle, from);
                if (index < 0) {
                    break;
                }
                int keywordEnd = windowStart + index + needle.length();
                if (keywordEnd <= start && text.codePointCount(keywordEnd, end) <= distance) {
                    return true;
                }
                from = index - 1;
            }
        }
        return false;
    }

    private static boolean containsIgnoredWord(String match, List<String> ignoreWords) {
        for (String word : ignoreWords) {
            if (match.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean allowed(String match, List<Pattern> allowList) {
        for (Pattern pattern : allowList) {
            if (pattern.matcher(match).matches()) {
                return true;
            }
        }
        return false;
    }

    static boolean luhn(String value) {
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }

    private static boolean looksLikeSecretKey(String value) {
        return value.chars().anyMatch(Character::isUpperCase)
                && value.chars().anyMatch(Character::isLowerCase)
                && value.chars().anyMatch(Character::isDigit);
    }

    private static long[] lineStarts(String text) {
        List<Long> starts = new ArrayList<>();
        starts.add(0L);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1L);
            }
        }
        return starts.stream().mapToLong(Long::longValue).toArray();
    }

    private static int lineIndex(long[] lineStarts, int offset) {
        int index = Arrays.binarySearch(lineStarts, offset);
        return index >= 0 ? index : -index - 2;
    }

    private static final class RegexBudgetExceeded extends RuntimeException {
        private RegexBudgetExceeded() {
            super(null, null, false, false);
        }
    }

    // Bounds backtracking of customer-supplied patterns over large objects.
    private static final class BoundedText implements CharSequence {
        private final String text;
        private final long[] remaining;

        private BoundedText(String text) {
            this(text, new long[]{Math.max(1_000_000L, text.length() * 200L)});
        }

        private BoundedText(String text, long[] remaining) {
            this.text = text;
            this.remaining = remaining;
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int index) {
            if (--remaining[0] < 0) {
                throw new RegexBudgetExceeded();
            }
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new BoundedText(text.substring(start, end), remaining);
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
