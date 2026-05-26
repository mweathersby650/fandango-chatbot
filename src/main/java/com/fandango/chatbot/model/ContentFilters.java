package com.fandango.chatbot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ContentFilters(
        String superType,       // "movies" | "tvShows"
        String genre,
        String mpaaRating,
        Double maxPrice,
        String offerType,       // "rent" | "purchase"
        String minVideoQuality, // "SD" | "HD" | "HDX" | "4K"
        Integer yearFrom,
        Integer yearTo,
        String sortBy,
        IntentType intent
) {
    public ContentFilters withIntent(IntentType newIntent) {
        return new ContentFilters(superType, genre, mpaaRating, maxPrice,
                offerType, minVideoQuality, yearFrom, yearTo, sortBy, newIntent);
    }

    public static ContentFilters empty() {
        return new ContentFilters("movies", null, null, null,
                null, null, null, null, "-streamScore", IntentType.NEW_SEARCH);
    }
}
