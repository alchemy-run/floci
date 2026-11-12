package io.floci.aws.services.omics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What every part of HealthOmics shares: reading and writing S3 objects in the caller's account, checking that a role
 * exists and trusts HealthOmics, running work after a transition, and the tags of every resource, kept by ARN.
 */
@ApplicationScoped
public class OmicsCommon {

    private static final Logger LOG = Logger.getLogger(OmicsCommon.class);
    static final String TAGS = "tags";

    private final OmicsStore store;
    private final Provider<S3Service> s3;
    private final Provider<IamService> iam;
    private final Vertx vertx;
    private final ExecutorService worker = Executors.newFixedThreadPool(4, task -> {
        Thread thread = new Thread(task, "omics");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public OmicsCommon(OmicsStore store, Provider<S3Service> s3, Provider<IamService> iam, Vertx vertx) {
        this.store = store;
        this.s3 = s3;
        this.iam = iam;
        this.vertx = vertx;
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    ObjectMapper mapper() {
        return store.mapper();
    }

    /** Runs work after the transition delay, off the event loop, as the account in the Region. */
    void later(String account, String region, Runnable task) {
        vertx.setTimer(Math.max(1, store.config().transitionMillis()), id -> worker.submit(() -> {
            try {
                RequestScopes.runAs(account, region, task);
            } catch (RuntimeException e) {
                LOG.warnv("HealthOmics work failed: {0}", e.getMessage());
            }
        }));
    }

    static String required(JsonNode in, String member) {
        String value = in.path(member).asText("");
        if (value.isBlank()) {
            throw OmicsStore.validation(member + " is required.");
        }
        return value;
    }

    String arn(String region, String account, String resource) {
        return AwsArnUtils.Arn.of(OmicsStore.SERVICE, region, account, resource).toString();
    }

    // ---------------------------------------------------------------- roles

    /** Fails unless the role exists and trusts HealthOmics. */
    void role(String arn, String account, String region) {
        if (!AwsArnUtils.isArnFor(arn, "iam") || !AwsArnUtils.parse(arn).resource().startsWith("role/")) {
            throw OmicsStore.validation("The role " + arn + " isn't a role ARN.");
        }
        String resource = AwsArnUtils.parse(arn).resource();
        String name = resource.substring(resource.lastIndexOf('/') + 1);
        String principal = ServicePrincipals.of(OmicsStore.SERVICE);
        IamRole role;
        try {
            role = RequestScopes.callAs(AwsArnUtils.accountOrDefault(arn, account), region, () ->
                    iam.get().getRole(name));
        } catch (AwsException e) {
            throw OmicsStore.validation("The role " + arn + " can't be used: " + e.getMessage());
        }
        String trust = role.getAssumeRolePolicyDocument() == null ? "" : URLDecoder.decode(
                role.getAssumeRolePolicyDocument(), StandardCharsets.UTF_8);
        if (!trust.contains(principal)) {
            throw OmicsStore.validation("The role " + arn + " doesn't trust " + principal + ".");
        }
    }

    // ---------------------------------------------------------------- S3

    /** The bucket and key an {@code s3://} URI names. */
    static String[] s3Uri(String uri) {
        if (uri == null || !uri.startsWith("s3://") || uri.length() <= 5) {
            throw OmicsStore.validation("The location " + uri + " isn't an s3:// URI.");
        }
        String path = uri.substring(5);
        int slash = path.indexOf('/');
        return slash < 0 ? new String[] {path, ""} : new String[] {path.substring(0, slash), path.substring(slash + 1)};
    }

    byte[] read(String uri) {
        String[] location = s3Uri(uri);
        try {
            S3Object object = s3.get().getObject(location[0], location[1]);
            return object.getData() == null ? new byte[0] : object.getData();
        } catch (AwsException e) {
            throw new IllegalStateException("The file " + uri + " can't be read: " + e.getMessage());
        }
    }

    void write(String uri, byte[] data, String contentType) {
        String[] location = s3Uri(uri);
        s3.get().putObject(location[0], location[1], data, contentType, Map.of());
    }

    /** The keys under an S3 prefix. */
    List<String> list(String uri) {
        String[] location = s3Uri(uri);
        return s3.get().listObjects(location[0], location[1], null, 1000).stream().map(S3Object::getKey).toList();
    }

    // ---------------------------------------------------------------- tags

    private static String tagKey(String arn) {
        return arn.toLowerCase(Locale.ROOT).replace('/', '|');
    }

    /** Records a resource's ARN, with its first tags, so it can be tagged. */
    void tagged(String account, String arn, JsonNode tags) {
        ObjectNode record = store.record();
        record.put("arn", arn);
        addTags(record, tags);
        store.putForAccount(account, OmicsStore.GLOBAL_TAGS, TAGS, tagKey(arn), record);
    }

    void untagged(String account, String arn) {
        store.deleteForAccount(account, OmicsStore.GLOBAL_TAGS, TAGS, tagKey(arn));
    }

    ObjectNode tags(String account, String arn) {
        return store.getForAccount(account, OmicsStore.GLOBAL_TAGS, TAGS, tagKey(arn))
                .map(record -> OmicsStore.tags(record).deepCopy())
                .orElse(store.mapper().createObjectNode());
    }

    private static void addTags(ObjectNode record, JsonNode tags) {
        if (tags == null || !tags.isObject()) {
            return;
        }
        ObjectNode map = OmicsStore.tags(record);
        tags.properties().forEach(tag -> {
            if (tag.getKey().toLowerCase(Locale.ROOT).startsWith("aws:")) {
                throw OmicsStore.validation("Tag keys can't start with aws:.");
            }
            map.put(tag.getKey(), tag.getValue().asText(""));
        });
        if (map.size() > 50) {
            throw OmicsStore.quota("A resource can have at most 50 tags.");
        }
    }

    private ObjectNode tagRecord(String account, String arn) {
        return store.getForAccount(account, OmicsStore.GLOBAL_TAGS, TAGS, tagKey(arn)).orElseThrow(() ->
                OmicsStore.notFound("The resource " + arn + " doesn't exist."));
    }

    ObjectNode tagResource(JsonNode in) {
        String arn = required(in, "resourceArn");
        String account = store.account();
        ObjectNode record = tagRecord(account, arn);
        addTags(record, in.path("tags"));
        store.putForAccount(account, OmicsStore.GLOBAL_TAGS, TAGS, tagKey(arn), record);
        return store.mapper().createObjectNode();
    }

    ObjectNode untagResource(JsonNode in) {
        String arn = required(in, "resourceArn");
        String account = store.account();
        ObjectNode record = tagRecord(account, arn);
        in.path("tagKeys").forEach(key -> OmicsStore.tags(record).remove(key.asText()));
        store.putForAccount(account, OmicsStore.GLOBAL_TAGS, TAGS, tagKey(arn), record);
        return store.mapper().createObjectNode();
    }

    ObjectNode listTagsForResource(JsonNode in) {
        String arn = required(in, "resourceArn");
        ObjectNode out = store.mapper().createObjectNode();
        out.set("tags", OmicsStore.tags(tagRecord(store.account(), arn)).deepCopy());
        return out;
    }
}
