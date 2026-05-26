# Plan: Movie Discovery Chatbot Service

## Context

Build a conversational movie discovery chatbot for Fandango At Home (athome.fandango.com). Users describe what they want to watch in natural language; the service maps that intent to structured queries against the Vudu `contentSearch` API (`apicache.vudu.com/api2`) and returns curated results. The chatbot is purely additive — it does not touch the existing browse/search UI.

Full spec: `_specs/movie-discovery-chatbot.md`

---

## Technology Decisions

| Concern | Choice |
|---------|--------|
| Backend | Java 21, Spring Boot 3.3.5, Spring AI 1.0.0 |
| Build tool | Gradle 8.10 (Groovy DSL) |
| LLM — production | `claude-haiku-4-5-20251001` via Anthropic API |
| LLM — local testing | `qwen2.5:1.5b` via Ollama |
| Frontend | Vanilla JS Web Component (shadow DOM) |
| Session state | In-memory (`ConcurrentHashMap`, TTL-evicted) |
| Vudu API client | Spring `RestClient` |

---

## Spring AI 1.0.0 Notes

Spring AI 1.0.0 GA ships with **renamed artifacts** (breaking change from M-series). Use:

| Old (pre-1.0) | Current (1.0.0) |
|---------------|-----------------|
| `spring-ai-anthropic-spring-boot-starter` | `spring-ai-starter-model-anthropic` |
| `spring-ai-ollama-spring-boot-starter` | `spring-ai-starter-model-ollama` |

Other 1.0.0 API changes:
- `AssistantMessage.getContent()` → `AssistantMessage.getText()`
- Services must inject `ChatModel` (interface), not `AnthropicChatModel` or `OllamaChatModel` directly
- The `-parameters` compiler flag is required for Spring Framework 6 constructor injection (`options.compilerArgs << '-parameters'` in `build.gradle`)

---

## Profile Strategy (Local vs Production)

Both starters are on the classpath, which causes an ambiguous `ChatModel` bean at startup. Each Spring profile excludes the provider it doesn't use:

- **Default profile** (`application.yml`) — excludes `OllamaChatAutoConfiguration`; requires `ANTHROPIC_API_KEY`
- **`local` profile** (`application-local.yml`) — excludes `AnthropicChatAutoConfiguration`; requires Ollama running on `localhost:11434`

Exclusion is done via `spring.autoconfigure.exclude` (not the deprecated `spring.ai.*.enabled` property).

---

## Project Structure

```
chatbot/
├── build.gradle                             # Groovy DSL, Spring Boot 3.3.5, Spring AI BOM 1.0.0
├── settings.gradle
├── gradle/wrapper/
├── gradlew
├── smoke-test.sh                            # End-to-end smoke test script
├── widget/
│   ├── chatbot-widget.js                    # <fandango-chatbot> web component
│   └── demo.html                            # Local test page
└── src/
    ├── main/
    │   ├── java/com/fandango/chatbot/
    │   │   ├── ChatbotApplication.java
    │   │   ├── config/
    │   │   │   └── AppConfig.java           # RestClient bean, CORS, @EnableScheduling
    │   │   ├── controller/
    │   │   │   └── ChatController.java      # POST /chat, GET /health
    │   │   ├── model/
    │   │   │   ├── ChatRequest.java         # { sessionId?, message }
    │   │   │   ├── ChatResponse.java        # { sessionId, reply, results[], moreAvailable }
    │   │   │   ├── ContentFilters.java      # structured intent record
    │   │   │   ├── IntentType.java          # NEW_SEARCH | REFINE | MORE_RESULTS | MORE_LIKE_THIS
    │   │   │   ├── VuduContent.java         # mapped API response record
    │   │   │   └── VuduOffer.java           # { offerType, price, videoQuality }
    │   │   ├── service/
    │   │   │   ├── ChatOrchestrator.java    # intent → query → format, session updates
    │   │   │   ├── IntentExtractionService.java  # ChatModel + BeanOutputConverter<ContentFilters>
    │   │   │   ├── VuduContentService.java  # Vudu API client + post-filter + deep links
    │   │   │   └── ResponseFormatterService.java # ChatModel → conversational reply
    │   │   └── session/
    │   │       ├── ChatSession.java         # filters + history + lastResults + TTL
    │   │       └── SessionStore.java        # ConcurrentHashMap + @Scheduled eviction
    │   └── resources/
    │       ├── application.yml              # default: Anthropic, excludes Ollama
    │       ├── application-local.yml        # local: Ollama qwen2.5:1.5b, excludes Anthropic
    │       └── prompts/
    │           ├── intent-extraction.st     # system prompt: NL → ContentFilters JSON
    │           └── response-generation.st   # system prompt: results → conversational reply
    └── test/java/com/fandango/chatbot/
        ├── VuduContentServiceTest.java      # URL construction, wrapper stripping, post-filter
        └── ChatControllerTest.java          # @WebMvcTest: contract + validation
```

