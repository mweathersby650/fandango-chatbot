package com.fandango.chatbot.service;

import com.fandango.chatbot.model.VuduContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ResponseFormatterService {

    private static final Logger log = LoggerFactory.getLogger(ResponseFormatterService.class);

    private final ChatModel chatModel;
    private final String promptTemplate;

    public ResponseFormatterService(ChatModel chatModel) throws IOException {
        this.chatModel = chatModel;
        this.promptTemplate = new ClassPathResource("prompts/response-generation.st")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    public String format(String userMessage, List<VuduContent> results) {
        try {
            String resultsSummary = summarize(results);
            PromptTemplate template = new PromptTemplate(promptTemplate);
            String systemContent = template.render(Map.of(
                    "userMessage", userMessage,
                    "results", resultsSummary
            ));

            return chatModel.call(
                    new Prompt(List.of(
                            new SystemMessage(systemContent),
                            new UserMessage("Generate the reply now.")
                    ))
            ).getResult().getOutput().getText();

        } catch (Exception e) {
            log.error("Response formatting failed", e);
            return results.isEmpty()
                    ? "I couldn't find anything matching that. Try a different genre or loosen the filters."
                    : "Here are some titles that might interest you!";
        }
    }

    private String summarize(List<VuduContent> results) {
        if (results.isEmpty()) return "No results found.";
        return results.stream()
                .map(c -> {
                    String genres = c.genres() != null ? String.join(", ", c.genres()) : "Unknown";
                    String price = c.offers() != null ? c.offers().stream()
                            .filter(o -> o.price() != null)
                            .map(o -> String.format("%s $%.2f (%s)", o.offerType(), o.price(), o.videoQuality()))
                            .collect(Collectors.joining("; ")) : "unavailable";
                    String rt = c.tomatoMeter() != null ? c.tomatoMeter() + "%" : "N/A";
                    return String.format("- %s (%s) | %s | RT: %s | %s",
                            c.title(), c.mpaaRating() != null ? c.mpaaRating() : "NR",
                            genres, rt, price);
                })
                .collect(Collectors.joining("\n"));
    }
}
