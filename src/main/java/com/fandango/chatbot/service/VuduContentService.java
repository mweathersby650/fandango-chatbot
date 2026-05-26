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
        sb.append("/sortBy/").append(filters.sortBy() != null ? filters.sortBy() : "-streamScore");
        sb.append("/dimensionality/any");
        sb.append("/includePreOrders/true");
        if (filters.yearFrom() != null) {
            sb.append("/releaseTimeMin/").append(filters.yearFrom()).append("-01-01");
        }
        if (filters.yearTo() != null) {
            sb.append("/releaseTimeMax/").append(filters.yearTo()).append("-12-31");
        }
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
        // Every field in the Vudu API response is array-wrapped: "title": ["Foo"]
        String contentId = firstText(node, "contentId");
        String title     = firstText(node, "title");
        String description = firstText(node, "description");
        Long releaseTime = firstLong(node, "releaseTime");
        Integer lengthSeconds = firstInt(node, "lengthSeconds");
        String mpaaRating = firstText(node, "mpaaRating");
        String posterUrl  = firstText(node, "posterUrl");
        Integer tomatoMeter = firstInt(node, "tomatoMeter");
        String quality    = firstText(node, "bestDashVideoQuality");

        List<String> genres = new ArrayList<>();
        for (JsonNode genreList : node.path("genres")) {
            for (JsonNode g : genreList.path("genre")) {
                String name = firstTextNode(g.path("name"));
                if (name != null) genres.add(name);
            }
        }

        List<VuduOffer> offers = new ArrayList<>();
        for (JsonNode variantList : node.path("contentVariants")) {
            for (JsonNode variant : variantList.path("variant")) {
                String vq = firstText(variant, "videoQuality");
                for (JsonNode offerList : variant.path("offers")) {
                    for (JsonNode offer : offerList.path("offer")) {
                        String offerType = firstText(offer, "offerType");
                        Double price = firstDouble(offer, "price");
                        if (offerType != null && price != null) {
                            offers.add(new VuduOffer(offerType, price, vq));
                        }
                    }
                }
            }
        }

        String deepLink = buildDeepLink(title, contentId);

        return new VuduContent(contentId, title, description, releaseTime,
                lengthSeconds, mpaaRating, posterUrl,
                tomatoMeter != null && tomatoMeter >= 0 ? tomatoMeter : null,
                quality, genres, offers, deepLink);
    }

    private List<VuduContent> postFilter(List<VuduContent> results, ContentFilters filters) {
        return results.stream()
                .filter(c -> matchesGenre(c, filters.genre()))
                .filter(c -> matchesRating(c, filters.mpaaRating()))
                .filter(c -> matchesPrice(c, filters.maxPrice(), filters.offerType()))
                .filter(c -> matchesQuality(c, filters.minVideoQuality()))
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

    private String buildDeepLink(String title, String contentId) {
        if (title == null || contentId == null) return null;
        String slug = title.replaceAll("[^a-zA-Z0-9 ]", "").trim().replace(' ', '-');
        return DEEP_LINK_BASE + "/" + slug + "/" + contentId;
    }

    // Vudu wraps every scalar in a single-element array: "title": ["Foo"]
    private String firstText(JsonNode node, String field) {
        return firstTextNode(node.path(field));
    }

    private String firstTextNode(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) return null;
        if (n.isArray()) {
            JsonNode first = n.get(0);
            return (first == null || first.isNull()) ? null : first.asText(null);
        }
        return n.asText(null);
    }

    private Long firstLong(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isArray()) n = n.get(0);
        return (n == null || n.isNull() || n.isMissingNode()) ? null : n.asLong(0);
    }

    private Integer firstInt(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isArray()) n = n.get(0);
        return (n == null || n.isNull() || n.isMissingNode()) ? null : n.asInt(-1);
    }

    private Double firstDouble(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isArray()) n = n.get(0);
        return (n == null || n.isNull() || n.isMissingNode()) ? null : n.asDouble(0);
    }
}
