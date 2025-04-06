package com.urlshortner.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.*; // Import necessary stream classes
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AnalyticsService {

    private final StringRedisTemplate redisTemplate;
    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    // Constants matching UrlService and defining consumer group/name
    private static final String CLICK_STREAM_KEY = "streams:url-clicks";
    private static final String CONSUMER_GROUP = "analytics-group";
    private static final String CONSUMER_NAME = "consumer-1"; // Can be dynamic if needed
    private static final String CLICKS_HASH_KEY = "clicks"; // Key for storing click counts

    public AnalyticsService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // Ensure the stream and consumer group exist on startup
    @PostConstruct
    private void initializeStreamAndGroup() {
        try {
            // Check if stream exists, otherwise XREADGROUP fails.
            // This is a basic check; a more robust approach might try creating.
            // If stream doesn't exist yet, XINFO STREAM command throws exception
             redisTemplate.opsForStream().info(CLICK_STREAM_KEY); // Check if stream exists
             log.info("Stream {} already exists.", CLICK_STREAM_KEY);

             // Check groups and create if necessary
             StreamInfo.XInfoGroups groups = redisTemplate.opsForStream().groups(CLICK_STREAM_KEY);
             if(groups.stream().noneMatch(g -> g.groupName().equals(CONSUMER_GROUP))) {
                 log.info("Creating consumer group {} for stream {}", CONSUMER_GROUP, CLICK_STREAM_KEY);
                 redisTemplate.opsForStream().createGroup(CLICK_STREAM_KEY, ReadOffset.from("0-0"), CONSUMER_GROUP);
             } else {
                  log.info("Consumer group {} already exists for stream {}", CONSUMER_GROUP, CLICK_STREAM_KEY);
             }

        } catch (Exception e) {
             // Stream likely doesn't exist, try creating the group which might implicitly create the stream or fail gracefully
             log.warn("Stream {} might not exist yet. Attempting to create group {} which might auto-create the stream or require manual creation/first event.", CLICK_STREAM_KEY, CONSUMER_GROUP);
             try {
                  // Create group starting from the beginning of the stream (0-0)
                  // If the stream doesn't exist, this specific call might fail depending on Redis version / config
                  // A more robust approach might involve explicitly creating the stream with XADD if needed.
                  redisTemplate.opsForStream().createGroup(CLICK_STREAM_KEY, ReadOffset.from("0-0"), CONSUMER_GROUP);
                  log.info("Created consumer group {} for stream {}", CONSUMER_GROUP, CLICK_STREAM_KEY);
             } catch (Exception creationEx) {
                   log.error("Could not create consumer group {} for stream {}. Manual creation or first event publish might be required. Error: {}", CONSUMER_GROUP, CLICK_STREAM_KEY, creationEx.getMessage());
             }
        }
    }


    // Method to periodically check for new messages in the stream
    @Scheduled(fixedDelay = 5000) // Run every 5000 milliseconds (5 seconds)
    public void consumeClickEvents() {
        log.trace("Checking for new click events in stream {}", CLICK_STREAM_KEY); // Use TRACE for frequent logs

        try {
            // Read pending messages for our consumer within the group
            // '>' means read new messages not yet delivered to any consumer in the group
             List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream().read(
                    Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
                    StreamReadOptions.empty().count(10), // Read up to 10 messages at a time
                    new StreamOffset[]{StreamOffset.create(CLICK_STREAM_KEY, ReadOffset.lastConsumed())} // Read msgs after last consumed
             );

             if (messages == null || messages.isEmpty()) {
                 log.trace("No new messages found in stream {}", CLICK_STREAM_KEY);
                 return; // Nothing to process
             }

             log.debug("Received {} new messages from stream {}", messages.size(), CLICK_STREAM_KEY);

             for (MapRecord<String, Object, Object> message : messages) {
                 Map<Object, Object> messageBody = message.getValue();
                 String shortId = (String) messageBody.get("shortId"); // Extract shortId

                 if (shortId != null && !shortId.isEmpty()) {
                     // Increment the click count in the Redis Hash
                     Long clickCount = redisTemplate.opsForHash().increment(CLICKS_HASH_KEY, shortId, 1L);
                     log.info("Processed click for shortId: {}. New count: {}. (Msg ID: {})", shortId, clickCount, message.getId());

                     // Acknowledge the message was processed successfully
                     redisTemplate.opsForStream().acknowledge(CLICK_STREAM_KEY, CONSUMER_GROUP, message.getId());
                 } else {
                     log.warn("Received message with invalid/missing shortId: {}", message);
                     // Acknowledge even if invalid to prevent reprocessing? Or move to dead-letter?
                     redisTemplate.opsForStream().acknowledge(CLICK_STREAM_KEY, CONSUMER_GROUP, message.getId());
                 }
             }

        } catch (Exception e) {
             log.error("Error consuming click events from stream {}: {}", CLICK_STREAM_KEY, e.getMessage(), e);
        }
    }
}