package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Region-local catalog keys over the factory's account-aware backend. */
final class GlueCatalogStorage<V> implements StorageBackend<String, V> {

    // NUL is not valid in Glue names, so regional keys cannot collide with legacy names.
    private static final String REGION_PREFIX = "\0region\0";

    private final StorageBackend<String, V> delegate;
    private final RegionResolver regionResolver;

    GlueCatalogStorage(StorageBackend<String, V> delegate, RegionResolver regionResolver) {
        this.delegate = delegate;
        this.regionResolver = regionResolver;
        migrateLegacyAccounts();
    }

    private void migrateLegacyAccounts() {
        if (delegate instanceof AccountAwareStorageBackend<V> accounts) {
            Map<String, V> raw = accounts.scanAllAccountsWithRawKeys();
            for (AccountAwareStorageBackend.AccountEntry<V> entry : accounts.scanAllAccountEntries(key -> true)) {
                if (regionResolver.getDefaultAccountId().equals(entry.accountId()) && raw.containsKey(entry.key())) {
                    // Pre-account records belong to the configured default account, not the first reader.
                    accounts.migrateLegacyEntries(entry.accountId(), entry.key()::equals,
                            ignored -> entry.key(), ignored -> true);
                }
            }
        }
    }

    private static String prefix(String region) {
        return REGION_PREFIX + region + "\0";
    }

    private boolean isDefaultRegion(String region) {
        return regionResolver.getDefaultRegion().equals(region);
    }

    @Override
    public void put(String key, V value) {
        String region = regionResolver.getRegion();
        synchronized (delegate) {
            delegate.put(prefix(region) + key, value);
            if (isDefaultRegion(region) && delegate.get(key).isPresent()) {
                delegate.delete(key);
            }
        }
    }

    @Override
    public Optional<V> get(String key) {
        String region = regionResolver.getRegion();
        String scopedKey = prefix(region) + key;
        synchronized (delegate) {
            Optional<V> scoped = delegate.get(scopedKey);
            if (!isDefaultRegion(region)) {
                return scoped;
            }
            // Legacy catalog keys have no region metadata and belong only to the default region.
            Optional<V> legacy = delegate.get(key);
            if (legacy.isPresent()) {
                if (scoped.isEmpty()) {
                    delegate.put(scopedKey, legacy.get());
                    scoped = legacy;
                }
                delegate.delete(key);
            }
            return scoped;
        }
    }

    @Override
    public void delete(String key) {
        String region = regionResolver.getRegion();
        synchronized (delegate) {
            delegate.delete(prefix(region) + key);
            if (isDefaultRegion(region) && delegate.get(key).isPresent()) {
                delegate.delete(key);
            }
        }
    }

    @Override
    public List<V> scan(Predicate<String> keyFilter) {
        List<V> values = new ArrayList<>();
        for (String key : keys()) {
            if (keyFilter.test(key)) {
                get(key).ifPresent(values::add);
            }
        }
        return values;
    }

    @Override
    public Set<String> keys() {
        String region = regionResolver.getRegion();
        String prefix = prefix(region);
        return delegate.keys().stream()
                .filter(key -> key.startsWith(prefix) || (isDefaultRegion(region) && !key.startsWith(REGION_PREFIX)))
                .map(key -> key.startsWith(prefix) ? key.substring(prefix.length()) : key)
                .collect(Collectors.toSet());
    }

    @Override
    public void flush() {
        delegate.flush();
    }

    @Override
    public void load() {
        delegate.load();
        migrateLegacyAccounts();
    }

    @Override
    public void clear() {
        keys().forEach(this::delete);
    }
}
