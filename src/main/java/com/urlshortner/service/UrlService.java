package com.urlshortner.service;

// Add necessary imports for logging, time, maps etc.
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.urlshortner.dto.UrlStatsResponse;

import java.security.SecureRandom;
import java.time.Instant; // For timestamp
import java.util.Base64;
import java.util.HashMap; // For stream message map
import java.util.Map;     // For stream message map
import java.util.concurrent.TimeUnit;

@Service
public class UrlService {

    private final StringRedisTemplate redisTemplate;
    private final String baseUrl;
    // Add Logger
    private static final Logger log = LoggerFactory.getLogger(UrlService.class);

    private static final String URL_KEY_PREFIX = "url:";
    // Define the key for our Redis Stream
    private static final String CLICK_STREAM_KEY = "streams:url-clicks";
    private static final int SHORT_ID_LENGTH = 7;
    private static final SecureRandom random = new SecureRandom();
    private static final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    public UrlService(StringRedisTemplate redisTemplate, @Value("${app.base-url}") String baseUrl) {
        this.redisTemplate = redisTemplate;
        this.baseUrl = baseUrl;
    }

    // shortenUrl method remains the same
    public String shortenUrl(String longUrl) {
        String shortId;
        do {
            shortId = generateShortId();
        } while (Boolean.TRUE.equals(redisTemplate.hasKey(URL_KEY_PREFIX + shortId)));

        redisTemplate.opsForValue().set(URL_KEY_PREFIX + shortId, longUrl);
        redisTemplate.expire(URL_KEY_PREFIX + shortId, 30, TimeUnit.DAYS); // Keep expiration
        return baseUrl + shortId;
    }

    // generateShortId method remains the same
    private String generateShortId() {
        byte[] randomBytes = new byte[SHORT_ID_LENGTH * 6 / 8 + 1];
        random.nextBytes(randomBytes);
        String base64Id = encoder.encodeToString(randomBytes);
        return base64Id.substring(0, SHORT_ID_LENGTH);
    }

    // getLongUrl method remains the same
    public String getLongUrl(String shortId) {
        return redisTemplate.opsForValue().get(URL_KEY_PREFIX + shortId);
    }

    // --- IMPLEMENT THIS METHOD ---
    public void publishClickEvent(String shortId) {
        try {
            // Create the message payload as a Map
            Map<String, String> messageBody = new HashMap<>();
            messageBody.put("shortId", shortId);
            messageBody.put("timestamp", Instant.now().toString()); // Use UTC timestamp

            // Add the message to the Redis Stream
            // opsForStream().add() returns the unique ID of the message within the stream
            var messageId = redisTemplate.opsForStream().add(CLICK_STREAM_KEY, messageBody);

            // Log success at DEBUG level (optional, requires DEBUG level enabled)
            log.debug("Published click event to stream {} with ID {}: {}", CLICK_STREAM_KEY, messageId, messageBody);

        } catch (Exception e) {
            // Log errors if publishing fails, but don't let it break the redirection flow
            log.error("Failed to publish click event for shortId {} to stream {}: {}", shortId, CLICK_STREAM_KEY, e.getMessage());
        }
    }


    // Add this constant within UrlService class
    private static final String CLICKS_HASH_KEY = "clicks";

    // Add this new method to UrlService
    public UrlStatsResponse getUrlStats(String shortId) {
        // Reuse the existing method to get the long URL
        String longUrl = getLongUrl(shortId);
        if (longUrl == null) {
            // If the original URL mapping doesn't exist, stats are irrelevant
            return null;
        }

        // Get the click count from the 'clicks' hash
        Object clickCountObj = redisTemplate.opsForHash().get(CLICKS_HASH_KEY, shortId);
        long clicks = 0; // Default to 0 clicks

        if (clickCountObj != null) {
            // Redis hash values are stored as strings, need to parse
            try {
                clicks = Long.parseLong(clickCountObj.toString());
            } catch (NumberFormatException e) {
                // Log error if the value in Redis isn't a valid number
                log.error("Failed to parse click count for shortId {}. Value was: {}. Error: {}",
                        shortId, clickCountObj, e.getMessage());
                // Keep clicks as 0 in case of parsing error
            }
        }

        // Create and return the stats response object
        return new UrlStatsResponse(shortId, longUrl, clicks);
    }
}