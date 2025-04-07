package com.urlshortner.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*; // Import argument matchers
import static org.mockito.Mockito.*; // Import static Mockito methods

@ExtendWith(MockitoExtension.class) // Enable Mockito
class AnalyticsServiceTest {

    @Mock // Mock the main Redis template
    private StringRedisTemplate redisTemplate;

    @Mock // Mock the StreamOperations interface
    private StreamOperations<String, Object, Object> streamOperationsMock;

    @Mock // Mock the HashOperations interface
    private HashOperations<String, Object, Object> hashOperationsMock; // Match opsForHash() return type

    @InjectMocks // Create an instance of AnalyticsService and inject the mocks above
    private AnalyticsService analyticsService;

    // Define constants used in the service to ensure consistency in tests
    private final String STREAM_KEY = "streams:url-clicks";
    private final String CONSUMER_GROUP = "analytics-group";
    private final String CONSUMER_NAME = "consumer-1";
    private final String HASH_KEY = "clicks";

    @BeforeEach
    void setUp() {
        // Configure the mock redisTemplate to return the specific operation mocks
        // Use lenient() because not all tests might use all operations
        lenient().when(redisTemplate.opsForStream()).thenReturn(streamOperationsMock);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperationsMock);
        // Note: The @PostConstruct initializeStreamAndGroup method is NOT typically run
        // during standard unit testing. We mock the results of stream reads directly.
    }

    @Test
    void consumeClickEvents_whenMessagesExist_shouldProcessIncrementAndAcknowledge() {
        // Arrange
        String testShortId = "abc1234";
        String messageIdString = "1700000000000-0"; // Example message ID
        RecordId recordId = RecordId.of(messageIdString);
        // Create the expected message payload map
        Map<Object, Object> messageBody = Map.of("shortId", testShortId, "timestamp", "some-timestamp");

        // --- Mock the MapRecord that would be returned by read() ---
        // Use Mockito.mock() to create a mock instance of the interface
        @SuppressWarnings("unchecked") // Suppress warning for raw MapRecord mock
        MapRecord<String, Object, Object> mockRecord = mock(MapRecord.class);
        // Define behavior for the methods our code calls on the record
        when(mockRecord.getId()).thenReturn(recordId); // Mock getId()
        when(mockRecord.getValue()).thenReturn(messageBody); // Mock getValue()
        // We don't need mockRecord.getStream() for the current code under test

        // Create a list containing our single mock record
        List<MapRecord<String, Object, Object>> messages = List.of(mockRecord);

        // --- Mock Redis interactions ---
        // 1. Mock the stream read operation to return our list with the mocked record
        when(streamOperationsMock.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
            .thenReturn(messages);

        // 2. Mock the hash increment operation - return the expected new count (e.g., 1)
        when(hashOperationsMock.increment(eq(HASH_KEY), eq(testShortId), eq(1L))).thenReturn(1L);

        // 3. Mock the acknowledge operation (it returns # acknowledged, so return 1L)
        when(streamOperationsMock.acknowledge(eq(STREAM_KEY), eq(CONSUMER_GROUP), eq(recordId))).thenReturn(1L);

        // Act
        // Call the scheduled method directly for the unit test
        analyticsService.consumeClickEvents();

        // Verify (Check that mock methods were called as expected)
        // 1. Verify stream read was called once
        verify(streamOperationsMock, times(1)).read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class));
        // 2. Verify hash increment was called once with correct arguments
        verify(hashOperationsMock, times(1)).increment(eq(HASH_KEY), eq(testShortId), eq(1L));
        // 3. Verify acknowledge was called once with correct arguments (using the real recordId instance)
        verify(streamOperationsMock, times(1)).acknowledge(eq(STREAM_KEY), eq(CONSUMER_GROUP), eq(recordId));
    }

    @Test
    void consumeClickEvents_whenNoMessages_shouldNotProcessOrAcknowledge() {
        // Arrange
        // Mock the stream read operation to return an empty list
        when(streamOperationsMock.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
            .thenReturn(Collections.emptyList());

        // Act
        analyticsService.consumeClickEvents();

        // Verify
        // 1. Verify stream read was called once
        verify(streamOperationsMock, times(1)).read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class));
        // 2. Verify increment was NEVER called
        verify(hashOperationsMock, never()).increment(anyString(), anyString(), anyLong());
        // 3. Verify acknowledge was NEVER called
        verify(streamOperationsMock, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
    }

    // TODO: Add more tests for other scenarios:
    // - Processing multiple messages in one poll
    // - Handling messages with missing/invalid 'shortId' field
    // - Handling exceptions during Redis operations (read, increment, acknowledge)
}