package com.urlshortner.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest; // Use RepeatedTest to check randomness
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks; // To inject mocks into UrlService if needed later
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations; // To mock opsForValue() etc.
import org.springframework.test.util.ReflectionTestUtils; // To access private field/method for this specific test

import org.springframework.data.redis.connection.stream.MapRecord; // May not be directly needed but good practice
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.util.Map;
import java.time.temporal.ChronoUnit; // For timestamp assertion
import static org.assertj.core.api.Assertions.within; // For timestamp assertion
import static org.assertj.core.api.Assertions.fail; // For timestamp assertion try-catch
import org.springframework.data.redis.RedisConnectionFailureException; // For exception test
import static org.assertj.core.api.Assertions.assertThatCode; // For exception test

import com.urlshortner.dto.UrlStatsResponse;

import java.util.concurrent.TimeUnit;


import static org.assertj.core.api.Assertions.assertThat; // AssertJ for fluent assertions
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.*; // Import Mockito static methods

@ExtendWith(MockitoExtension.class) // Enable Mockito annotations like @Mock, @InjectMocks
class UrlServiceTest {

    // --- Mocks (for testing methods that use Redis) ---
    @Mock // Creates a mock instance of StringRedisTemplate
    private StringRedisTemplate redisTemplate;

    @Mock // Mock the specific operations interface returned by opsForValue()
    private ValueOperations<String, String> valueOperationsMock;

    @Mock
    private HashOperations<String, Object, Object> hashOperationsMock;

    @Mock
    private StreamOperations<String, Object, Object> streamOperationsMock; // Use Object for map values to simplify mocking/capturing

    // --- Instance Under Test ---
    // @InjectMocks // We'll manually instantiate for now to pass the base URL easily
    private UrlService urlService;

    // Test constants
    private final String TEST_BASE_URL = "http://test.com/";
    private final String TEST_LONG_URL = "https://example.com/very/long/path";
    private final int EXPECTED_SHORT_ID_LENGTH = 7; // Should match UrlService.SHORT_ID_LENGTH


