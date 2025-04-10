# URL Shortener Service

This project is a RESTful web service built using Java and Spring Boot that shortens long URLs. It provides API endpoints to create short URLs, redirect users from the short URL to the original long URL, and retrieve basic click statistics.

The service utilizes Redis for efficient data storage:
* **Key-Value:** Stores the mapping between the generated short ID and the original long URL.
* **Redis Streams:** Used for asynchronous processing of click events generated during redirection.
* **Redis Hashes:** Stores the click counts for basic analytics.

This project demonstrates core concepts of Spring Boot web development, data persistence with Redis, asynchronous processing patterns, API design, unit/integration testing, and cloud deployment.

## Features

* **URL Shortening:** Accepts a long URL via a `POST` request and returns a unique, shorter URL alias.
* **Redirection:** Handles `GET` requests on the short URL path (`/{shortId}`) and redirects the user (HTTP 302) to the original long URL.
* **Asynchronous Click Tracking:** Upon successful redirection, publishes a click event (containing `shortId` and `timestamp`) to a Redis Stream.
* **Click Counting:** A background scheduled task consumes events from the Redis Stream, processes them, and increments a click counter stored in a Redis Hash for each `shortId`.
* **Statistics Retrieval:** Provides an endpoint (`GET /api/stats/{shortId}`) to fetch the original long URL and the total click count for a given short ID.

## Technologies Used

* **Language:** Java 21
* **Framework:** Spring Boot 3.x (using Spring Web, Spring Data Redis)
* **Database/Cache:** Redis (Leveraging Key-Value, Streams, and Hashes)
    * **Redis Client:** Lettuce (provided by Spring Data Redis)
* **Build Tool:** Apache Maven
* **Containerization:** Docker & Docker Compose (for local Redis environment), Dockerfile (for deployment build)
* **Testing:**
    * JUnit 5
    * Mockito
    * AssertJ
    * Testcontainers (for integration tests with Redis)
* **Logging:** SLF4j (with Logback implementation via Spring Boot)
* **Validation:** Jakarta Bean Validation
* **Deployment:** Render

## Setup and Running Locally

### Prerequisites

* **Java Development Kit (JDK):** Version 21 or higher installed and configured (`JAVA_HOME` set).
* **Apache Maven:** Installed and configured (accessible via `mvn` command).
* **Docker Desktop:** Installed and **running**.

### Steps

1.  **Clone the Repository:**
    ```bash
    git clone [https://github.com/Taleef7/url-shortener-service.git](https://github.com/Taleef7/url-shortener-service.git)
    cd url-shortener-service
    ```

2.  **Start Dependencies (Redis):**
    Ensure Docker Desktop is running. Open a terminal in the project root directory and run:
    ```bash
    docker compose up -d
    ```
    This command reads the `docker-compose.yml` file and starts a Redis container in the background. You can verify it's running using `docker ps`.

3.  **Build and Run the Application:**
    In the same terminal (project root), run the application using the Maven wrapper:

    * **Windows (PowerShell):**
        ```bash
        .\mvnw.cmd spring-boot:run
        ```
    * **Windows (CMD):**
        ```bash
        mvnw.cmd spring-boot:run
        ```
    * **macOS / Linux:**
        ```bash
        ./mvnw spring-boot:run
        ```
    The application will start up and listen on `http://localhost:8081` by default (as configured in `application.properties`).

## API Endpoints

### 1. Shorten URL

* **Method:** `POST`
* **Path:** `/api/urls`
* **Request Body:** (`application/json`)
    ```json
    {
        "longUrl": "[https://example.com/some/very/long/url/to/shorten]"
    }
    ```
* **Success Response:**
    * **Code:** `201 Created`
    * **Body:** (`application/json`)

        ```json
        {
            "shortUrl": "http://localhost:8081/AbCdEfG"
        }
        ```
        *(Where `AbCdEfG` is the generated unique short ID. Note: Use the deployed base URL when interacting with the live service.)*
* **Error Response:**
    * `400 Bad Request`: If the request body is invalid or the `longUrl` fails basic validation (e.g., empty, not starting with http/https).

### 2. Redirect to Long URL

* **Method:** `GET`
* **Path:** `/{shortId}` (e.g., `/AbCdEfG`)
* **Description:** Accessing this path directly in a web browser (or using any HTTP client that follows redirects) relative to the service's base URL will result in an **HTTP 302 Found** redirect to the original long URL associated with the `{shortId}`. This action also triggers the asynchronous click tracking event.
* **Error Response:**
    * `404 Not Found`: If the `{shortId}` does not exist in the system.

### 3. Get URL Statistics

* **Method:** `GET`
* **Path:** `/api/stats/{shortId}` (e.g., `/api/stats/AbCdEfG`)
* **Success Response:**
    * **Code:** `200 OK`
    * **Body:** (`application/json`)
        ```json
        {
            "shortId": "AbCdEfG",
            "longUrl": "[https://example.com/some/very/long/url/to/shorten](https://example.com/some/very/long/url/to/shorten)",
            "clicks": 5
        }
        ```
        *(Where `clicks` is the total number of times the short link has been accessed/redirected)*
* **Error Response:**
    * `404 Not Found`: If the `{shortId}` does not exist in the system.

## Running Tests

To execute the unit and integration tests included in the project, run the following Maven command from the project root directory:

* **Windows (CMD/PowerShell):**
    ```bash
    mvnw.cmd test
    ```
* **macOS / Linux:**
    ```bash
    ./mvnw test
    ```

## Live Deployment

A live version of this service is deployed on Render's free tier:

* **Base URL:** `https://url-shortener-service-ef2d.onrender.com/`

You can use the API endpoints described above with this base URL.

**Note:** This deployment uses Render's free Redis instance which **does not have data persistence**. All shortened URLs and click counts will be lost if the Redis instance restarts. Free web services also spin down after inactivity and may take 30-60 seconds to respond to the first request after being idle.

## Acknowledgements

This project was developed with guidance and assistance from Google's Gemini AI. Its help was utilized for:
* Brainstorming project ideas and scope definition.
* Guidance on technology choices (Java, Spring Boot, Redis Streams).
* Step-by-step environment setup (JDK, Maven, Docker).
* Code generation for initial structure and feature implementation.
* Troubleshooting and debugging errors (like package structure issues, deployment errors).
* Explaining concepts and best practices (Git, Testing, REST APIs, Dockerfiles).