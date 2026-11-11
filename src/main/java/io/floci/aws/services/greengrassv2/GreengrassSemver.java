package io.floci.aws.services.greengrassv2;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic versions as Greengrass reads them: a component version is {@code major.minor.patch} with an optional
 * prerelease and build, and a version requirement is an npm-style range, comparators joined by spaces (all must
 * hold) and alternatives by {@code ||}: {@code >=1.0.0 <2.0.0}, {@code ^1.2.0}, {@code ~1.2}, {@code 1.x},
 * {@code 1.0.0 - 1.5.0}, {@code *}.
 */
final class GreengrassSemver implements Comparable<GreengrassSemver> {

    private static final Pattern VERSION = Pattern.compile(
            "v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?");
    private static final Pattern PARTIAL = Pattern.compile("v?(\\d+|[xX*])(?:\\.(\\d+|[xX*]))?(?:\\.(\\d+|[xX*]))?"
            + "(?:-([0-9A-Za-z.-]+))?");
    private static final Pattern COMPARATOR = Pattern.compile("(<=|>=|<|>|=|\\^|~)?(.+)");
    private static final Pattern OPERATOR = Pattern.compile("<=|>=|<|>|=|\\^|~");

    final int major;
    final int minor;
    final int patch;
    final String prerelease;

    private GreengrassSemver(int major, int minor, int patch, String prerelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = prerelease;
    }

    static boolean valid(String version) {
        return version != null && VERSION.matcher(version).matches();
    }

