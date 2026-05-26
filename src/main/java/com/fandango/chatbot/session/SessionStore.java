package com.fandango.chatbot.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);
    private static final long TTL_SECONDS = 1800; // 30 minutes

    private final ConcurrentHashMap<String, ChatSession> sessions = new ConcurrentHashMap<>();

    public ChatSession getOrCreate(String sessionId) {
        if (sessionId != null && sessions.containsKey(sessionId)) {
            ChatSession session = sessions.get(sessionId);
            session.touch();
            return session;
        }
        String newId = UUID.randomUUID().toString();
        ChatSession session = new ChatSession();
        // store under the new ID; caller retrieves it via the session object
        session.touch();
        sessions.put(newId, session);
        return session;
    }

    public String getOrCreateId(String sessionId) {
        if (sessionId != null && sessions.containsKey(sessionId)) {
            return sessionId;
        }
        String newId = UUID.randomUUID().toString();
        sessions.put(newId, new ChatSession());
        return newId;
    }

    public ChatSession get(String sessionId) {
        ChatSession session = sessions.get(sessionId);
        if (session != null) session.touch();
        return session;
    }

    public void put(String sessionId, ChatSession session) {
        sessions.put(sessionId, session);
    }

    @Scheduled(fixedDelay = 300_000) // runs every 5 minutes
    public void evictExpired() {
        int before = sessions.size();
        sessions.entrySet().removeIf(e -> e.getValue().isExpired(TTL_SECONDS));
        int evicted = before - sessions.size();
        if (evicted > 0) {
            log.debug("Evicted {} expired sessions", evicted);
        }
    }
}
