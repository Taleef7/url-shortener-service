package com.urlshortner.dto;

// Record to hold the stats data for the response
public record UrlStatsResponse(String shortId, String longUrl, long clicks) {
}