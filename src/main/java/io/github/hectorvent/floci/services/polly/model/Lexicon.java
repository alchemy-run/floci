package io.github.hectorvent.floci.services.polly.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A stored Amazon Polly pronunciation lexicon (W3C PLS document plus attributes).
 */
@RegisterForReflection
public class Lexicon {

    private String name;
    private String content;
    private String alphabet;
    private String languageCode;
    private long lastModified;
    private String lexiconArn;
    private int lexemesCount;
    private int size;
    private String region;

    public Lexicon() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getAlphabet() {
        return alphabet;
    }

    public void setAlphabet(String alphabet) {
        this.alphabet = alphabet;
    }

    public String getLanguageCode() {
        return languageCode;
    }

    public void setLanguageCode(String languageCode) {
        this.languageCode = languageCode;
    }

    public long getLastModified() {
        return lastModified;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    public String getLexiconArn() {
        return lexiconArn;
    }

    public void setLexiconArn(String lexiconArn) {
        this.lexiconArn = lexiconArn;
    }

    public int getLexemesCount() {
        return lexemesCount;
    }

    public void setLexemesCount(int lexemesCount) {
        this.lexemesCount = lexemesCount;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }
}
