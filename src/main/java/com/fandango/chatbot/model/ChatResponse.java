package com.fandango.chatbot.model;

import java.util.List;

public record ChatResponse(
        String sessionId,
        String reply,
        List<VuduContent> results,
        boolean moreAvailable
) {}
