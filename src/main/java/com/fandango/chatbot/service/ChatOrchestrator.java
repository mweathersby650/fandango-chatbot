package com.fandango.chatbot.service;

import com.fandango.chatbot.model.*;
import com.fandango.chatbot.session.ChatSession;
import com.fandango.chatbot.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);
    private static final int PAGE_SIZE = 20;

    private final SessionStore sessionStore;
    private final IntentExtractionService intentExtractionService;
    private final VuduContentService vuduContentService;
    private final ResponseFormatterService responseFormatterService;

    public ChatOrchestrator(SessionStore sessionStore,
                            IntentExtractionService intentExtractionService,
                            VuduContentService vuduContentService,
                            ResponseFormatterService responseFormatterService) {
        this.sessionStore = sessionStore;
        this.intentExtractionService = intentExtractionService;
        this.vuduContentService = vuduContentService;
        this.responseFormatterService = responseFormatterService;
    }

    public ChatResponse handle(ChatRequest request) {
        String sessionId = request.sessionId() != null ? request.sessionId() : UUID.randomUUID().toString();
        ChatSession session = sessionStore.get(sessionId);
        if (session == null) {
            session = new ChatSession();
            sessionStore.put(sessionId, session);
        }

        // 1. Extract intent
        ContentFilters extracted = intentExtractionService.extract(request.message(), session.getActiveFilters());
        log.debug("Extracted filters: {}", extracted);

        // 2. Determine offset
        int offset;
        ContentFilters filtersToUse;

        switch (extracted.intent()) {
            case MORE_RESULTS -> {
                offset = session.getCurrentOffset() + PAGE_SIZE;
                filtersToUse = session.getActiveFilters().withIntent(IntentType.MORE_RESULTS);
                session.setCurrentOffset(offset);
            }
            case MORE_LIKE_THIS -> {
                offset = 0;
                filtersToUse = buildMoreLikeThis(session.getLastResults(), session.getActiveFilters());
                session.setCurrentOffset(0);
            }
            case NEW_SEARCH -> {
                offset = 0;
                filtersToUse = extracted;
                session.setCurrentOffset(0);
                session.getHistory().clear();
            }
            default -> { // REFINE
                offset = 0;
                filtersToUse = extracted;
                session.setCurrentOffset(0);
            }
        }

        // 3. Query Vudu
        List<VuduContent> results = vuduContentService.search(filtersToUse, offset);

        // 4. Format reply
        String reply = responseFormatterService.format(request.message(), results);

        // 5. Update session
        session.setActiveFilters(filtersToUse);
        session.setLastResults(results);
        session.addMessage(new UserMessage(request.message()));
        session.addMessage(new AssistantMessage(reply));
        session.touch();

        boolean moreAvailable = results.size() == PAGE_SIZE;
        return new ChatResponse(sessionId, reply, results, moreAvailable);
    }

    private ContentFilters buildMoreLikeThis(List<VuduContent> lastResults, ContentFilters active) {
        if (lastResults == null || lastResults.isEmpty()) return active;
        // use the most common genre from last results as the new genre filter
        String genre = lastResults.stream()
                .flatMap(c -> c.genres() != null ? c.genres().stream() : java.util.stream.Stream.of())
                .collect(java.util.stream.Collectors.groupingBy(g -> g, java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey)
                .orElse(active.genre());

        return new ContentFilters(active.superType(), genre, active.mpaaRating(),
                active.maxPrice(), active.offerType(), active.minVideoQuality(),
                active.yearFrom(), active.yearTo(), active.sortBy(), IntentType.MORE_LIKE_THIS);
    }
}
