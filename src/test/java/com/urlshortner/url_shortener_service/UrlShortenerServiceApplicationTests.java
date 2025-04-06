package com.urlshortner.url_shortener_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class UrlShortenerServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