---

## Implementation

### 1. Gradle Build (`build.gradle`)

Key configuration:
```groovy
plugins {
    id 'org.springframework.boot' version '3.3.5'
    id 'io.spring.dependency-management' version '1.1.6'
    id 'java'
}

dependencyManagement {
    imports { mavenBom "org.springframework.ai:spring-ai-bom:1.0.0" }
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.ai:spring-ai-starter-model-anthropic'
    implementation 'org.springframework.ai:spring-ai-starter-model-ollama'
}

tasks.withType(JavaCompile).configureEach {
    options.compilerArgs << '-parameters'   // required for Spring 6 constructor injection
}
```

### 2. Session Management (`session/`)

`ChatSession` holds:
- `ContentFilters activeFilters` — merged filters accumulated across turns
- `List<Message> history` — Spring AI `Message` objects for conversation context
- `List<VuduContent> lastResults` — last page of results (used by MORE_LIKE_THIS)
- `int currentOffset` — current pagination position
- `Instant lastActive` — TTL anchor

`SessionStore` wraps a `ConcurrentHashMap<String, ChatSession>`. A `@Scheduled` task (every 5 min) evicts sessions idle > 30 minutes.

### 3. Vudu API Client (`VuduContentService`)

Builds path-style URLs against `apicache.vudu.com`:

```
https://apicache.vudu.com/api2/claimedAppId/myvudu/format/application*2Fjson
  /_type/contentSearch/superType/{superType}/type/program/type/bundle
  /count/20/offset/{offset}/sortBy/{sortBy}/dimensionality/any
  /followup/genres/followup/ratingsSummaries
  /followup/usefulStreamableOffers/followup/totalCount
```

Key responsibilities:
- Null filters are omitted (no empty path segments)
- Response body: strip `/*-secure-` prefix and `*/` suffix before JSON parse (regex: `^/\*-secure-\s*|\s*\*/$`)
- Post-filter in memory: `maxPrice`, `minVideoQuality`, `genre`, `mpaaRating`, year range
- Deep link: `https://athome.fandango.com/content/browse/details/{title-slug}/{contentId}`

`VuduContent` record fields: `contentId`, `title`, `description`, `releaseTime`, `lengthSeconds`, `mpaaRating`, `posterUrl`, `tomatoMeter`, `bestDashVideoQuality`, `genres`, `offers`, `deepLink`

### 4. Intent Extraction (`IntentExtractionService`)

- Injects `ChatModel` (interface) — works with Anthropic or Ollama transparently
- Loads `prompts/intent-extraction.st` at construction time
- Renders the template with `{activeFilters}` (current session JSON) and `{format}` (JSON schema from `BeanOutputConverter`)
- Calls `chatModel.call(Prompt)` and converts the response text via `BeanOutputConverter<ContentFilters>`
- Falls back to `ContentFilters.empty()` on any exception

`ContentFilters` record:
```java
record ContentFilters(
    String superType,       // "movies" | "tvShows"
    String genre,           // e.g. "Horror", "Comedy"
    String mpaaRating,      // "G" | "PG" | "PG-13" | "R" | "NR"
    Double maxPrice,        // USD ceiling
    String offerType,       // "rent" | "purchase"
    String minVideoQuality, // "SD" | "HD" | "HDX" | "4K"
    Integer yearFrom,
    Integer yearTo,
    String sortBy,          // "popularity" | "title" | "releaseTime"
    IntentType intent       // NEW_SEARCH | REFINE | MORE_RESULTS | MORE_LIKE_THIS
) {}
```

