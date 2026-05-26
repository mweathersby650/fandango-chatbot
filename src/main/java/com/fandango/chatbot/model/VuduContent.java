package com.fandango.chatbot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VuduContent(
        String contentId,
        String title,
        String description,
        Long releaseTime,
        Integer lengthSeconds,
        String mpaaRating,
        String posterUrl,
        Integer tomatoMeter,
        String bestDashVideoQuality,
        List<String> genres,
        List<VuduOffer> offers,
        String deepLink
) {}
