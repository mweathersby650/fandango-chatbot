package com.fandango.chatbot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VuduOffer(
        String offerType,
        Double price,
        String videoQuality
) {}
