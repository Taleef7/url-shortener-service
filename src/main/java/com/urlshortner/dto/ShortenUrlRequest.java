package com.urlshortner.dto;

// Using a Java Record for a concise immutable data carrier
// Add imports for validation annotations later if needed
public record ShortenUrlRequest(String longUrl) {
}