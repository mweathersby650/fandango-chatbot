# Movie Discovery Chatbot Service

## Overview

A conversational chatbot service that helps users discover movies and TV content on Fandango At Home (athome.fandango.com). The chatbot is an additive layer on top of the existing browse and search interfaces — it does not replace them. Users can describe what they want to watch in natural language, and the chatbot translates that intent into structured queries against the Fandango At Home / Vudu content API, returning curated, explainable results.

---

## Goals

- Allow users to discover content using natural language (e.g., "I want a funny movie for family night under $5")
- Surface relevant results from the existing Fandango At Home catalog via the `contentSearch` API
- Provide a low-friction, conversational interface that complements — not competes with — the existing browse/search UI
- Maintain session context so users can refine searches conversationally ("show me more like that", "only show HD", "filter to 4K rentals")

---

## Non-Goals / Out of Scope

- Replacing or modifying the existing browse and search UI
- User authentication, account management, purchase flows, or rental initiation
- Content playback or streaming
- Recommendations based on personal viewing history
- Building a proprietary content catalog — all data comes from the existing Vudu/Fandango At Home APIs

---

## User Stories

### Core Discovery

- As a user, I can type or speak a natural language query ("sci-fi movies from the 90s") and receive a list of matching titles with prices and availability.
- As a user, I can ask follow-up questions to refine results without starting over ("now filter to 4K", "only show ones under $4 to rent").
- As a user, I can ask for recommendations by mood, genre, rating, era, or keyword.
- As a user, I receive responses that include the title, poster image, genre, MPAA rating, Rotten Tomatoes score, rental/purchase price, and video quality (SD/HD/4K) for each result.

### Conversational Refinement

- As a user, I can say "show me more like this" and get similar titles.
- As a user, I can ask "what's new this week?" or "what are the top rentals right now?" and get relevant content.
- As a user, the chatbot remembers my filters within a session (e.g., if I said "HD only", subsequent results respect that).

### Transparency

- As a user, the chatbot tells me when it cannot find results matching my query and suggests alternative searches.
- As a user, clicking a result takes me to the existing Fandango At Home product page for that title (deep link). The chatbot does not initiate purchases or rentals.

---

## API Integration

### Base Endpoints

The service integrates with the existing Fandango At Home / Vudu content APIs:

- **Cached/read endpoint:** `https://apicache.vudu.com/api2/`
- **Write/account endpoint:** `https://api.vudu.com/api2/`

All content discovery uses the `apicache` host (read-only, no auth required). The `claimedAppId` value `myvudu` is used for all requests. The API does **not** support free-text keyword search — all filtering is via structured parameters (genre, rating, superType, etc.). The chatbot must map natural language intent to these structured parameters.

### contentSearch Parameters

Requests use path-style parameters in the form `/param/value`:

| Parameter | Description | Example Values |
|-----------|-------------|----------------|
| `_type` | Operation type | `contentSearch` |
| `claimedAppId` | App identifier | `myvudu` |
| `format` | Response format | `application/json` |
| `count` | Results per page (max 100) | `20` |
| `offset` | Pagination offset | `0`, `20`, `40` |
| `sortBy` | Sort order | `title`, `releaseTime`, `popularity` |
| `superType` | Content category | `movies`, `tvShows` |
| `type` | Content type | `program`, `bundle` |
| `dimensionality` | Video format filter | `any`, `2D`, `3D` |
| `contentId` | Fetch specific title | `1617107` |

### Followup Parameters (Enrichment)

Append `followup/<field>` segments to include nested data in the response:

- `followup/genres` — genre name(s)
- `followup/ratingsSummaries` — MPAA or TV rating
- `followup/usefulStreamableOffers` — rental/purchase pricing and availability
- `followup/editions` — SD/HD/4K variants
- `followup/longCredits` — cast and crew
- `followup/totalCount` — total result count (useful for pagination UI)

### Key Response Fields

```
content[]
  contentId             — unique title identifier
  title                 — display title
  description           — synopsis
  releaseTime           — release date (epoch ms)
  lengthSeconds         — runtime in seconds
  mpaaRating            — MPAA rating (e.g. "PG", "PG-13", "R")
  posterUrl             — poster image URL (use in result cards)
  tomatoMeter           — Rotten Tomatoes score (integer, 0–100)
  bestDashVideoQuality  — best available streaming quality summary
  genres
    genre[].name        — genre label
  contentVariants
    variant[]
      videoQuality      — "SD" | "HD" | "HDX" | "4K"
      offers
        offer[]
          offerType     — "rent" | "purchase"
          price         — price in USD
moreBelow               — boolean, true if additional pages exist
```

