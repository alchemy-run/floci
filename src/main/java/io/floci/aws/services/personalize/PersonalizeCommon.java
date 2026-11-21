package io.floci.aws.services.personalize;

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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What Personalize's jobs share: checking that a role exists and trusts Personalize, reading and writing S3 objects in
 * the caller's account, and running work after the transition delay.
 */
@ApplicationScoped
public class PersonalizeCommon {

    private static final Logger LOG = Logger.getLogger(PersonalizeCommon.class);

    private final PersonalizeStore store;
    private final Provider<S3Service> s3;
    private final Provider<IamService> iam;
    private final Vertx vertx;
    private final ExecutorService worker = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "personalize");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public PersonalizeCommon(PersonalizeStore store, Provider<S3Service> s3, Provider<IamService> iam, Vertx vertx) {
        this.store = store;
        this.s3 = s3;
        this.iam = iam;
        this.vertx = vertx;
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    /** Runs work after the transition delay, off the event loop, as the account in the Region. */
    void later(String account, String region, Runnable task) {
        vertx.setTimer(Math.max(1, store.config().transitionMillis()), id -> worker.submit(() -> {
            try {
                RequestScopes.runAs(account, region, task);
            } catch (RuntimeException e) {
                LOG.warnv("Personalize work failed: {0}", e.getMessage());
            }
        }));
    }

    /** Fails unless the role exists and trusts Personalize. */
    void role(String arn, String region) {
        if (!AwsArnUtils.isArnFor(arn, "iam") || !AwsArnUtils.parse(arn).resource().startsWith("role/")) {
            throw PersonalizeStore.invalid("The role " + arn + " isn't a role ARN.");
        }
        String resource = AwsArnUtils.parse(arn).resource();
        String name = resource.substring(resource.lastIndexOf('/') + 1);
        String principal = ServicePrincipals.of(PersonalizeStore.SERVICE);
        IamRole role;
        try {
            role = RequestScopes.callAs(AwsArnUtils.accountOrDefault(arn, store.account()), region, () ->
                    iam.get().getRole(name));
        } catch (AwsException e) {
            throw PersonalizeStore.invalid("The role " + arn + " can't be used: " + e.getMessage());
        }
        String trust = role.getAssumeRolePolicyDocument() == null ? "" : URLDecoder.decode(
                role.getAssumeRolePolicyDocument(), StandardCharsets.UTF_8);
        if (!trust.contains(principal)) {
            throw PersonalizeStore.invalid("The role " + arn + " doesn't trust " + principal + ".");
        }
    }

    /** The bucket and key an {@code s3://} URI names. */
    static String[] s3Uri(String uri) {
        if (uri == null || !uri.startsWith("s3://") || uri.length() <= 5) {
            throw PersonalizeStore.invalid("The location " + uri + " isn't an s3:// URI.");
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
        try {
            s3.get().putObject(location[0], location[1], data, contentType, Map.of());
        } catch (AwsException e) {
            throw new IllegalStateException("The file " + uri + " can't be written: " + e.getMessage());
        }
    }

    /** The objects a location names: the object itself, or every object under a folder ending in {@code /}. */
    List<String> objects(String uri) {
        String[] location = s3Uri(uri);
        if (!location[1].isEmpty() && !location[1].endsWith("/")) {
            return List.of(uri);
        }
        try {
            List<String> found = s3.get().listObjects(location[0], location[1], null, 1000).stream()
                    .map(S3Object::getKey).filter(key -> !key.endsWith("/"))
                    .map(key -> "s3://" + location[0] + "/" + key).toList();
            if (found.isEmpty()) {
                throw new IllegalStateException("No files are under " + uri + ".");
            }
            return found;
        } catch (AwsException e) {
            throw new IllegalStateException("The folder " + uri + " can't be listed: " + e.getMessage());
        }
    }
}
