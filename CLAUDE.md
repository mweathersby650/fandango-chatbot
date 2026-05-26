# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./gradlew build

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.fandango.chatbot.VuduContentServiceTest"

# Run a single test method
./gradlew test --tests "com.fandango.chatbot.VuduContentServiceTest.parsesContentFields"

# Start the service (Anthropic — requires ANTHROPIC_API_KEY)
ANTHROPIC_API_KEY=sk-ant-... ./gradlew bootRun

# Start the service (local Ollama — no key needed)
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun

# End-to-end smoke test (starts service, runs 25 assertions, shuts down)
./smoke-test.sh --local        # Ollama
./smoke-test.sh                # Anthropic
./smoke-test.sh --skip-start   # service already running

# Quick API smoke test (service must already be running)
curl -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"horror movie under $4"}'
```

## Architecture

The service is a Spring Boot 3.3.5 + Spring AI 1.0.0 backend that translates natural language into structured queries against the Fandango At Home / Vudu `contentSearch` API, then uses an LLM to generate a conversational reply. A Vanilla JS web component (`widget/chatbot-widget.js`) embeds as a floating chat panel on any page.

### Request flow

```
POST /chat
  └─ ChatOrchestrator
       ├─ SessionStore          load or create ChatSession (UUID key, 30-min TTL)
       ├─ IntentExtractionService
       │    └─ ChatModel + BeanOutputConverter → ContentFilters record
       │         intent: NEW_SEARCH | REFINE | MORE_RESULTS | MORE_LIKE_THIS
       ├─ VuduContentService
       │    ├─ builds path-style URL → apicache.vudu.com/api2
       │    ├─ strips /*-secure- ... */ wrapper from response
       │    ├─ deserializes JSON → List<VuduContent>
       │    └─ post-filters in memory (price, quality, genre, rating, era)
       └─ ResponseFormatterService
            └─ ChatModel → conversational reply string
```

### Profile / LLM switching

Both `spring-ai-starter-model-anthropic` and `spring-ai-starter-model-ollama` are on the classpath, which would create two `ChatModel` beans. Each profile disables the unwanted one via `spring.autoconfigure.exclude`:

- **default** (`application.yml`) — excludes `OllamaChatAutoConfiguration`, uses `ANTHROPIC_API_KEY`
- **local** (`application-local.yml`) — excludes `AnthropicChatAutoConfiguration`, uses Ollama on `localhost:11434` with model `qwen2.5:1.5b`

Both services (`IntentExtractionService`, `ResponseFormatterService`) inject the `ChatModel` interface — no provider-specific type — so they work identically under either profile.

### Vudu API

The API at `apicache.vudu.com/api2` uses path-style parameters (`/param/value`) rather than query strings, and wraps all responses in `/*-secure- ... */` that must be stripped before JSON parsing. The API has no free-text search — `VuduContentService` builds structured path parameters from `ContentFilters`, then post-filters results in memory for fields the API can't filter natively (price ceiling, min quality, genre, year range).

Deep links are constructed as: `https://athome.fandango.com/content/browse/details/{title-slug}/{contentId}`

### Prompt templates

LLM prompts live in `src/main/resources/prompts/` as StringTemplate (`.st`) files:

- `intent-extraction.st` — system prompt rendered with `{activeFilters}` (current session JSON) and `{format}` (JSON schema injected by `BeanOutputConverter`). Returns a `ContentFilters` JSON object.
- `response-generation.st` — system prompt rendered with `{userMessage}` and `{results}` (plain-text summary). Returns 2–3 sentence conversational reply; result cards are rendered by the widget.

### Session state

`ChatSession` holds `activeFilters`, `history` (Spring AI `Message` list), `lastResults`, `currentOffset`, and `lastActive`. `SessionStore` is an in-memory `ConcurrentHashMap` with a `@Scheduled` eviction task running every 5 minutes.

The `ChatOrchestrator` handles four intent types differently: `NEW_SEARCH` resets offset and clears history; `REFINE` resets offset but keeps the merged filters; `MORE_RESULTS` increments offset and reuses filters; `MORE_LIKE_THIS` derives a new genre filter from the dominant genre in `lastResults`.

### Spring AI 1.0.0 specifics

- Starter artifact names changed from `spring-ai-*-spring-boot-starter` to `spring-ai-starter-model-*`
- `AssistantMessage.getText()` (not `.getContent()`)
- The `-parameters` compiler flag is required for constructor injection (already set in `build.gradle`)

### Widget

`widget/chatbot-widget.js` is a self-contained custom element (`<fandango-chatbot api-url="...">`) using shadow DOM. It persists `sessionId` in `sessionStorage`. Load `widget/demo.html` in a browser with the service running on port 8080 to test the widget manually.
