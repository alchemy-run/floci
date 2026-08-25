package io.github.hectorvent.floci.services.lexv2.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An Amazon Lex V2 bot plus DRAFT/versioned locales, intents, aliases, and
 * numbered version snapshots.
 */
@RegisterForReflection
public class Bot {

    private String botId;
    private String botName;
    private String description;
    private String roleArn;
    private boolean childDirected;
    private int idleSessionTTLInSeconds;
    private String botStatus;
    private String region;
    private String accountId;
    private long creationDateTime;
    private long lastUpdatedDateTime;
    private Map<String, String> tags = new LinkedHashMap<>();
    private int nextVersion = 1;
    /** version -> localeId -> locale (includes {@code DRAFT}). */
    private Map<String, Map<String, Locale>> locales = new LinkedHashMap<>();
    private Map<String, Alias> aliases = new LinkedHashMap<>();
    private Map<String, Version> versions = new LinkedHashMap<>();

    public Bot() {
    }

    public String getBotId() {
        return botId;
    }

    public void setBotId(String botId) {
        this.botId = botId;
    }

    public String getBotName() {
        return botName;
    }

    public void setBotName(String botName) {
        this.botName = botName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public boolean isChildDirected() {
        return childDirected;
    }

    public void setChildDirected(boolean childDirected) {
        this.childDirected = childDirected;
    }

    public int getIdleSessionTTLInSeconds() {
        return idleSessionTTLInSeconds;
    }

    public void setIdleSessionTTLInSeconds(int idleSessionTTLInSeconds) {
        this.idleSessionTTLInSeconds = idleSessionTTLInSeconds;
    }

    public String getBotStatus() {
        return botStatus;
    }

    public void setBotStatus(String botStatus) {
        this.botStatus = botStatus;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public long getCreationDateTime() {
        return creationDateTime;
    }

    public void setCreationDateTime(long creationDateTime) {
        this.creationDateTime = creationDateTime;
    }

    public long getLastUpdatedDateTime() {
        return lastUpdatedDateTime;
    }

    public void setLastUpdatedDateTime(long lastUpdatedDateTime) {
        this.lastUpdatedDateTime = lastUpdatedDateTime;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public int getNextVersion() {
        return nextVersion;
    }

    public void setNextVersion(int nextVersion) {
        this.nextVersion = nextVersion;
    }

    public Map<String, Map<String, Locale>> getLocales() {
        return locales;
    }

    public void setLocales(Map<String, Map<String, Locale>> locales) {
        this.locales = locales == null ? new LinkedHashMap<>() : locales;
    }

    public Map<String, Alias> getAliases() {
        return aliases;
    }

    public void setAliases(Map<String, Alias> aliases) {
        this.aliases = aliases == null ? new LinkedHashMap<>() : aliases;
    }

    public Map<String, Version> getVersions() {
        return versions;
    }

    public void setVersions(Map<String, Version> versions) {
        this.versions = versions == null ? new LinkedHashMap<>() : versions;
    }

    @RegisterForReflection
    public static class Locale {
        private String localeId;
        private String localeName;
        private String description;
        private double nluIntentConfidenceThreshold = 0.4;
        private String botLocaleStatus;
        private String voiceId;
        private String voiceEngine;
        private long creationDateTime;
        private long lastUpdatedDateTime;
        private Map<String, Intent> intents = new LinkedHashMap<>();
        private Map<String, SlotType> slotTypes = new LinkedHashMap<>();

        public Locale() {
        }

        public Locale copy() {
            Locale copy = new Locale();
            copy.localeId = localeId;
            copy.localeName = localeName;
            copy.description = description;
            copy.nluIntentConfidenceThreshold = nluIntentConfidenceThreshold;
            copy.botLocaleStatus = botLocaleStatus;
            copy.voiceId = voiceId;
            copy.voiceEngine = voiceEngine;
            copy.creationDateTime = creationDateTime;
            copy.lastUpdatedDateTime = lastUpdatedDateTime;
            copy.intents = new LinkedHashMap<>();
            for (Map.Entry<String, Intent> entry : intents.entrySet()) {
                copy.intents.put(entry.getKey(), entry.getValue().copy());
            }
            copy.slotTypes = new LinkedHashMap<>();
            for (Map.Entry<String, SlotType> entry : slotTypes.entrySet()) {
                copy.slotTypes.put(entry.getKey(), entry.getValue().copy());
            }
            return copy;
        }

        public String getLocaleId() {
            return localeId;
        }

        public void setLocaleId(String localeId) {
            this.localeId = localeId;
        }

        public String getLocaleName() {
            return localeName;
        }

        public void setLocaleName(String localeName) {
            this.localeName = localeName;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public double getNluIntentConfidenceThreshold() {
            return nluIntentConfidenceThreshold;
        }

        public void setNluIntentConfidenceThreshold(double nluIntentConfidenceThreshold) {
            this.nluIntentConfidenceThreshold = nluIntentConfidenceThreshold;
        }

        public String getBotLocaleStatus() {
            return botLocaleStatus;
        }

        public void setBotLocaleStatus(String botLocaleStatus) {
            this.botLocaleStatus = botLocaleStatus;
        }

        public String getVoiceId() {
            return voiceId;
        }

        public void setVoiceId(String voiceId) {
            this.voiceId = voiceId;
        }

        public String getVoiceEngine() {
            return voiceEngine;
        }

        public void setVoiceEngine(String voiceEngine) {
            this.voiceEngine = voiceEngine;
        }

        public long getCreationDateTime() {
            return creationDateTime;
        }

        public void setCreationDateTime(long creationDateTime) {
            this.creationDateTime = creationDateTime;
        }

        public long getLastUpdatedDateTime() {
            return lastUpdatedDateTime;
        }

        public void setLastUpdatedDateTime(long lastUpdatedDateTime) {
            this.lastUpdatedDateTime = lastUpdatedDateTime;
        }

        public Map<String, Intent> getIntents() {
            return intents;
        }

        public void setIntents(Map<String, Intent> intents) {
            this.intents = intents == null ? new LinkedHashMap<>() : intents;
        }

        public Map<String, SlotType> getSlotTypes() {
            if (slotTypes == null) {
                slotTypes = new LinkedHashMap<>();
            }
            return slotTypes;
        }

        public void setSlotTypes(Map<String, SlotType> slotTypes) {
            this.slotTypes = slotTypes == null ? new LinkedHashMap<>() : slotTypes;
        }
    }

    @RegisterForReflection
    public static class Intent {
        private String intentId;
        private String intentName;
        private String description;
        private String parentIntentSignature;
        private List<String> sampleUtterances = new ArrayList<>();
        private boolean dialogCodeHook;
        private boolean fulfillmentCodeHook;
        private long creationDateTime;
        private long lastUpdatedDateTime;

        public Intent() {
        }

        public Intent copy() {
            Intent copy = new Intent();
            copy.intentId = intentId;
            copy.intentName = intentName;
            copy.description = description;
            copy.parentIntentSignature = parentIntentSignature;
            copy.sampleUtterances = new ArrayList<>(sampleUtterances);
            copy.dialogCodeHook = dialogCodeHook;
            copy.fulfillmentCodeHook = fulfillmentCodeHook;
            copy.creationDateTime = creationDateTime;
            copy.lastUpdatedDateTime = lastUpdatedDateTime;
            return copy;
        }

        public String getIntentId() {
            return intentId;
        }

        public void setIntentId(String intentId) {
            this.intentId = intentId;
        }

        public String getIntentName() {
            return intentName;
        }

        public void setIntentName(String intentName) {
            this.intentName = intentName;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getParentIntentSignature() {
            return parentIntentSignature;
        }

        public void setParentIntentSignature(String parentIntentSignature) {
            this.parentIntentSignature = parentIntentSignature;
        }

        public List<String> getSampleUtterances() {
            return sampleUtterances;
        }

        public void setSampleUtterances(List<String> sampleUtterances) {
            this.sampleUtterances = sampleUtterances == null ? new ArrayList<>() : new ArrayList<>(sampleUtterances);
        }

        public boolean isDialogCodeHook() {
            return dialogCodeHook;
        }

        public void setDialogCodeHook(boolean dialogCodeHook) {
            this.dialogCodeHook = dialogCodeHook;
        }

        public boolean isFulfillmentCodeHook() {
            return fulfillmentCodeHook;
        }

        public void setFulfillmentCodeHook(boolean fulfillmentCodeHook) {
            this.fulfillmentCodeHook = fulfillmentCodeHook;
        }

        public long getCreationDateTime() {
            return creationDateTime;
        }

        public void setCreationDateTime(long creationDateTime) {
            this.creationDateTime = creationDateTime;
        }

        public long getLastUpdatedDateTime() {
            return lastUpdatedDateTime;
        }

        public void setLastUpdatedDateTime(long lastUpdatedDateTime) {
            this.lastUpdatedDateTime = lastUpdatedDateTime;
        }
    }

    @RegisterForReflection
    public static class SlotType {
        private String slotTypeId;
        private String slotTypeName;
        private String description;
        private List<Map<String, Object>> slotTypeValues = new ArrayList<>();
        private Map<String, Object> valueSelectionSetting = new LinkedHashMap<>();
        private String parentSlotTypeSignature;
        private long creationDateTime;
        private long lastUpdatedDateTime;

        public SlotType() {
        }

        public SlotType copy() {
            SlotType copy = new SlotType();
            copy.slotTypeId = slotTypeId;
            copy.slotTypeName = slotTypeName;
            copy.description = description;
            copy.slotTypeValues = new ArrayList<>();
            for (Map<String, Object> value : slotTypeValues) {
                copy.slotTypeValues.add(new LinkedHashMap<>(value));
            }
            copy.valueSelectionSetting = new LinkedHashMap<>(valueSelectionSetting);
            copy.parentSlotTypeSignature = parentSlotTypeSignature;
            copy.creationDateTime = creationDateTime;
            copy.lastUpdatedDateTime = lastUpdatedDateTime;
            return copy;
        }

        public String getSlotTypeId() {
            return slotTypeId;
        }

        public void setSlotTypeId(String slotTypeId) {
            this.slotTypeId = slotTypeId;
        }

        public String getSlotTypeName() {
            return slotTypeName;
        }

        public void setSlotTypeName(String slotTypeName) {
            this.slotTypeName = slotTypeName;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public List<Map<String, Object>> getSlotTypeValues() {
            return slotTypeValues;
        }

        public void setSlotTypeValues(List<Map<String, Object>> slotTypeValues) {
            this.slotTypeValues = slotTypeValues == null ? new ArrayList<>() : new ArrayList<>(slotTypeValues);
        }

        public Map<String, Object> getValueSelectionSetting() {
            return valueSelectionSetting;
        }

        public void setValueSelectionSetting(Map<String, Object> valueSelectionSetting) {
            this.valueSelectionSetting = valueSelectionSetting == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(valueSelectionSetting);
        }

        public String getParentSlotTypeSignature() {
            return parentSlotTypeSignature;
        }

        public void setParentSlotTypeSignature(String parentSlotTypeSignature) {
            this.parentSlotTypeSignature = parentSlotTypeSignature;
        }

        public long getCreationDateTime() {
            return creationDateTime;
        }

        public void setCreationDateTime(long creationDateTime) {
            this.creationDateTime = creationDateTime;
        }

        public long getLastUpdatedDateTime() {
            return lastUpdatedDateTime;
        }

        public void setLastUpdatedDateTime(long lastUpdatedDateTime) {
            this.lastUpdatedDateTime = lastUpdatedDateTime;
        }
    }

    @RegisterForReflection
    public static class Alias {
        private String botAliasId;
        private String botAliasName;
        private String description;
        private String botVersion;
        private String botAliasStatus;
        private Map<String, Object> botAliasLocaleSettings = new LinkedHashMap<>();
        private Map<String, String> tags = new LinkedHashMap<>();
        private long creationDateTime;
        private long lastUpdatedDateTime;

        public Alias() {
        }

        public String getBotAliasId() {
            return botAliasId;
        }

        public void setBotAliasId(String botAliasId) {
            this.botAliasId = botAliasId;
        }

        public String getBotAliasName() {
            return botAliasName;
        }

        public void setBotAliasName(String botAliasName) {
            this.botAliasName = botAliasName;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getBotVersion() {
            return botVersion;
        }

        public void setBotVersion(String botVersion) {
            this.botVersion = botVersion;
        }

        public String getBotAliasStatus() {
            return botAliasStatus;
        }

        public void setBotAliasStatus(String botAliasStatus) {
            this.botAliasStatus = botAliasStatus;
        }

        public Map<String, Object> getBotAliasLocaleSettings() {
            return botAliasLocaleSettings;
        }

        public void setBotAliasLocaleSettings(Map<String, Object> botAliasLocaleSettings) {
            this.botAliasLocaleSettings = botAliasLocaleSettings == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(botAliasLocaleSettings);
        }

        public Map<String, String> getTags() {
            return tags;
        }

        public void setTags(Map<String, String> tags) {
            this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
        }

        public long getCreationDateTime() {
            return creationDateTime;
        }

        public void setCreationDateTime(long creationDateTime) {
            this.creationDateTime = creationDateTime;
        }

        public long getLastUpdatedDateTime() {
            return lastUpdatedDateTime;
        }

        public void setLastUpdatedDateTime(long lastUpdatedDateTime) {
            this.lastUpdatedDateTime = lastUpdatedDateTime;
        }
    }

    @RegisterForReflection
    public static class Version {
        private String botVersion;
        private String description;
        private String botStatus;
        private long creationDateTime;

        public Version() {
        }

        public String getBotVersion() {
            return botVersion;
        }

        public void setBotVersion(String botVersion) {
            this.botVersion = botVersion;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getBotStatus() {
            return botStatus;
        }

        public void setBotStatus(String botStatus) {
            this.botStatus = botStatus;
        }

        public long getCreationDateTime() {
            return creationDateTime;
        }

        public void setCreationDateTime(long creationDateTime) {
            this.creationDateTime = creationDateTime;
        }
    }
}
