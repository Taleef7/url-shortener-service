package com.urlshortner.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

@Service
public class UrlService {

    private final StringRedisTemplate redisTemplate;
    private final String baseUrl;

    private static final String URL_KEY_PREFIX = "url:"; // Prefix for Redis keys storing mappings
    private static final int SHORT_ID_LENGTH = 7; // Length of the generated short ID
    private static final SecureRandom random = new SecureRandom();
    private static final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();


    // Inject RedisTemplate and the base URL from application.properties
    public UrlService(StringRedisTemplate redisTemplate, @Value("${app.base-url}") String baseUrl) {
        this.redisTemplate = redisTemplate;
        this.baseUrl = baseUrl;
    }

    public String shortenUrl(String longUrl) {
        String shortId;
        // Basic collision check loop (can be improved later)
        do {
            shortId = generateShortId();
        } while (Boolean.TRUE.equals(redisTemplate.hasKey(URL_KEY_PREFIX + shortId))); // Check if key already exists

        // Store mapping in Redis: url:<shortId> -> longUrl
        redisTemplate.opsForValue().set(URL_KEY_PREFIX + shortId, longUrl);
        // TODO: Consider setting an expiration time for the key?
        redisTemplate.expire(URL_KEY_PREFIX + shortId, 30, TimeUnit.DAYS); // Example expiration

        // Construct the full short URL
        return baseUrl + shortId;
    }

    // Generates a random Base64 URL-safe string of a specified length
    private String generateShortId() {
         // Generate random bytes
        byte[] randomBytes = new byte[SHORT_ID_LENGTH * 6 / 8 + 1]; // Estimate bytes needed for Base64
        random.nextBytes(randomBytes);
         // Encode to Base64 URL-safe string
        String base64Id = encoder.encodeToString(randomBytes);
         // Trim or adjust length if needed, ensure it's exactly SHORT_ID_LENGTH
        return base64Id.substring(0, SHORT_ID_LENGTH);
    }

    // Method needed for redirection logic later
    public String getLongUrl(String shortId) {
        return redisTemplate.opsForValue().get(URL_KEY_PREFIX + shortId);
    }

     // Method needed for analytics later
    public void publishClickEvent(String shortId) {
        // TODO: Implement Redis Stream publishing logic here
         System.out.println("Placeholder: Publishing click event for " + shortId);
    }
}