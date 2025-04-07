package com.urlshortner.controller;

import com.fasterxml.jackson.databind.ObjectMapper; // For JSON processing
import com.urlshortner.dto.ShortenUrlRequest;
import com.urlshortner.dto.ShortenUrlResponse;
import com.urlshortner.dto.UrlStatsResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*; // For jsonPath assertions
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*; // For MockMvc assertions (status, jsonPath etc)

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT) // Load full context, random port
@Testcontainers // Enable Testcontainers extension for JUnit 5
@AutoConfigureMockMvc // Configure MockMvc for sending test requests
//@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS) // Optional: Reset context after tests in this class
class UrlControllerIntegrationTest {

    @Autowired // Inject MockMvc instance
    private MockMvc mockMvc;

    @Autowired // Inject RedisTemplate to verify Redis state directly
    private StringRedisTemplate redisTemplate;

    @Autowired // Inject ObjectMapper to parse JSON responses
    private ObjectMapper objectMapper;

    // Define the Redis container using Testcontainers
    @Container // Marks this as a Testcontainer managed resource
    static GenericContainer<?> redisContainer =
            new GenericContainer<>(DockerImageName.parse("redis:7")) // Use Redis image version 7
                    .withExposedPorts(6379); // Expose the default Redis port

    // Dynamically configure Spring Boot properties BEFORE context loads
    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        // Tell Spring Boot Data Redis to connect to the Testcontainer's host and mapped port
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379).toString());
        // Note: This overrides the values in application.properties ONLY for this test class
    }

    // Optional: Clean up Redis before each test if needed
    @BeforeEach
    void cleanupRedis() {
        // Flush all data from the Redis instance before each test method runs
        // Ensures tests are independent
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void postApiUrls_whenValidRequest_shouldReturnCreatedAndStoreMapping() throws Exception {
        // Arrange
        String longUrl = "https://integration-test.com/path";
        ShortenUrlRequest request = new ShortenUrlRequest(longUrl);
        String requestJson = objectMapper.writeValueAsString(request); // Convert request object to JSON string

        // Act & Assert using MockMvc
        MvcResult result = mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.shortUrl", containsString("/"))) // Check it contains base part
                // REMOVED the problematic jsonPath length assertion from here
                .andReturn();

        // Extract shortUrl from response using ObjectMapper
        String responseBody = result.getResponse().getContentAsString();
        ShortenUrlResponse responseDto = objectMapper.readValue(responseBody, ShortenUrlResponse.class);
        String shortUrl = responseDto.shortUrl();
        assertThat(shortUrl).isNotNull();

        // ADDED AssertJ length check here - Use the actual configured base URL length + expected ID length
        // Get base URL used by the service (can autowire UrlService or read property or hardcode based on application.properties)
        int expectedMinLength = "http://localhost:8081/".length() + 7; // Base from props + ID length (assuming 7)
        assertThat(shortUrl.length()).isEqualTo(expectedMinLength); // Check exact length using AssertJ

        // Extract shortId from shortUrl
        String shortId = shortUrl.substring(shortUrl.lastIndexOf('/') + 1);
        assertThat(shortId).hasSize(7); // Assuming ID length is 7

        // Assert Redis state
        String storedLongUrl = redisTemplate.opsForValue().get("url:" + shortId);
        assertThat(storedLongUrl).isEqualTo(longUrl); // Verify the mapping was stored correctly in Redis
    }

    @Test
    void getShortId_whenIdExists_shouldRedirectAndTriggerAnalytics() throws Exception {
        // Arrange
        String shortId = "test1234";
        String longUrl = "https://redirect-test.com";
        String urlKey = "url:" + shortId;
        String clicksKey = "clicks";
        String streamKey = "streams:url-clicks";

        // Manually set up the state in Redis before the test
        redisTemplate.opsForValue().set(urlKey, longUrl);
        redisTemplate.opsForHash().put(clicksKey, shortId, "0"); // Initialize clicks if needed (or let increment create)
        long initialStreamLength = redisTemplate.opsForStream().size(streamKey); // Get initial stream size

        // Act & Assert (Redirection)
        mockMvc.perform(get("/" + shortId)) // Perform GET request to the short ID path
                .andExpect(status().isFound()) // Assert HTTP status is 302 Found (Redirect)
                .andExpect(redirectedUrl(longUrl)); // Assert the Location header points to the long URL

        // Assert (Analytics - Check Redis state *after* redirect logic should have run)
        // NOTE: Testing the scheduled consumer is tricky in integration tests without waiting.
        // We'll check the stream directly here. A dedicated test for the consumer might be needed.

        // Check if a message was added to the stream
        long finalStreamLength = redisTemplate.opsForStream().size(streamKey);
        assertThat(finalStreamLength).isEqualTo(initialStreamLength + 1);

         // Check if click count was incremented (This might *not* happen immediately due to scheduling)
         // To test this reliably, we might need to wait or trigger the scheduler manually,
         // or test the AnalyticsService separately.
         // For now, checking the stream confirms the event was published.
         // String clickCount = redisTemplate.opsForHash().get(clicksKey, shortId);
         // assertThat(clickCount).isEqualTo("1"); // This assertion might fail depending on timing
    }

     @Test
     void getStats_whenIdExists_shouldReturnStats() throws Exception {
         // Arrange
         String shortId = "statTest";
         String longUrl = "https://stats-test.com";
         long clicks = 5L;
         String urlKey = "url:" + shortId;
         String clicksKey = "clicks";

         // Manually set up the state in Redis
         redisTemplate.opsForValue().set(urlKey, longUrl);
         redisTemplate.opsForHash().put(clicksKey, shortId, String.valueOf(clicks)); // Store click count

         // Act & Assert
         mockMvc.perform(get("/api/stats/" + shortId)) // Perform GET request to stats endpoint
                 .andExpect(status().isOk()) // Assert HTTP status 200 OK
                 .andExpect(content().contentType(MediaType.APPLICATION_JSON)) // Assert response type
                 .andExpect(jsonPath("$.shortId", is(shortId))) // Assert shortId in response
                 .andExpect(jsonPath("$.longUrl", is(longUrl))) // Assert longUrl in response
                 .andExpect(jsonPath("$.clicks", is((int)clicks))); // Assert clicks count (jsonPath often expects int here)
     }

     @Test
     void getShortId_whenIdDoesNotExist_shouldReturnNotFound() throws Exception {
         // Arrange
         String shortId = "notThere";

         // Act & Assert
         mockMvc.perform(get("/" + shortId))
                 .andExpect(status().isNotFound()); // Assert HTTP status is 404 Not Found
     }

      @Test
     void getStats_whenIdDoesNotExist_shouldReturnNotFound() throws Exception {
         // Arrange
         String shortId = "noStats";

         // Act & Assert
         mockMvc.perform(get("/api/stats/" + shortId))
                 .andExpect(status().isNotFound()); // Assert HTTP status is 404 Not Found
     }

    // TODO: Add tests for POST /api/urls with invalid input (e.g., empty URL, badly formatted URL)
}