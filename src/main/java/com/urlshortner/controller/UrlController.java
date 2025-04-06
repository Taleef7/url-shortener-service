package com.urlshortner.controller;

import com.urlshortner.dto.ShortenUrlRequest;
import com.urlshortner.dto.ShortenUrlResponse;
import com.urlshortner.service.UrlService;
import jakarta.validation.Valid; // Use jakarta validation
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.net.URISyntaxException;

// ... other imports ...
import org.slf4j.Logger; // Import Logger
import org.slf4j.LoggerFactory; // Import LoggerFactory


@RestController // Combines @Controller and @ResponseBody
public class UrlController {

    private final UrlService urlService;

    // Add Logger
    private static final Logger log = LoggerFactory.getLogger(UrlController.class);

    // Constructor injection (recommended)
    public UrlController(UrlService urlService) {
        this.urlService = urlService;
    }


    @PostMapping("/api/urls")
    public ResponseEntity<ShortenUrlResponse> shortenUrl(@Valid @RequestBody ShortenUrlRequest request) {

        // ADD THIS LOGGING
        log.info("Received shortenUrl request: {}", request);


        if (request.longUrl() == null || request.longUrl().isEmpty() || !isValidUrl(request.longUrl())) {
           log.warn("Validation failed for request: {}", request); // Log why if uncommented later
           return ResponseEntity.badRequest().build();
        }

        // Proceed directly to calling the service for debugging
        String shortUrl = urlService.shortenUrl(request.longUrl());
        ShortenUrlResponse response = new ShortenUrlResponse(shortUrl);
        log.info("Successfully generated short URL: {}", response.shortUrl()); // Log success
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }


    @GetMapping("/{shortId}") // Maps GET requests for redirection
    public ResponseEntity<Void> redirectToLongUrl(@PathVariable String shortId) {
        String longUrl = urlService.getLongUrl(shortId);

        if (longUrl == null) {
             // If shortId not found, return 404
             throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Short URL not found");
        }

        // Publish click event asynchronously (using placeholder for now)
        urlService.publishClickEvent(shortId);


         // Perform HTTP 302 Redirect
        return ResponseEntity.status(HttpStatus.FOUND)
               .location(URI.create(longUrl)) // Use URI.create for location header
               .build();
    }

     // Basic helper to check if a string looks like a URL (can be improved)
    private boolean isValidUrl(String url) {
        try {
             new URI(url).parseServerAuthority(); // Basic check if it can be parsed
             return url.startsWith("http://") || url.startsWith("https://");
        } catch (URISyntaxException | NullPointerException e) {
             return false;
        }
    }
}