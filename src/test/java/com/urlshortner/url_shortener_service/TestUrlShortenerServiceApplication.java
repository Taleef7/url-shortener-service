package com.urlshortner.url_shortener_service;

import org.springframework.boot.SpringApplication;

public class TestUrlShortenerServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(UrlShortenerServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
