package com.example.testcontainers_demo;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.sql.DriverManager;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@Testcontainers
@SpringBootTest(webEnvironment = RANDOM_PORT)
class GreetingControllerIntegrationTest {

  private static final String PROCEDURE_NAME = "usp_getGreeting";
  private static final String EXPECTED_GREETING = "Hello, TestUser!";
  private static final DockerImageName AZURE_SQL_EDGE_IMAGE =
          DockerImageName.parse("mcr.microsoft.com/azure-sql-edge:latest")
                         .asCompatibleSubstituteFor("mcr.microsoft.com/mssql/server");

  @Container
  static final MSSQLServerContainer<?> sqlContainer =
          new MSSQLServerContainer<>(AZURE_SQL_EDGE_IMAGE)
                  .withUrlParam("trustServerCertificate", "true")
                  .waitingFor(Wait.forListeningPort())
                  .withStartupTimeout(Duration.ofMinutes(5));

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.r2dbc.url", () -> String.format(
            "r2dbc:sqlserver://%s:%d/master?encrypt=true&trustServerCertificate=true",
            sqlContainer.getHost(), sqlContainer.getMappedPort(1433)));
    registry.add("spring.r2dbc.username", sqlContainer::getUsername);
    registry.add("spring.r2dbc.password", sqlContainer::getPassword);
  }

  @Autowired
  private WebTestClient webTestClient;
  @Autowired
  private DatabaseClient databaseClient;

  @BeforeAll
  static void initDb() throws Exception {
    var jdbcUrl = sqlContainer.getJdbcUrl();
    if (!jdbcUrl.contains("encrypt=true")) {
      jdbcUrl += ";encrypt=true;trustServerCertificate=true";
    }
    try (var conn = DriverManager.getConnection(jdbcUrl, sqlContainer.getUsername(), sqlContainer.getPassword());
         var stmt = conn.createStatement()) {

      stmt.execute("""
                    IF OBJECT_ID('usp_getGreeting', 'P') IS NOT NULL
                        DROP PROCEDURE usp_getGreeting;
                    """);

      stmt.execute("""
                    CREATE PROCEDURE usp_getGreeting @name NVARCHAR(100) AS
                    BEGIN
                        SET NOCOUNT ON;
                        SELECT CONCAT('Hello, ', @name, '!') AS greeting;
                    END
                    """);
    }
  }

  @Test
  void testGreeting() {
    var name = "TestUser";
    webTestClient.get().uri("/greet?name=" + name)
                 .exchange()
                 .expectStatus().isOk()
                 .expectBody(String.class)
                 .value(response -> assertEquals(EXPECTED_GREETING, response,
                                                 () -> "Expected: " + EXPECTED_GREETING + " but got: " + response));
  }

  @Test
  void testStoredProcedureWithDatabaseClient() {
    var query = "EXEC " + PROCEDURE_NAME + " @name = :name";
    Mono<String> resultMono = databaseClient.sql(query)
                                            .bind("name", "TestUser")
                                            .map(row -> row.get("greeting", String.class))
                                            .one();

    StepVerifier.create(resultMono)
                .assertNext(result -> assertEquals(EXPECTED_GREETING, result,
                                                   () -> "Expected: " + EXPECTED_GREETING + " but got: " + result))
                .verifyComplete();
  }
}