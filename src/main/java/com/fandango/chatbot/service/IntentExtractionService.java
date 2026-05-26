package com.fandango.chatbot.service;

import com.fandango.chatbot.model.ContentFilters;
import com.fandango.chatbot.model.IntentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
public class IntentExtractionService {

    private static final Logger log = LoggerFactory.getLogger(IntentExtractionService.class);

    private final ChatModel chatModel;
    private final BeanOutputConverter<ContentFilters> outputConverter;
    private final String systemPromptTemplate;

    public IntentExtractionService(ChatModel chatModel, ObjectMapper objectMapper) throws IOException {
        this.chatModel = chatModel;
        this.outputConverter = new BeanOutputConverter<>(ContentFilters.class, objectMapper);
        this.systemPromptTemplate = new ClassPathResource("prompts/intent-extraction.st")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    public ContentFilters extract(String userMessage, ContentFilters activeFilters) {
        try {
            String activeFiltersJson = serializeFilters(activeFilters);
            String format = outputConverter.getFormat();

            PromptTemplate template = new PromptTemplate(systemPromptTemplate);
            String systemContent = template.render(Map.of(
                    "activeFilters", activeFiltersJson,
                    "format", format
            ));

            String response = chatModel.call(
                    new org.springframework.ai.chat.prompt.Prompt(
                            List.of(
                                    new org.springframework.ai.chat.messages.SystemMessage(systemContent),
                                    new org.springframework.ai.chat.messages.UserMessage(userMessage)
                            )
                    )
            ).getResult().getOutput().getText();

            // small models often wrap JSON in ```json ... ``` fences — strip them
            response = response.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("(?s)\\s*```$", "").trim();
            log.debug("Intent extraction response: {}", response);
            ContentFilters result = outputConverter.convert(response);
            // guard against model omitting the intent field
            if (result.intent() == null) {
                result = result.withIntent(IntentType.NEW_SEARCH);
            }
            return result;

        } catch (Exception e) {
            log.error("Intent extraction failed, defaulting to NEW_SEARCH", e);
            return ContentFilters.empty();
        }
    }

    private String serializeFilters(ContentFilters filters) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(filters);
        } catch (Exception e) {
            return "{}";
        }
    }
}
