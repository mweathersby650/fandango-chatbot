package com.fandango.chatbot.service;

import com.fandango.chatbot.model.ContentFilters;
import com.fandango.chatbot.model.VuduContent;
import com.fandango.chatbot.model.VuduOffer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class VuduContentService {

    private static final Logger log = LoggerFactory.getLogger(VuduContentService.class);
    private static final String BASE_URL = "https://apicache.vudu.com/api2";
    private static final String DEEP_LINK_BASE = "https://athome.fandango.com/content/browse/details";
    private static final int PAGE_SIZE = 20;
    private static final Pattern SECURE_WRAPPER = Pattern.compile("^/\\*-secure-\\s*|\\s*\\*/$");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public VuduContentService(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    public List<VuduContent> search(ContentFilters filters, int offset) {
        String url = buildUrl(filters, offset);
        log.debug("Vudu API request: {}", url);

        String raw = restClient.get()
                .uri(url)
                .retrieve()
                .body(String.class);

        List<VuduContent> results = parseResponse(raw);
        return postFilter(results, filters);
    }

    private String buildUrl(ContentFilters filters, int offset) {
        StringBuilder sb = new StringBuilder(BASE_URL);
        sb.append("/claimedAppId/myvudu");
        sb.append("/format/application*2Fjson");
        sb.append("/_type/contentSearch");
        sb.append("/superType/").append(filters.superType() != null ? filters.superType() : "movies");
        sb.append("/type/program/type/bundle");
        sb.append("/count/").append(PAGE_SIZE);
        sb.append("/offset/").append(offset);
        sb.append("/sortBy/").append(filters.sortBy() != null ? filters.sortBy() : "popularity");
        sb.append("/dimensionality/any");
        sb.append("/followup/genres");
        sb.append("/followup/ratingsSummaries");
        sb.append("/followup/usefulStreamableOffers");
        sb.append("/followup/totalCount");
        return sb.toString();
    }

    private List<VuduContent> parseResponse(String raw) {
        if (raw == null || raw.isBlank()) return List.of();

        // strip /*-secure- ... */ wrapper
        String json = SECURE_WRAPPER.matcher(raw).replaceAll("").trim();

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode contentArray = root.path("content");
            List<VuduContent> results = new ArrayList<>();

            for (JsonNode node : contentArray) {
                results.add(mapContent(node));
            }
            return results;
        } catch (Exception e) {
            log.error("Failed to parse Vudu API response", e);
            return List.of();
        }
    }

    private VuduContent mapContent(JsonNode node) {
        String contentId = textOf(node, "contentId");
        String title = textOf(node, "title");
        String description = textOf(node, "description");
        Long releaseTime = node.path("releaseTime").asLong(0);
        Integer lengthSeconds = node.path("lengthSeconds").asInt(0);
        String mpaaRating = textOf(node, "mpaaRating");
        String posterUrl = textOf(node, "posterUrl");
        Integer tomatoMeter = node.path("tomatoMeter").asInt(-1);
        String quality = textOf(node, "bestDashVideoQuality");

        List<String> genres = new ArrayList<>();
        JsonNode genreNode = node.path("genres").path("genre");
        if (genreNode.isArray()) {
            for (JsonNode g : genreNode) {
                String name = g.path("name").asText(null);
                if (name != null) genres.add(name);
            }
        }

        List<VuduOffer> offers = new ArrayList<>();
        JsonNode variantNode = node.path("contentVariants").path("variant");
        if (variantNode.isArray()) {
            for (JsonNode variant : variantNode) {
                String vq = textOf(variant, "videoQuality");
                JsonNode offerNode = variant.path("offers").path("offer");
                if (offerNode.isArray()) {
                    for (JsonNode offer : offerNode) {
                        offers.add(new VuduOffer(
                                textOf(offer, "offerType"),
                                offer.path("price").asDouble(0),
                                vq
                        ));
                    }
                }
            }
        }

        String deepLink = buildDeepLink(title, contentId);

        return new VuduContent(contentId, title, description, releaseTime,
                lengthSeconds, mpaaRating, posterUrl,
                tomatoMeter >= 0 ? tomatoMeter : null,
                quality, genres, offers, deepLink);
    }

    private List<VuduContent> postFilter(List<VuduContent> results, ContentFilters filters) {
        return results.stream()
                .filter(c -> matchesGenre(c, filters.genre()))
                .filter(c -> matchesRating(c, filters.mpaaRating()))
                .filter(c -> matchesPrice(c, filters.maxPrice(), filters.offerType()))
                .filter(c -> matchesQuality(c, filters.minVideoQuality()))
                .filter(c -> matchesEra(c, filters.yearFrom(), filters.yearTo()))
                .toList();
    }

    private boolean matchesGenre(VuduContent c, String genre) {
        if (genre == null) return true;
        return c.genres() != null && c.genres().stream()
                .anyMatch(g -> g.equalsIgnoreCase(genre));
    }

    private boolean matchesRating(VuduContent c, String rating) {
        if (rating == null) return true;
        return rating.equalsIgnoreCase(c.mpaaRating());
    }

    private boolean matchesPrice(VuduContent c, Double maxPrice, String offerType) {
        if (maxPrice == null) return true;
        if (c.offers() == null || c.offers().isEmpty()) return false;
        return c.offers().stream()
                .filter(o -> offerType == null || offerType.equalsIgnoreCase(o.offerType()))
                .anyMatch(o -> o.price() <= maxPrice);
    }

    private boolean matchesQuality(VuduContent c, String minQuality) {
        if (minQuality == null) return true;
        List<String> hierarchy = List.of("SD", "HD", "HDX", "4K");
        int minIdx = hierarchy.indexOf(minQuality.toUpperCase());
        if (minIdx < 0) return true;
        if (c.offers() == null) return false;
        return c.offers().stream().anyMatch(o -> {
            int idx = hierarchy.indexOf(o.videoQuality() != null ? o.videoQuality().toUpperCase() : "");
            return idx >= minIdx;
        });
    }

    private boolean matchesEra(VuduContent c, Integer yearFrom, Integer yearTo) {
        if (yearFrom == null && yearTo == null) return true;
        if (c.releaseTime() == null || c.releaseTime() == 0) return true;
        int year = LocalDate.ofInstant(Instant.ofEpochMilli(c.releaseTime()), ZoneOffset.UTC).getYear();
        if (yearFrom != null && year < yearFrom) return false;
        if (yearTo != null && year > yearTo) return false;
        return true;
    }

    private String buildDeepLink(String title, String contentId) {
        if (title == null || contentId == null) return null;
        String slug = title.replaceAll("[^a-zA-Z0-9 ]", "").trim().replace(' ', '-');
        return DEEP_LINK_BASE + "/" + slug + "/" + contentId;
    }

    private String textOf(JsonNode node, String field) {
        JsonNode f = node.path(field);
        return f.isMissingNode() || f.isNull() ? null : f.asText(null);
    }
}
