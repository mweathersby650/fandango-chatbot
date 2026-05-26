package com.fandango.chatbot;

import com.fandango.chatbot.model.ContentFilters;
import com.fandango.chatbot.model.IntentType;
import com.fandango.chatbot.model.VuduContent;
import com.fandango.chatbot.service.VuduContentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class VuduContentServiceTest {

    private VuduContentService service;
    private RestClient restClient;
    private RestClient.RequestHeadersUriSpec requestSpec;
    private RestClient.ResponseSpec responseSpec;

    @BeforeEach
    void setUp() {
        restClient = mock(RestClient.class);
        requestSpec = mock(RestClient.RequestHeadersUriSpec.class);
        responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.get()).thenReturn(requestSpec);
        when(requestSpec.uri(anyString())).thenReturn(requestSpec);
        when(requestSpec.retrieve()).thenReturn(responseSpec);

        service = new VuduContentService(restClient, new ObjectMapper());
    }

    @Test
    void stripsSecureWrapper() {
        String wrappedJson = "/*-secure-\n{\"content\":[]}\n*/";
        when(responseSpec.body(String.class)).thenReturn(wrappedJson);

        ContentFilters filters = ContentFilters.empty();
        List<VuduContent> results = service.search(filters, 0);

        assertThat(results).isEmpty();
    }

    @Test
    void parsesContentFields() {
        String json = """
            /*-secure-
            {
              "content": [{
                "contentId": "9505",
                "title": "Masters of the Universe",
                "mpaaRating": "PG",
                "tomatoMeter": 16,
                "posterUrl": "https://example.com/poster.jpg",
                "genres": { "genre": [{"name": "Action"}, {"name": "Adventure"}] },
                "contentVariants": {
                  "variant": [{
                    "videoQuality": "HD",
                    "offers": { "offer": [{"offerType": "rent", "price": 3.99}] }
                  }]
                }
              }]
            }
            */
            """;
        when(responseSpec.body(String.class)).thenReturn(json);

        List<VuduContent> results = service.search(ContentFilters.empty(), 0);

        assertThat(results).hasSize(1);
        VuduContent c = results.get(0);
        assertThat(c.contentId()).isEqualTo("9505");
        assertThat(c.title()).isEqualTo("Masters of the Universe");
        assertThat(c.mpaaRating()).isEqualTo("PG");
        assertThat(c.tomatoMeter()).isEqualTo(16);
        assertThat(c.genres()).containsExactly("Action", "Adventure");
        assertThat(c.offers()).hasSize(1);
        assertThat(c.offers().get(0).price()).isEqualTo(3.99);
    }

    @Test
    void postFiltersOnMaxPrice() {
        String json = """
            /*-secure-
            {
              "content": [
                {
                  "contentId": "1", "title": "Cheap Movie",
                  "contentVariants": {
                    "variant": [{"videoQuality": "HD", "offers": {"offer": [{"offerType": "rent", "price": 2.99}]}}]
                  }
                },
                {
                  "contentId": "2", "title": "Expensive Movie",
                  "contentVariants": {
                    "variant": [{"videoQuality": "HD", "offers": {"offer": [{"offerType": "rent", "price": 9.99}]}}]
                  }
                }
              ]
            }
            */
            """;
        when(responseSpec.body(String.class)).thenReturn(json);

        ContentFilters filters = new ContentFilters("movies", null, null, 4.99,
                "rent", null, null, null, "popularity", IntentType.NEW_SEARCH);

        List<VuduContent> results = service.search(filters, 0);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("Cheap Movie");
    }

    @Test
    void buildsCorrectDeepLink() {
        String json = """
            /*-secure-
            {"content": [{"contentId": "9505", "title": "Masters of the Universe"}]}
            */
            """;
        when(responseSpec.body(String.class)).thenReturn(json);

        List<VuduContent> results = service.search(ContentFilters.empty(), 0);

        assertThat(results.get(0).deepLink())
                .isEqualTo("https://athome.fandango.com/content/browse/details/Masters-of-the-Universe/9505");
    }
}
