# --- Stage 1: Build Stage ---
# Use an official Maven image with JDK 21 based on Eclipse Temurin
FROM maven:3.9-eclipse-temurin-21 AS builder

# Set the working directory inside the container
WORKDIR /app

# Copy the Maven wrapper first (leverages Docker cache)
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Download dependencies (also leverages Docker cache)
# The '-B' flag runs Maven in non-interactive (batch) mode
RUN ./mvnw dependency:go-offline -B

# Copy the source code
COPY src ./src

# Package the application, skipping tests
RUN ./mvnw package -DskipTests

# --- Stage 2: Runtime Stage ---
# Use a slim JRE (Java Runtime Environment) image based on Eclipse Temurin and Ubuntu Jammy
FROM eclipse-temurin:21-jre-jammy

# Set the working directory
WORKDIR /app

# Copy *only* the built JAR file from the builder stage
# Make sure the JAR filename matches your pom.xml artifactId and version
COPY --from=builder /app/target/url-shortener-service-0.0.1-SNAPSHOT.jar app.jar

# Expose the port the application will run on
# Render typically injects a PORT environment variable (e.g., 10000) which Spring Boot uses.
# If not, uncomment and adjust EXPOSE and potentially set server.port via ENV var.
# EXPOSE 8081

# Command to run the application when the container starts
ENTRYPOINT ["java", "-jar", "app.jar"]