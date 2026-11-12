package io.floci.aws.services.synthetics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The CloudWatch Synthetics runtime versions, with the Lambda runtime each one runs on and, for deprecated versions,
 * the date AWS deprecated it. A canary can't be created on, or moved to, a deprecated version.
 */
final class SyntheticsRuntimes {

    /** One runtime version. {@code lambdaRuntime} is null for versions whose Lambda runtime is gone. */
    record Version(String name, String language, String framework, String lambdaRuntime, String deprecated) {

        boolean python() {
            return "python".equals(language);
        }

        Optional<Instant> deprecationDate() {
            return Optional.ofNullable(deprecated).map(d -> LocalDate.parse(d).atStartOfDay(ZoneOffset.UTC)
                    .toInstant());
        }

        String description() {
            String runtime = lambdaRuntime == null ? language : lambdaRuntime;
            return "Synthetics runtime for " + runtime + " with " + framework + ".";
        }
    }

    private static final Map<String, Version> VERSIONS = new LinkedHashMap<>();

    static {
        // Node.js and Puppeteer.
        for (String v : List.of("19.0", "18.0", "17.0", "16.1", "16.0", "15.1", "15.0", "14.0", "13.1", "13.0",
                "12.0")) {
            add("syn-nodejs-puppeteer-" + v, "nodejs", "Puppeteer", "nodejs22.x", null);
        }
        for (String v : List.of("11.0", "10.0", "9.1", "9.0", "8.0")) {
            add("syn-nodejs-puppeteer-" + v, "nodejs", "Puppeteer", "nodejs20.x", null);
        }
        add("syn-nodejs-puppeteer-7.0", "nodejs", "Puppeteer", "nodejs18.x", "2026-01-22");
        add("syn-nodejs-puppeteer-6.2", "nodejs", "Puppeteer", "nodejs18.x", "2026-01-22");
        add("syn-nodejs-puppeteer-6.1", "nodejs", "Puppeteer", "nodejs18.x", "2024-03-08");
        add("syn-nodejs-puppeteer-6.0", "nodejs", "Puppeteer", "nodejs18.x", "2024-03-08");
        add("syn-nodejs-puppeteer-5.2", "nodejs", "Puppeteer", null, "2026-01-22");
        add("syn-nodejs-puppeteer-5.1", "nodejs", "Puppeteer", null, "2024-03-08");
        add("syn-nodejs-puppeteer-5.0", "nodejs", "Puppeteer", null, "2024-03-08");
        add("syn-nodejs-puppeteer-4.0", "nodejs", "Puppeteer", null, "2024-03-08");
        for (String v : List.of("3.9", "3.8", "3.7", "3.6", "3.5")) {
            add("syn-nodejs-puppeteer-" + v, "nodejs", "Puppeteer", null, "2024-01-08");
        }
        for (String v : List.of("3.4", "3.3", "3.2", "3.1", "3.0")) {
            add("syn-nodejs-puppeteer-" + v, "nodejs", "Puppeteer", null, "2022-11-13");
        }
        add("syn-nodejs-2.2", "nodejs", "Puppeteer", null, "2021-05-28");
        add("syn-nodejs-2.1", "nodejs", "Puppeteer", null, "2021-05-28");
        add("syn-nodejs-2.0", "nodejs", "Puppeteer", null, "2021-05-28");
        add("syn-nodejs-2.0-beta", "nodejs", "Puppeteer", null, "2021-02-08");
        add("syn-1.0", "nodejs", "Puppeteer", null, "2021-05-28");
        // Node.js and Playwright.
        for (String v : List.of("9.0", "8.0", "7.1", "7.0", "6.1", "6.0", "5.1", "5.0", "4.0")) {
            add("syn-nodejs-playwright-" + v, "nodejs", "Playwright", "nodejs22.x", null);
        }
        for (String v : List.of("3.0", "2.0", "1.0")) {
            add("syn-nodejs-playwright-" + v, "nodejs", "Playwright", "nodejs20.x", null);
        }
        // Python and Selenium.
        for (String v : List.of("14.0", "13.0", "12.0", "11.1", "11.0")) {
            add("syn-python-selenium-" + v, "python", "Selenium", "python3.12", null);
        }
        for (String v : List.of("10.0", "9.0", "8.0", "7.0", "6.0")) {
            add("syn-python-selenium-" + v, "python", "Selenium", "python3.11", null);
        }
        for (String v : List.of("5.1", "5.0", "4.1", "4.0")) {
            add("syn-python-selenium-" + v, "python", "Selenium", "python3.9", "2026-02-03");
        }
        add("syn-python-selenium-3.0", "python", "Selenium", null, "2026-01-22");
        add("syn-python-selenium-2.1", "python", "Selenium", null, "2026-01-22");
        for (String v : List.of("2.0", "1.3", "1.2", "1.1", "1.0")) {
            add("syn-python-selenium-" + v, "python", "Selenium", null, "2024-03-08");
        }
    }

    private SyntheticsRuntimes() {
    }

    private static void add(String name, String language, String framework, String lambdaRuntime,
                            String deprecated) {
        VERSIONS.put(name, new Version(name, language, framework, lambdaRuntime, deprecated));
    }

    static Optional<Version> find(String name) {
        return Optional.ofNullable(VERSIONS.get(name));
    }

    static List<Version> all() {
        return new ArrayList<>(VERSIONS.values());
    }
}
