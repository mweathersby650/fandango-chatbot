package com.fandango.chatbot;

import com.fandango.chatbot.model.ChatRequest;
import com.fandango.chatbot.model.ChatResponse;
import com.fandango.chatbot.service.ChatOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = com.fandango.chatbot.controller.ChatController.class)
class ChatControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    ChatOrchestrator orchestrator;

    @Test
    void healthReturnsOk() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void chatReturnsSessionIdAndReply() throws Exception {
        ChatResponse stubResponse = new ChatResponse("session-abc", "Here are some picks!", List.of(), false);
        when(orchestrator.handle(any())).thenReturn(stubResponse);

        ChatRequest request = new ChatRequest(null, "scary movie");

        mvc.perform(post("/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("session-abc"))
                .andExpect(jsonPath("$.reply").value("Here are some picks!"))
                .andExpect(jsonPath("$.results").isArray());
    }

    @Test
    void chatRejectsMissingMessage() throws Exception {
        mvc.perform(post("/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void chatRejectsNullMessage() throws Exception {
        mvc.perform(post("/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":null}"))
                .andExpect(status().isBadRequest());
    }
}
