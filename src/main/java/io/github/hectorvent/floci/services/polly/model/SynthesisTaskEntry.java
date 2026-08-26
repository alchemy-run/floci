package io.github.hectorvent.floci.services.polly.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** An Amazon Polly asynchronous speech synthesis task. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SynthesisTaskEntry {

    private String taskId;
    private String engine;
    private String taskStatus;
    private String taskStatusReason;
    private String outputUri;
    private String outputFormat;
    private String sampleRate;
    private String textType;
    private String voiceId;
    private String languageCode;
    private String snsTopicArn;
    private List<String> lexiconNames = new ArrayList<>();
    private List<String> speechMarkTypes = new ArrayList<>();
    private int requestCharacters;
    private long creationTime;

    public SynthesisTaskEntry() {
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public String getTaskStatus() {
        return taskStatus;
    }

    public void setTaskStatus(String taskStatus) {
        this.taskStatus = taskStatus;
    }

    public String getTaskStatusReason() {
        return taskStatusReason;
    }

    public void setTaskStatusReason(String taskStatusReason) {
        this.taskStatusReason = taskStatusReason;
    }

    public String getOutputUri() {
        return outputUri;
    }

    public void setOutputUri(String outputUri) {
        this.outputUri = outputUri;
    }

    public String getOutputFormat() {
        return outputFormat;
    }

    public void setOutputFormat(String outputFormat) {
        this.outputFormat = outputFormat;
    }

    public String getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(String sampleRate) {
        this.sampleRate = sampleRate;
    }

    public String getTextType() {
        return textType;
    }

    public void setTextType(String textType) {
        this.textType = textType;
    }

    public String getVoiceId() {
        return voiceId;
    }

    public void setVoiceId(String voiceId) {
        this.voiceId = voiceId;
    }

    public String getLanguageCode() {
        return languageCode;
    }

    public void setLanguageCode(String languageCode) {
        this.languageCode = languageCode;
    }

    public String getSnsTopicArn() {
        return snsTopicArn;
    }

    public void setSnsTopicArn(String snsTopicArn) {
        this.snsTopicArn = snsTopicArn;
    }

    public List<String> getLexiconNames() {
        return lexiconNames;
    }

    public void setLexiconNames(List<String> lexiconNames) {
        this.lexiconNames = lexiconNames == null ? new ArrayList<>() : new ArrayList<>(lexiconNames);
    }

    public List<String> getSpeechMarkTypes() {
        return speechMarkTypes;
    }

    public void setSpeechMarkTypes(List<String> speechMarkTypes) {
        this.speechMarkTypes = speechMarkTypes == null ? new ArrayList<>() : new ArrayList<>(speechMarkTypes);
    }

    public int getRequestCharacters() {
        return requestCharacters;
    }

    public void setRequestCharacters(int requestCharacters) {
        this.requestCharacters = requestCharacters;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(long creationTime) {
        this.creationTime = creationTime;
    }
}
