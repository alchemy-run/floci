package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.dynamodb.model.PointInTimeChange;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The change history behind point-in-time recovery. A change keeps what it replaced, so a table at
 * a time is the live table with every later change undone. Each change is stored on its own, under
 * {@code <storage key>#<sequence>}.
 */
final class DynamoDbPointInTimeRecovery {

    record RestoreWindow(Instant earliest, Instant latest) {}

    /** The changes of one table, oldest first. */
    private static final class Changes {
        private final List<PointInTimeChange> list = new ArrayList<>();
        private long nextSequence;
        private boolean dropped;
    }

    private final StorageBackend<String, PointInTimeChange> store;
    private final Duration latestLag;
    private final Clock clock;
    // Keyed like DynamoDbService.itemsByTable: "<account>/<region>::<table>".
    private final ConcurrentHashMap<String, Changes> changesByTable = new ConcurrentHashMap<>();

    DynamoDbPointInTimeRecovery(StorageBackend<String, PointInTimeChange> store, String defaultAccountId,
                                Duration latestLag, Clock clock) {
        this.store = store;
        this.latestLag = latestLag;
        this.clock = clock;
        Map<String, PointInTimeChange> stored = new HashMap<>();
        if (store instanceof AccountAwareStorageBackend<PointInTimeChange> aware) {
            stored.putAll(aware.scanAllAccountsRaw());
        } else {
            for (String key : store.keys()) {
                store.get(key).ifPresent(change -> stored.put(defaultAccountId + "/" + key, change));
            }
        }
        stored.forEach((key, change) -> changesByTable
                .computeIfAbsent(key.substring(0, key.lastIndexOf('#')), ignored -> new Changes())
                .list.add(change));
        for (Changes changes : changesByTable.values()) {
            changes.list.sort(Comparator.comparingLong(PointInTimeChange::getSequence));
            changes.nextSequence = changes.list.getLast().getSequence() + 1;
        }
    }

    void clear() {
        changesByTable.clear();
    }

    /** Call after point-in-time recovery of the table is turned off, so no writer records again. */
    void drop(String accountId, String storageKey) {
        Changes changes = changesByTable.remove(key(accountId, storageKey));
        if (changes == null) {
            return;
        }
        synchronized (changes) {
            changes.dropped = true;
            for (PointInTimeChange change : changes.list) {
                delete(accountId, storageKey, change);
            }
        }
    }

    /** Call before the item is written. */
    void recordItem(String accountId, String storageKey, TableDefinition table, String itemKey, JsonNode oldItem) {
        if (!table.isPointInTimeRecoveryEnabled()) {
            return;
        }
        Changes changes = changesByTable.computeIfAbsent(key(accountId, storageKey), ignored -> new Changes());
        synchronized (changes) {
            if (changes.dropped || !table.isPointInTimeRecoveryEnabled()) {
                return;
            }
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            PointInTimeChange change = new PointInTimeChange(changes.nextSequence++, now, itemKey,
                    oldItem != null ? oldItem.deepCopy() : null);
            changes.list.add(change);
            put(accountId, storageKey, change);
            Instant cutoff = now.minus(recoveryPeriod(table));
            int expired = 0;
            while (expired < changes.list.size() && changes.list.get(expired).getTime().isBefore(cutoff)) {
                delete(accountId, storageKey, changes.list.get(expired));
                expired++;
            }
            changes.list.subList(0, expired).clear();
        }
    }

    /** AWS words an InvalidRestoreTimeException with the latest time to the nanosecond. */
    RestoreWindow window(TableDefinition table) {
        Instant now = clock.instant();
        Instant earliest = later(table.getPointInTimeRecoveryStartDateTime(), now.minus(recoveryPeriod(table)));
        return new RestoreWindow(earliest, later(earliest, now.minus(latestLag)));
    }

    /**
     * An item changed after {@code time} takes the value that its first change after it replaced.
     * Writers record a change before they write the item, so the live items are copied first.
     */
    List<JsonNode> itemsAt(String accountId, String storageKey, Map<String, JsonNode> liveItems, Instant time) {
        Map<String, JsonNode> items = new HashMap<>(liveItems);
        Changes changes = changesByTable.get(key(accountId, storageKey));
        if (changes != null) {
            Set<String> undone = new HashSet<>();
            synchronized (changes) {
                for (PointInTimeChange change : changes.list) {
                    String itemKey = change.getItemKey();
                    if (!change.getTime().isAfter(time) || !undone.add(itemKey)) {
                        continue;
                    }
                    if (change.getOldItem() == null) {
                        items.remove(itemKey);
                    } else {
                        items.put(itemKey, change.getOldItem());
                    }
                }
            }
        }
        return new ArrayList<>(items.values());
    }

    private void put(String accountId, String storageKey, PointInTimeChange change) {
        String key = storageKey + "#" + change.getSequence();
        if (store instanceof AccountAwareStorageBackend<PointInTimeChange> aware) {
            aware.putForAccount(accountId, key, change);
        } else {
            store.put(key, change);
        }
    }

    private void delete(String accountId, String storageKey, PointInTimeChange change) {
        String key = storageKey + "#" + change.getSequence();
        if (store instanceof AccountAwareStorageBackend<PointInTimeChange> aware) {
            aware.deleteForAccount(accountId, key);
        } else {
            store.delete(key);
        }
    }

    private static Duration recoveryPeriod(TableDefinition table) {
        return Duration.ofDays(table.getPointInTimeRecoveryRecoveryPeriodInDays());
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static String key(String accountId, String storageKey) {
        return accountId + "/" + storageKey;
    }
}