### 5. Response Formatter (`ResponseFormatterService`)

- Injects `ChatModel` — same interface, same provider transparency
- Loads `prompts/response-generation.st`
- Renders with `{userMessage}` and `{results}` (a plain-text summary of matched titles)
- Returns a short conversational reply (2–3 sentences); result cards are rendered by the widget

### 6. Chat Orchestrator (`ChatOrchestrator`)

Per-request flow:
1. Load or create `ChatSession` from `SessionStore`
2. Extract intent via `IntentExtractionService`
3. Resolve offset and filters by intent:
   - `NEW_SEARCH` → reset offset + history, use new filters
   - `REFINE` → reset offset, use new (merged) filters
   - `MORE_RESULTS` → increment offset, reuse active filters
   - `MORE_LIKE_THIS` → reset offset, derive genre from dominant genre in `lastResults`
4. Query `VuduContentService`
5. Format reply via `ResponseFormatterService`
6. Update session (`activeFilters`, `history`, `lastResults`, `currentOffset`)
7. Return `ChatResponse` with `moreAvailable = results.size() == PAGE_SIZE`

### 7. REST Controller (`ChatController`)

```
POST /chat
  Body:    { "sessionId": "uuid|null", "message": "..." }
  Returns: { "sessionId": "uuid", "reply": "...", "results": [...], "moreAvailable": bool }

GET /health
  Returns: { "status": "ok" }
```

- `@NotBlank` + `@Size(max=500)` on `message`
- `@ExceptionHandler` for `MethodArgumentNotValidException` → 400 `{ "error": "..." }`

### 8. Frontend Widget (`widget/chatbot-widget.js`)

Custom element `<fandango-chatbot api-url="...">` using closed shadow DOM:

- **FAB** (closed): film-reel icon, fixed bottom-right, Fandango red
- **Panel** (open): 380×520px, slides up with CSS transition
  - Renders result cards: poster image, title, genre, MPAA rating, RT score, quality badge, lowest rent price, deep link → new tab
  - "Show more results" button appears when `moreAvailable: true`
  - `sessionId` persisted in `sessionStorage` (clears on tab close)
  - All user content HTML-escaped before insertion

Embed: `<script src="/widget/chatbot-widget.js"></script><fandango-chatbot api-url="https://..."></fandango-chatbot>`

---

## Running Locally

```bash
# Install Ollama and pull model (one-time)
brew install ollama
ollama serve &
ollama pull qwen2.5:1.5b

# Run service
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun

# Or against Anthropic
ANTHROPIC_API_KEY=sk-ant-... ./gradlew bootRun
```

---

## Smoke Test (`smoke-test.sh`)

Starts the service, waits for health, runs 25 assertions across 10 scenarios, then shuts down.

```bash
./smoke-test.sh --local          # Ollama, no API key needed
./smoke-test.sh                  # Anthropic (requires ANTHROPIC_API_KEY)
./smoke-test.sh --skip-start     # assume service already running
```

Scenarios covered: health, new search, session refinement, more results, more like this, new session (no ID), result field validation, blank/missing message (400), price filter.

**Smoke test result: 25/25 passed** (Vudu content results are empty in local-only testing as `apicache.vudu.com` requires network access from Fandango's infrastructure; all service logic, intent extraction, and session flows pass.)

---

## Key API Details

- **Base URL**: `https://apicache.vudu.com/api2/`
- **Auth**: none for public catalog; `claimedAppId=myvudu`
- **Response wrapper**: strip `/*-secure-` ... `*/` before JSON parse
- **No free-text search**: structured parameters only; the LLM maps NL → filters
- **Deep link pattern**: `https://athome.fandango.com/content/browse/details/{title-slug}/{contentId}`
- **Pagination**: `moreBelow` boolean in response; `offset` increments by `count` (max 100)

---

## Out of Scope

- Authentication / user accounts
- Purchase or rental initiation from within the chatbot
- Persistent session storage (in-memory only)
- Rate limiting / API key rotation
- Prompt caching headers (Anthropic `cache_control: ephemeral`) — scaffolded in notes, not yet wired
