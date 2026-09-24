package io.github.hectorvent.floci.services.amazonmq;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.amazonmq.model.MqConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Amazon MQ broker configurations (CreateConfiguration, DescribeConfiguration,
 * UpdateConfiguration, ...). ActiveMQ revisions are applied to the broker's
 * {@code activemq.xml} when the broker starts or reboots. Scoped per account (storage)
 * and per region (key prefix).
 */
@ApplicationScoped
public class AmazonMqConfigurationService {

    private static final Logger LOG = Logger.getLogger(AmazonMqConfigurationService.class);

    static final String ENGINE_ACTIVEMQ = "ACTIVEMQ";
    static final String ENGINE_RABBITMQ = "RABBITMQ";
    private static final String DEFAULT_ACTIVEMQ_VERSION = "5.18";
    private static final String DEFAULT_RABBITMQ_VERSION = "3.13";
    private static final int MAX_PAGE = 100;

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9._~-]{1,150}");
    private static final Pattern VERSION_PATTERN = Pattern.compile("\\d+\\.\\d+(\\.\\d+)?");

    private static final String DEFAULT_ACTIVEMQ_DATA = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <broker xmlns="http://activemq.apache.org/schema/core">
              <plugins>
              </plugins>
            </broker>
            """;
    private static final String DEFAULT_RABBITMQ_DATA = """
            # Default RabbitMQ delivery acknowledgement timeout is 30 minutes in milliseconds
            consumer_timeout = 1800000
            """;

    private final StorageBackend<String, MqConfiguration> storage;
    private final RegionResolver regionResolver;

    @Inject
    public AmazonMqConfigurationService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.storage = storageFactory.create("amazonmq", "amazonmq-configurations.json",
                new TypeReference<Map<String, MqConfiguration>>() {});
        this.regionResolver = regionResolver;
    }

    public MqConfiguration createConfiguration(String name, String engineType, String engineVersion,
                                               String authenticationStrategy, Map<String, String> tags) {
        if (name == null || name.isBlank()) {
            throw badRequest("Configuration name is required.");
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw badRequest("Configuration name must be 1-150 characters long and contain only "
                    + "alphanumeric characters, dashes, periods, underscores, and tildes (- . _ ~).");
        }
        String engine = canonicalEngine(engineType);
        String version = engineVersion == null || engineVersion.isBlank()
                ? (ENGINE_ACTIVEMQ.equals(engine) ? DEFAULT_ACTIVEMQ_VERSION : DEFAULT_RABBITMQ_VERSION)
                : engineVersion;
        if (!VERSION_PATTERN.matcher(version).matches()) {
            throw badRequest("Broker engine version [" + version + "] is not supported.");
        }
        String strategy = canonicalAuthenticationStrategy(authenticationStrategy, engine);

        String region = regionResolver.getRegion();
        boolean nameTaken = storage.scan(k -> k.startsWith(region + "/")).stream()
                .anyMatch(c -> name.equals(c.getName()));
        if (nameTaken) {
            throw new AwsException("ConflictException",
                    "A configuration with the name [" + name + "] already exists.", 409);
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String id = "c-" + UUID.randomUUID();
        MqConfiguration configuration = new MqConfiguration();
        configuration.setId(id);
        configuration.setArn(AwsArnUtils.Arn.of("mq", region, regionResolver.getAccountId(),
                "configuration:" + id).toString());
        configuration.setName(name);
        configuration.setEngineType(engine);
        configuration.setEngineVersion(version);
        configuration.setAuthenticationStrategy(strategy);
        configuration.setCreated(now);
        configuration.setRegion(region);
        configuration.setTags(tags != null ? new HashMap<>(tags) : new HashMap<>());
        String defaultData = ENGINE_ACTIVEMQ.equals(engine) ? DEFAULT_ACTIVEMQ_DATA : DEFAULT_RABBITMQ_DATA;
        configuration.getRevisions().add(new MqConfiguration.Revision(1, now,
                "Auto-generated default for " + name + " on " + displayEngine(engine) + " " + version,
                Base64.getEncoder().encodeToString(defaultData.getBytes(StandardCharsets.UTF_8))));

        storage.put(key(region, id), configuration);
        LOG.infov("Created Amazon MQ configuration {0} ({1})", name, id);
        return configuration;
    }

    public MqConfiguration describeConfiguration(String configurationId) {
        return find(configurationId).orElseThrow(() -> configurationNotFound(configurationId));
    }

    public MqConfiguration.Revision describeConfigurationRevision(String configurationId, String revision) {
        MqConfiguration configuration = describeConfiguration(configurationId);
        int number;
        try {
            number = Integer.parseInt(revision);
        } catch (NumberFormatException e) {
            throw badRequest("The revision [" + revision + "] is not a valid revision number.");
        }
        MqConfiguration.Revision found = configuration.revision(number);
        if (found == null) {
            throw new AwsException("NotFoundException", "Can't find requested revision [" + revision
                    + "] of configuration [" + configurationId + "].", 404);
        }
        return found;
    }

    public PaginatedResult<MqConfiguration.Revision> listConfigurationRevisions(String configurationId,
                                                                                Integer maxResults,
                                                                                String nextToken) {
        MqConfiguration configuration = describeConfiguration(configurationId);
        return Pagination.paginate(new ArrayList<>(configuration.getRevisions()),
                r -> String.format("%010d", r.getRevision()), maxResults, nextToken, MAX_PAGE,
                "BadRequestException");
    }

    public PaginatedResult<MqConfiguration> listConfigurations(Integer maxResults, String nextToken) {
        String region = regionResolver.getRegion();
        return Pagination.paginate(storage.scan(k -> k.startsWith(region + "/")),
                MqConfiguration::getId, maxResults, nextToken, MAX_PAGE, "BadRequestException");
    }

    /** Publishes a new revision. {@code data} is the base64 document from the request body. */
    public MqConfiguration updateConfiguration(String configurationId, String data, String description) {
        MqConfiguration configuration = describeConfiguration(configurationId);
        if (data == null || data.isBlank()) {
            throw badRequest("The configuration data is required.");
        }
        String document;
        try {
            document = new String(Base64.getDecoder().decode(data.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw badRequest("The configuration data must be base64-encoded.");
        }
        if (ENGINE_ACTIVEMQ.equals(configuration.getEngineType())) {
            validateActiveMqDocument(document);
        }
        MqConfiguration.Revision latest = configuration.latestRevision();
        int next = latest == null ? 1 : latest.getRevision() + 1;
        configuration.getRevisions().add(new MqConfiguration.Revision(next,
                Instant.now().truncatedTo(ChronoUnit.MILLIS), description, data.trim()));
        save(configuration);
        return configuration;
    }

    public void deleteConfiguration(String configurationId) {
        MqConfiguration configuration = describeConfiguration(configurationId);
        storage.delete(key(configuration.getRegion(), configuration.getId()));
        LOG.infov("Deleted Amazon MQ configuration {0}", configurationId);
    }

    /**
     * Validates a CreateBroker {@code configuration} reference the way AWS does: the
     * configuration must exist in this account/region, target the broker's engine, and
     * the revision (when given) must exist.
     */
    public void validateBrokerReference(String configurationId, Integer revision, String brokerEngineType) {
        if (configurationId == null || configurationId.isBlank()) {
            throw badRequest("The configuration id is required.");
        }
        MqConfiguration configuration = describeConfiguration(configurationId);
        if (brokerEngineType != null && !configuration.getEngineType().equalsIgnoreCase(brokerEngineType)) {
            throw badRequest("Configuration [" + configurationId + "] is for engine type "
                    + displayEngine(configuration.getEngineType()) + ", which does not match the broker's engine type.");
        }
        if (revision != null && configuration.revision(revision) == null) {
            throw new AwsException("NotFoundException", "Can't find requested revision [" + revision
                    + "] of configuration [" + configurationId + "].", 404);
        }
    }

    /** The revision a broker applies when its configuration reference omits one: the latest. */
    public int resolveRevision(String configurationId, Integer revision) {
        if (revision != null) {
            return revision;
        }
        MqConfiguration.Revision latest = describeConfiguration(configurationId).latestRevision();
        return latest == null ? 1 : latest.getRevision();
    }

    /**
     * The decoded ActiveMQ {@code <broker>} document of a configuration revision, or
     * {@code null} when the configuration no longer exists, is not an ActiveMQ configuration,
     * or the revision is missing.
     */
    public String activeMqDocument(String configurationId, Integer revision) {
        Optional<MqConfiguration> configuration = find(configurationId);
        if (configuration.isEmpty() || !ENGINE_ACTIVEMQ.equals(configuration.get().getEngineType())) {
            return null;
        }
        MqConfiguration.Revision found = revision == null
                ? configuration.get().latestRevision()
                : configuration.get().revision(revision);
        if (found == null || found.getData() == null) {
            return null;
        }
        try {
            return new String(Base64.getDecoder().decode(found.getData().trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            LOG.warnv("Configuration {0} revision {1} is not valid base64; ignoring it", configurationId,
                    String.valueOf(found.getRevision()));
            return null;
        }
    }

    // --- tags (configuration ARNs) ---

    public boolean isConfigurationArn(String arn) {
        try {
            return AwsArnUtils.parse(arn).resource().startsWith("configuration:");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public Map<String, String> listTags(String arn) {
        return new LinkedHashMap<>(byArn(arn).getTags());
    }

    public void tagResource(String arn, Map<String, String> tags) {
        MqConfiguration configuration = byArn(arn);
        if (tags != null) {
            configuration.getTags().putAll(tags);
        }
        save(configuration);
    }

    public void untagResource(String arn, List<String> tagKeys) {
        MqConfiguration configuration = byArn(arn);
        if (tagKeys != null) {
            tagKeys.forEach(configuration.getTags()::remove);
        }
        save(configuration);
    }

    private MqConfiguration byArn(String arn) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String id = parsed.resource().substring("configuration:".length());
        if (!parsed.accountId().equals(regionResolver.getAccountId())
                || !parsed.region().equals(regionResolver.getRegion())) {
            throw configurationNotFound(id);
        }
        return describeConfiguration(id);
    }

    // --- helpers ---

    static String displayEngine(String engine) {
        return ENGINE_ACTIVEMQ.equals(engine) ? "ActiveMQ" : "RabbitMQ";
    }

    private static String canonicalEngine(String engineType) {
        if (engineType == null || engineType.isBlank()) {
            throw badRequest("The engine type is required.");
        }
        String upper = engineType.toUpperCase(Locale.ROOT);
        if (!ENGINE_ACTIVEMQ.equals(upper) && !ENGINE_RABBITMQ.equals(upper)) {
            throw badRequest("Broker engine type [" + engineType + "] is not supported. "
                    + "Valid values: [ACTIVEMQ, RABBITMQ].");
        }
        return upper;
    }

    private static String canonicalAuthenticationStrategy(String strategy, String engine) {
        if (strategy == null || strategy.isBlank()) {
            return "SIMPLE";
        }
        String upper = strategy.toUpperCase(Locale.ROOT);
        switch (upper) {
            case "SIMPLE":
                return upper;
            case "LDAP":
                if (!ENGINE_ACTIVEMQ.equals(engine)) {
                    throw badRequest("LDAP authentication is only supported for ActiveMQ configurations.");
                }
                return upper;
            case "CONFIG_MANAGED":
                if (!ENGINE_RABBITMQ.equals(engine)) {
                    throw badRequest("CONFIG_MANAGED authentication is only supported for RabbitMQ configurations.");
                }
                return upper;
            default:
                throw badRequest("Authentication strategy [" + strategy
                        + "] is not supported. Valid values: [SIMPLE, LDAP, CONFIG_MANAGED].");
        }
    }

    private static void validateActiveMqDocument(String document) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            // The default handler prints parse errors to stderr; the error is reported to the caller instead.
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(SAXParseException e) throws SAXParseException {
                    throw e;
                }
            });
            Element root = builder.parse(new InputSource(new StringReader(document))).getDocumentElement();
            if (!"broker".equals(root.getLocalName())) {
                throw badRequest("The ActiveMQ configuration must have a <broker> root element.");
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw badRequest("The configuration data is not well-formed XML.");
        }
    }

    private Optional<MqConfiguration> find(String configurationId) {
        if (configurationId == null || configurationId.isBlank()) {
            return Optional.empty();
        }
        return storage.get(key(regionResolver.getRegion(), configurationId));
    }

    private void save(MqConfiguration configuration) {
        storage.put(key(configuration.getRegion(), configuration.getId()), configuration);
    }

    private static String key(String region, String id) {
        return region + "/" + id;
    }

    private static AwsException configurationNotFound(String configurationId) {
        return new AwsException("NotFoundException",
                "Can't find requested configuration [" + configurationId + "].", 404);
    }

    private static AwsException badRequest(String message) {
        return new AwsException("BadRequestException", message, 400);
    }
}
