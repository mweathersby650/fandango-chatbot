package com.fandango.chatbot.session;

import com.fandango.chatbot.model.ContentFilters;
import com.fandango.chatbot.model.VuduContent;
import org.springframework.ai.chat.messages.Message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class ChatSession {

    private ContentFilters activeFilters;
    private final List<Message> history;
    private List<VuduContent> lastResults;
    private int currentOffset;
    private volatile Instant lastActive;

    public ChatSession() {
        this.activeFilters = ContentFilters.empty();
        this.history = new ArrayList<>();
        this.lastResults = new ArrayList<>();
        this.currentOffset = 0;
        this.lastActive = Instant.now();
    }

    public void touch() {
        this.lastActive = Instant.now();
    }

    public boolean isExpired(long ttlSeconds) {
        return Instant.now().isAfter(lastActive.plusSeconds(ttlSeconds));
    }

    public ContentFilters getActiveFilters() { return activeFilters; }
    public void setActiveFilters(ContentFilters filters) { this.activeFilters = filters; }

    public List<Message> getHistory() { return history; }
    public void addMessage(Message message) { history.add(message); }

    public List<VuduContent> getLastResults() { return lastResults; }
    public void setLastResults(List<VuduContent> results) { this.lastResults = results; }

    public int getCurrentOffset() { return currentOffset; }
    public void setCurrentOffset(int offset) { this.currentOffset = offset; }
    public void incrementOffset(int count) { this.currentOffset += count; }
}