    @BeforeEach // Method runs before each @Test
    void setUp() {
        // Manually create UrlService instance, injecting the mock RedisTemplate
        // and providing a value for baseUrl (which comes from @Value otherwise)
        urlService = new UrlService(redisTemplate, TEST_BASE_URL);

        // --- Configure Mock Behavior (Example for later tests) ---
        // When redisTemplate.opsForValue() is called, return our valueOperationsMock
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperationsMock);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperationsMock);
        // Add this inside setUp()
        lenient().when(redisTemplate.opsForStream()).thenReturn(streamOperationsMock);
        // lenient() avoids Strict Stubs checking if mocks aren't used in *every* test
    }

    // --- Test for Private Method generateShortId ---
    // Note: Testing private methods directly is debatable. Often better to test via public methods.
    // But for checking ID format/length, ReflectionTestUtils can be used.
    @Test
    @RepeatedTest(10) // Run 10 times to get different random IDs
    void generateShortId_shouldReturnCorrectFormatAndLength() {
        // Arrange (Nothing specific needed here as it uses SecureRandom internally)

        // Act
        // Use Spring's ReflectionTestUtils to call the private method
        String shortId = ReflectionTestUtils.invokeMethod(urlService, "generateShortId");

        // Assert
        System.out.println("Generated ID: " + shortId); // Print for observation
        assertThat(shortId).isNotNull();
        assertThat(shortId).hasSize(EXPECTED_SHORT_ID_LENGTH);
        // Check if it contains only Base64 URL Safe characters (A-Z, a-z, 0-9, -, _)
        assertThat(shortId).matches("^[A-Za-z0-9_-]+$");
    }


    // --- Test for Public Method shortenUrl (Illustrates Mocking) ---
    @Test
    void shortenUrl_shouldGenerateIdStoreInRedisAndReturnFullUrl() {
        // Arrange

        // 1. Mock the check for existing key (hasKey) -> assume ID doesn't exist initially
        // Need access to the URL_KEY_PREFIX (make package-private or use ReflectionTestUtils)
        String prefix = "url:"; // Hardcode or get via ReflectionTestUtils
        when(redisTemplate.hasKey(anyString())).thenReturn(false); // Assume generated ID is always unique for this test

        // 2. Mock the set operation -> Do nothing when set is called
        // We don't need to verify the value was actually set in this specific unit test,
        // just that the method runs without error after mocking.
        // redisTemplate.opsForValue().set(...) -> valueOperationsMock.set(...)
        doNothing().when(valueOperationsMock).set(anyString(), anyString());

        // 3. Mock the expire operation -> Do nothing
        when(redisTemplate.expire(anyString(), anyLong(), any())).thenReturn(Boolean.TRUE);


        // Act
        String fullShortUrl = urlService.shortenUrl(TEST_LONG_URL);


        // Assert
        assertThat(fullShortUrl).startsWith(TEST_BASE_URL);
        assertThat(fullShortUrl.substring(TEST_BASE_URL.length())).hasSize(EXPECTED_SHORT_ID_LENGTH);

        // Verify interactions with Mocks (Optional but good)
        // Verify hasKey was checked at least once (due to do-while)
        verify(redisTemplate, atLeastOnce()).hasKey(matches(prefix + "[A-Za-z0-9_-]{" + EXPECTED_SHORT_ID_LENGTH + "}"));
        // Verify set was called exactly once with expected prefix and the long URL
        verify(valueOperationsMock, times(1)).set(matches(prefix + ".{" + EXPECTED_SHORT_ID_LENGTH + "}"), eq(TEST_LONG_URL));
         // Verify expire was called exactly once
         verify(redisTemplate, times(1)).expire(matches(prefix + ".{" + EXPECTED_SHORT_ID_LENGTH + "}"), eq(30L), eq(TimeUnit.DAYS));
    }


    @Test
    void getLongUrl_whenKeyExists_shouldReturnValueFromRedis() {
        // Arrange
        String testShortId = "exists1";
        String expectedLongUrl = "https://example-exists.com";
        String expectedRedisKey = "url:" + testShortId; // Construct the key as used in UrlService

        // Define mock behavior: When get(key) is called, return the expected URL
        when(valueOperationsMock.get(expectedRedisKey)).thenReturn(expectedLongUrl);

        // Act: Call the method under test
        String actualLongUrl = urlService.getLongUrl(testShortId);

        // Assert: Check if the returned value matches what the mock provided
        assertThat(actualLongUrl).isEqualTo(expectedLongUrl);

        // Verify: Ensure the mock's get method was called exactly once with the correct key
        verify(valueOperationsMock, times(1)).get(expectedRedisKey);
    }

    @Test
    void getLongUrl_whenKeyDoesNotExist_shouldReturnNull() {
        // Arrange
        String testShortId = "missing1";
        String expectedRedisKey = "url:" + testShortId; // Construct the key

        // Define mock behavior: When get(key) is called, return null
        when(valueOperationsMock.get(expectedRedisKey)).thenReturn(null);

        // Act: Call the method under test
        String actualLongUrl = urlService.getLongUrl(testShortId);

        // Assert: Check if the returned value is null
        assertThat(actualLongUrl).isNull();

        // Verify: Ensure the mock's get method was called exactly once with the correct key
        verify(valueOperationsMock, times(1)).get(expectedRedisKey);
    }

    // --- Tests for getUrlStats ---

    @Test
    void getUrlStats_whenUrlAndClicksExist_shouldReturnStats() {
        // Arrange
        String testShortId = "stats1";
        String expectedLongUrl = "https://example-stats.com";
        long expectedClicks = 15L;
        String urlKey = "url:" + testShortId;
        String clicksKey = "clicks"; // Match the key used in AnalyticsService/UrlService

        // Mock Redis responses
        when(valueOperationsMock.get(urlKey)).thenReturn(expectedLongUrl);
        when(hashOperationsMock.get(clicksKey, testShortId)).thenReturn(String.valueOf(expectedClicks)); // Return count as String

        // Act
        UrlStatsResponse stats = urlService.getUrlStats(testShortId);

        // Assert
        assertThat(stats).isNotNull();
        assertThat(stats.shortId()).isEqualTo(testShortId);
        assertThat(stats.longUrl()).isEqualTo(expectedLongUrl);
        assertThat(stats.clicks()).isEqualTo(expectedClicks);

        // Verify mocks were called
        verify(valueOperationsMock).get(urlKey);
        verify(hashOperationsMock).get(clicksKey, testShortId);
    }

    @Test
    void getUrlStats_whenUrlExistsButNoClicks_shouldReturnStatsWithZeroClicks() {
        // Arrange
        String testShortId = "stats0";
        String expectedLongUrl = "https://example-no-clicks.com";
        String urlKey = "url:" + testShortId;
        String clicksKey = "clicks";

        when(valueOperationsMock.get(urlKey)).thenReturn(expectedLongUrl);
        when(hashOperationsMock.get(clicksKey, testShortId)).thenReturn(null); // Simulate no click entry found in hash

        // Act
        UrlStatsResponse stats = urlService.getUrlStats(testShortId);

        // Assert
        assertThat(stats).isNotNull();
        assertThat(stats.shortId()).isEqualTo(testShortId);
        assertThat(stats.longUrl()).isEqualTo(expectedLongUrl);
        assertThat(stats.clicks()).isEqualTo(0L); // Expect 0 clicks

        verify(valueOperationsMock).get(urlKey);
        verify(hashOperationsMock).get(clicksKey, testShortId);
    }

    @Test
    void getUrlStats_whenUrlDoesNotExist_shouldReturnNull() {
        // Arrange
        String testShortId = "statsNotFound";
        String urlKey = "url:" + testShortId;
        String clicksKey = "clicks";

        when(valueOperationsMock.get(urlKey)).thenReturn(null); // Simulate URL key not found

        // Act
        UrlStatsResponse stats = urlService.getUrlStats(testShortId);

        // Assert
        assertThat(stats).isNull(); // Expect null response as per method logic

        verify(valueOperationsMock).get(urlKey);
        // Verify the hash operation was NOT called because the method should return early
        verify(hashOperationsMock, never()).get(clicksKey, testShortId);
    }

    @Test
    void getUrlStats_whenClickCountIsNotNumeric_shouldReturnStatsWithZeroClicks() {
        // Arrange
        String testShortId = "statsBadCount";
        String expectedLongUrl = "https://example-bad-count.com";
        String urlKey = "url:" + testShortId;
        String clicksKey = "clicks";

        when(valueOperationsMock.get(urlKey)).thenReturn(expectedLongUrl);
        when(hashOperationsMock.get(clicksKey, testShortId)).thenReturn("not-a-number"); // Simulate non-numeric data stored

        // Act
        UrlStatsResponse stats = urlService.getUrlStats(testShortId);

        // Assert
        assertThat(stats).isNotNull();
        assertThat(stats.shortId()).isEqualTo(testShortId);
        assertThat(stats.longUrl()).isEqualTo(expectedLongUrl);
        assertThat(stats.clicks()).isEqualTo(0L); // Expect 0 due to NumberFormatException catch block in service

        verify(valueOperationsMock).get(urlKey);
        verify(hashOperationsMock).get(clicksKey, testShortId);
    }

    // --- Tests for publishClickEvent ---

    @Test
    void publishClickEvent_shouldAddEventToRedisStreamWithCorrectData() {
        // Arrange
        String testShortId = "click123";
        String expectedStreamKey = "streams:url-clicks"; // Match constant in UrlService
        RecordId mockRecordId = RecordId.of("12345-0"); // Dummy RecordId to be returned

        // ArgumentCaptor to capture the Map payload sent to Redis
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Object, Object>> payloadCaptor = (ArgumentCaptor<Map<Object, Object>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Map.class);

        // Mock the stream 'add' operation, make it capture the payload and return dummy ID
        when(streamOperationsMock.add(eq(expectedStreamKey), payloadCaptor.capture())).thenReturn(mockRecordId);

        // Act
        urlService.publishClickEvent(testShortId);

        // Verify that the 'add' method was called exactly once with the correct stream key
        verify(streamOperationsMock, times(1)).add(eq(expectedStreamKey), any(Map.class)); // Verify call

        // Assert on the captured payload Map
        Map<Object, Object> capturedPayload = payloadCaptor.getValue();
        assertThat(capturedPayload).isNotNull();
        // Check if the map contains the correct shortId
        assertThat(capturedPayload).containsEntry("shortId", testShortId);
        // Check if the map contains a timestamp
        assertThat(capturedPayload).containsKey("timestamp");
        // Check if the timestamp value is a recent Instant (within 5 seconds)
        try {
            Instant timestamp = Instant.parse((String) capturedPayload.get("timestamp"));
            assertThat(timestamp).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
        } catch (Exception e) {
            fail("Timestamp parsing failed or timestamp not found/valid in payload", e);
        }
    }

    // Optional but good: Test the exception handling within publishClickEvent
    @Test
    void publishClickEvent_whenRedisThrowsException_shouldLogErrorAndNotThrow() {
        // Arrange
        String testShortId = "clickFail";
        String expectedStreamKey = "streams:url-clicks";
        // Simulate a Redis connection error
        RuntimeException redisException = new RedisConnectionFailureException("Test connection failure");

        // Mock the 'add' operation to throw the exception when called
        when(streamOperationsMock.add(eq(expectedStreamKey), any(Map.class))).thenThrow(redisException);

        // Act & Assert
        // Verify that calling the method does NOT throw the exception up,
        // because it should be caught by the try-catch block in the service method.
        assertThatCode(() -> urlService.publishClickEvent(testShortId))
                .doesNotThrowAnyException();

        // Verify the add method was still called (triggering the exception)
        verify(streamOperationsMock, times(1)).add(eq(expectedStreamKey), any(Map.class));

        // Note: Verifying that log.error was called requires more advanced setup
        // (like Mockito spies or custom Logback appenders), which we can skip for now.
        // The main point is ensuring the method handles the error gracefully.
    }

     // TODO: Add tests for AnalyticsService (mocking stream reads, hash increments, acknowledges)

}