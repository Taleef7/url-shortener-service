package com.urlshortner.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest; // Use RepeatedTest to check randomness
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks; // To inject mocks into UrlService if needed later
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations; // To mock opsForValue() etc.
import org.springframework.test.util.ReflectionTestUtils; // To access private field/method for this specific test
import java.util.concurrent.TimeUnit;


import static org.assertj.core.api.Assertions.assertThat; // AssertJ for fluent assertions
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*; // Import Mockito static methods

@ExtendWith(MockitoExtension.class) // Enable Mockito annotations like @Mock, @InjectMocks
class UrlServiceTest {

    // --- Mocks (for testing methods that use Redis) ---
    @Mock // Creates a mock instance of StringRedisTemplate
    private StringRedisTemplate redisTemplate;

    @Mock // Mock the specific operations interface returned by opsForValue()
    private ValueOperations<String, String> valueOperationsMock;

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

     // TODO: Add tests for getLongUrl (mocking redis get)
     // TODO: Add tests for getUrlStats (mocking redis get and hget)
     // TODO: Add tests for publishClickEvent (mocking redis opsForStream)
     // TODO: Add tests for AnalyticsService (mocking stream reads, hash increments, acknowledges)

}