    static GreengrassSemver parse(String version) {
        Matcher matcher = VERSION.matcher(version == null ? "" : version.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a semantic version: " + version);
        }
        return new GreengrassSemver(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)), matcher.group(4));
    }

    @Override
    public int compareTo(GreengrassSemver other) {
        if (major != other.major) {
            return Integer.compare(major, other.major);
        }
        if (minor != other.minor) {
            return Integer.compare(minor, other.minor);
        }
        if (patch != other.patch) {
            return Integer.compare(patch, other.patch);
        }
        if (prerelease == null || other.prerelease == null) {
            return prerelease == null ? (other.prerelease == null ? 0 : 1) : -1;
        }
        String[] mine = prerelease.split("\\.");
        String[] theirs = other.prerelease.split("\\.");
        for (int i = 0; i < Math.min(mine.length, theirs.length); i++) {
            boolean numeric = mine[i].matches("\\d+");
            boolean otherNumeric = theirs[i].matches("\\d+");
            int compared = numeric && otherNumeric ? Long.compare(Long.parseLong(mine[i]), Long.parseLong(theirs[i]))
                    : numeric ? -1 : otherNumeric ? 1 : mine[i].compareTo(theirs[i]);
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(mine.length, theirs.length);
    }

    /** Whether a requirement is one this reads. */
    static boolean validRequirement(String requirement) {
        try {
            satisfies("0.0.0", requirement);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Whether {@code version} satisfies {@code requirement}. A prerelease satisfies only a range that names one. */
    static boolean satisfies(String version, String requirement) {
        GreengrassSemver candidate = parse(version);
        String range = requirement == null ? "" : requirement.trim();
        if (range.isEmpty() || "*".equals(range)) {
            return candidate.prerelease == null;
        }
        for (String alternative : range.split("\\|\\|")) {
            if (allHold(candidate, alternative.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean allHold(GreengrassSemver candidate, String set) {
        if (set.isEmpty() || "*".equals(set)) {
            return candidate.prerelease == null;
        }
        String[] hyphen = set.split("\\s+-\\s+");
        List<Bound> bounds = new ArrayList<>();
        if (hyphen.length == 2) {
            bounds.add(new Bound(">=", lower(hyphen[0].trim())));
            bounds.addAll(upperInclusive(hyphen[1].trim()));
        } else {
            String pendingOperator = "";
            for (String token : set.split("\\s+")) {
                if (OPERATOR.matcher(token).matches()) {
                    pendingOperator = token;
                    continue;
                }
                Matcher matcher = COMPARATOR.matcher(pendingOperator + token);
                if (!matcher.matches()) {
                    throw new IllegalArgumentException("Not a version requirement: " + token);
                }
                bounds.addAll(expand(matcher.group(1) == null ? "" : matcher.group(1), matcher.group(2)));
                pendingOperator = "";
            }
            if (!pendingOperator.isEmpty()) {
                throw new IllegalArgumentException("A version requirement ends with " + pendingOperator);
            }
        }
        boolean namesPrerelease = false;
        for (Bound bound : bounds) {
            if (!bound.holds(candidate)) {
                return false;
            }
            namesPrerelease |= bound.version.prerelease != null && bound.version.major == candidate.major
                    && bound.version.minor == candidate.minor && bound.version.patch == candidate.patch;
        }
        return candidate.prerelease == null || namesPrerelease;
    }

    private record Bound(String operator, GreengrassSemver version) {
        boolean holds(GreengrassSemver candidate) {
            int compared = candidate.compareTo(version);
            return switch (operator) {
                case ">=" -> compared >= 0;
                case ">" -> compared > 0;
                case "<=" -> compared <= 0;
                case "<" -> compared < 0;
                default -> compared == 0;
            };
        }
    }

    private static int[] numbers(String partial) {
        Matcher matcher = PARTIAL.matcher(partial);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a version requirement: " + partial);
        }
        int[] parts = {-1, -1, -1};
        for (int i = 0; i < 3; i++) {
            String group = matcher.group(i + 1);
            parts[i] = group == null || group.matches("[xX*]") ? -1 : Integer.parseInt(group);
        }
        return parts;
    }

    private static String prereleaseOf(String partial) {
        Matcher matcher = PARTIAL.matcher(partial);
        return matcher.matches() ? matcher.group(4) : null;
    }

    private static GreengrassSemver lower(String partial) {
        int[] parts = numbers(partial);
        return new GreengrassSemver(Math.max(0, parts[0]), Math.max(0, parts[1]), Math.max(0, parts[2]),
                prereleaseOf(partial));
    }

    private static List<Bound> upperInclusive(String partial) {
        int[] parts = numbers(partial);
        if (parts[0] < 0) {
            return List.of();
        }
        if (parts[1] < 0) {
            return List.of(new Bound("<", new GreengrassSemver(parts[0] + 1, 0, 0, "0")));
        }
        if (parts[2] < 0) {
            return List.of(new Bound("<", new GreengrassSemver(parts[0], parts[1] + 1, 0, "0")));
        }
        return List.of(new Bound("<=", new GreengrassSemver(parts[0], parts[1], parts[2], prereleaseOf(partial))));
    }

    private static List<Bound> expand(String operator, String partial) {
        int[] parts = numbers(partial);
        GreengrassSemver base = lower(partial);
        boolean wildcard = parts[0] < 0 || parts[1] < 0 || parts[2] < 0;
        switch (operator) {
            case "^" -> {
                GreengrassSemver upper = parts[0] > 0 || parts[1] < 0
                        ? new GreengrassSemver(base.major + 1, 0, 0, "0")
                        : parts[1] > 0 || parts[2] < 0 ? new GreengrassSemver(0, base.minor + 1, 0, "0")
                        : new GreengrassSemver(0, 0, base.patch + 1, "0");
                return List.of(new Bound(">=", base), new Bound("<", upper));
            }
            case "~" -> {
                GreengrassSemver upper = parts[1] < 0 ? new GreengrassSemver(base.major + 1, 0, 0, "0")
                        : new GreengrassSemver(base.major, base.minor + 1, 0, "0");
                return List.of(new Bound(">=", base), new Bound("<", upper));
            }
            case "", "=" -> {
                if (!wildcard) {
                    return List.of(new Bound("=", base));
                }
                List<Bound> bounds = new ArrayList<>();
                if (parts[0] >= 0) {
                    bounds.add(new Bound(">=", base));
                    bounds.addAll(upperInclusive(partial));
                }
                return bounds;
            }
            case ">" -> {
                if (!wildcard) {
                    return List.of(new Bound(">", base));
                }
                List<Bound> upper = upperInclusive(partial);
                return upper.isEmpty() ? List.of(new Bound("<", new GreengrassSemver(0, 0, 0, "0")))
                        : List.of(new Bound(">=", upper.get(0).version));
            }
            case ">=" -> {
                return List.of(new Bound(">=", base));
            }
            case "<" -> {
                return List.of(new Bound("<", wildcard ? new GreengrassSemver(base.major, base.minor, base.patch,
                        "0") : base));
            }
            case "<=" -> {
                return wildcard ? upperInclusive(partial) : List.of(new Bound("<=", base));
            }
            default -> throw new IllegalArgumentException("Unknown operator: " + operator);
        }
    }
}
