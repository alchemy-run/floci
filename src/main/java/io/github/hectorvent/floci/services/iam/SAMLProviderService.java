package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iam.model.IamSamlProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Minimal IAM SAML provider registry used by STS assertion verification. */
@ApplicationScoped
public class SAMLProviderService {
    private static final Pattern ARN = Pattern.compile("^arn:aws:iam::(\\d{12}):saml-provider/[A-Za-z0-9+=,.@_-]{1,128}$");
    private final StorageBackend<String, IamSamlProvider> providers;
    private final Object providerLock = new Object();

    @Inject
    public SAMLProviderService(StorageFactory storageFactory) {
        this(storageFactory.create("iam", "iam-saml-providers.json", new TypeReference<>() {}));
    }

    SAMLProviderService(StorageBackend<String, IamSamlProvider> providers) {
        this.providers = providers;
    }

    public IamSamlProvider create(String accountId, String name, String metadata) {
        String arn = "arn:aws:iam::" + accountId + ":saml-provider/" + name;
        if (!ARN.matcher(arn).matches()) {
            throw new AwsException("InvalidInput", "Invalid SAML provider name.", 400);
        }
        if (metadata == null || metadata.isBlank()) {
            throw new AwsException("InvalidInput", "SAML metadata document must not be empty.", 400);
        }
        SAMLMetadata.Parsed parsed;
        try {
            parsed = SAMLMetadata.parse(metadata);
        } catch (Exception e) {
            throw new AwsException("InvalidInput", "The SAML metadata document is invalid.", 400);
        }
        IamSamlProvider provider = new IamSamlProvider();
        provider.setArn(arn);
        provider.setName(name);
        provider.setUuid(UUID.randomUUID().toString());
        provider.setMetadataDocument(metadata);
        provider.setEntityId(parsed.entityId());
        provider.setCertificate(parsed.certificateBase64());
        synchronized (providerLock) {
            if (findForAccount(accountId, arn).isPresent()) {
                throw new AwsException("EntityAlreadyExists",
                        "SAML provider " + arn + " already exists.", 409);
            }
            if (providers instanceof AccountAwareStorageBackend<IamSamlProvider> aware) {
                aware.putForAccount(accountId, arn, provider);
            } else {
                providers.put(arn, provider);
            }
        }
        return provider;
    }

    public Optional<IamSamlProvider> find(String arn) {
        var matcher = ARN.matcher(arn == null ? "" : arn);
        if (matcher.matches()) {
            return findForAccount(matcher.group(1), arn);
        }
        return providers.get(arn);
    }

    public Optional<IamSamlProvider> findForAccount(String accountId, String arn) {
        var matcher = ARN.matcher(arn == null ? "" : arn);
        if (!matcher.matches() || !matcher.group(1).equals(accountId)) {
            return Optional.empty();
        }
        Optional<IamSamlProvider> found = providers instanceof AccountAwareStorageBackend<IamSamlProvider> aware
                ? aware.getForAccount(accountId, arn) : providers.get(arn);
        return found.map(provider -> {
            if (provider.getEntityId() == null && provider.getMetadataDocument() != null) {
                updateLegacyMetadata(accountId, provider);
            }
            return provider;
        });
    }

    private void updateLegacyMetadata(String accountId, IamSamlProvider provider) {
        try {
            SAMLMetadata.Parsed parsed = SAMLMetadata.parse(provider.getMetadataDocument());
            provider.setEntityId(parsed.entityId());
            provider.setCertificate(parsed.certificateBase64());
            save(accountId, provider);
        } catch (Exception e) {
            throw new AwsException("InvalidInput", "The SAML metadata document is invalid.", 400);
        }
    }

    public List<IamSamlProvider> list(String accountId) {
        if (providers instanceof AccountAwareStorageBackend<IamSamlProvider> aware) {
            return aware.scanForAccount(accountId, k -> true);
        }
        return providers.scan(k -> k.startsWith("arn:aws:iam::" + accountId + ":saml-provider/"));
    }

    public IamSamlProvider getForAccount(String accountId, String arn) {
        return findForAccount(accountId, arn).orElseThrow(() -> new AwsException("NoSuchEntity",
                "The SAML provider with ARN " + arn + " cannot be found.", 404));
    }

    public void update(String accountId, String arn, String metadata, String encryptionMode) {
        synchronized (providerLock) {
            IamSamlProvider provider = getForAccount(accountId, arn);
            if (metadata != null) {
                SAMLMetadata.Parsed parsed;
                try {
                    parsed = SAMLMetadata.parse(metadata);
                } catch (Exception e) {
                    throw new AwsException("InvalidInput", "The SAML metadata document is invalid.", 400);
                }
                provider.setMetadataDocument(metadata);
                provider.setEntityId(parsed.entityId());
                provider.setCertificate(parsed.certificateBase64());
            }
            if (encryptionMode != null) {
                provider.setAssertionEncryptionMode(encryptionMode);
            }
            save(accountId, provider);
        }
    }

    public void tag(String accountId, String arn, Map<String, String> tags) {
        synchronized (providerLock) {
            IamSamlProvider provider = getForAccount(accountId, arn);
            provider.getTags().putAll(tags);
            save(accountId, provider);
        }
    }

    public void untag(String accountId, String arn, List<String> keys) {
        synchronized (providerLock) {
            IamSamlProvider provider = getForAccount(accountId, arn);
            keys.forEach(provider.getTags()::remove);
            save(accountId, provider);
        }
    }

    public void delete(String accountId, String arn) {
        synchronized (providerLock) {
            getForAccount(accountId, arn);
            if (providers instanceof AccountAwareStorageBackend<IamSamlProvider> aware) {
                aware.deleteForAccount(accountId, arn);
            } else {
                providers.delete(arn);
            }
        }
    }

    private void save(String accountId, IamSamlProvider provider) {
        if (providers instanceof AccountAwareStorageBackend<IamSamlProvider> aware) {
            aware.putForAccount(accountId, provider.getArn(), provider);
        } else {
            providers.put(provider.getArn(), provider);
        }
    }

    /** Metadata parser shared by provider registration and the assertion verifier. */
    static final class SAMLMetadata {
        private SAMLMetadata() {
        }
        record Parsed(String entityId, String certificateBase64) {}

        static Parsed parse(String metadata) throws Exception {
            var doc = SAMLXml.document(metadata);
            var entity = doc.getDocumentElement().getAttribute("entityID");
            var cert = SAMLXml.text(doc, "X509Certificate");
            if (entity == null || entity.isBlank() || cert == null || cert.isBlank()) {
                throw new Exception();
            }
            Base64.getDecoder().decode(cert.replaceAll("\\s+", ""));
            return new Parsed(entity, cert.replaceAll("\\s+", ""));
        }
    }
}