### Deep Link URL Pattern

Product page URLs follow the pattern:

```
https://athome.fandango.com/content/browse/details/{title-slug}/{contentId}
```

Example: `contentId=9505`, `title="Masters of the Universe"` →
`https://athome.fandango.com/content/browse/details/Masters-of-the-Universe/9505`

The title slug is the title with spaces replaced by hyphens. The `contentId` from the API response maps directly to this URL.

### Response Handling

The API response is wrapped in a security prefix (`/*-secure-` ... `*/`) that must be stripped before JSON parsing.

---

## Architecture

### Components

```
User (Browser)
    │
    ▼
Chatbot UI Widget
    │  Natural language input
    ▼
Chatbot Service (Backend)
    │  Intent extraction & query building
    │
    ├──► Claude API (LLM)
    │      Parses user intent → structured filter parameters
    │      Generates natural language response from results
    │
    └──► Fandango At Home Content API
           apicache.vudu.com/api2/contentSearch
           Returns matching content with pricing & metadata
```

### Chatbot Service Responsibilities

1. **Session management** — maintain conversational context (active filters, last results, follow-up state) per session
2. **Intent extraction** — use Claude to parse user input into structured parameters (genre, rating, price ceiling, format, era, keywords)
3. **Query construction** — map extracted parameters to `contentSearch` path-style API parameters
4. **Result formatting** — pass raw API results to Claude to generate a natural, concise response with title cards
5. **Deep linking** — generate `athome.fandango.com` product page URLs from `contentId` for each result

### Embedding Pattern: Floating Widget

The chatbot is implemented as a **floating action button (FAB) with an expandable chat panel**, anchored to the bottom-right corner of the page. This approach:

- Has zero impact on existing page layout and browse/search flows
- Works consistently across all pages (home, browse, search results, PDP)
- Is dismissible — users who prefer the existing UI are not disrupted
- Is a familiar pattern for chat-style overlays on e-commerce sites

### Integration Points

- The floating widget is injected into the existing Fandango At Home web UI via a script tag or web component
- It does not intercept or modify existing browse/search flows
- Deep links from chatbot results navigate users to the existing product pages
- No purchase or rental flows are initiated from within the chatbot

---

## Conversation Flow

```
User: "I want a scary movie for tonight, nothing too gory"

Chatbot:
  → Extract intent: superType=movies, genre=Horror, rating=PG-13 or R
  → Query: contentSearch / superType/movies / followup/genres / followup/ratingsSummaries / followup/usefulStreamableOffers / count/10
  → Filter results by genre=Horror, rating ∈ [PG-13, R]
  → Return: "Here are some horror picks available tonight: ..."

User: "Only show HD or better"

Chatbot:
  → Retain previous filters, add videoQuality filter: HD | HDX | 4K
  → Re-query or filter in-memory
  → Return: "Filtered to HD and above: ..."
```

---

## Acceptance Criteria

- [ ] A natural language query returns at least one page of relevant content results from the `contentSearch` API
- [ ] Results display title, poster image, genre, MPAA rating, Rotten Tomatoes score, rental price, and video quality
- [ ] Follow-up queries within a session correctly refine without losing prior context
- [ ] Each result includes a working deep link to the Fandango At Home product page
- [ ] The chatbot gracefully handles zero-result queries with a helpful message
- [ ] API response security wrapper is correctly stripped before JSON parsing
- [ ] The chatbot widget does not interfere with existing browse/search UI
- [ ] Session context resets when the user navigates away or explicitly starts a new conversation

---

## Resolved Decisions

| Question | Decision |
|----------|----------|
| Does `contentSearch` support free-text keyword search? | No — structured parameters only. The LLM must map natural language to structured filters. |
| What `claimedAppId` to use? | `myvudu` |
| Rate limits / auth for `apicache.vudu.com`? | None for public catalog access |
| Purchase/rental initiation in chatbot? | No — strictly discovery and deep-linking |
| Embedding pattern? | Floating action button + expandable panel (bottom-right) |

---

## Dependencies

- Fandango At Home / Vudu `contentSearch` API (`apicache.vudu.com/api2`)
- Claude API (Anthropic) for natural language intent extraction and response generation
- Existing Fandango At Home web frontend (for widget embedding and deep-link routing)
