package com.example.testcontainers_demo;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@Testcontainers
@SpringBootTest(webEnvironment = RANDOM_PORT)
public class GreetingControllerIntegrationTest {

  // Azure SQL Edge 이미지를 MSSQL Server의 호환 이미지로 선언
  private static final DockerImageName AZURE_SQL_EDGE_IMAGE = DockerImageName
          .parse("mcr.microsoft.com/azure-sql-edge:latest")
          .asCompatibleSubstituteFor("mcr.microsoft.com/mssql/server");

  @Container
  public static MSSQLServerContainer<?> sqlContainer =
          new MSSQLServerContainer<>(AZURE_SQL_EDGE_IMAGE)
                  .withPassword("MyStrong(?)P@ssword!")
                  // JDBC URL에 암호화 설정을 추가합니다.
                  .withUrlParam("encrypt", "true")
                  .withUrlParam("trustServerCertificate", "true")
                  // 포트 리스닝 전략을 사용하여 내부 1433 포트가 열릴 때까지 대기합니다.
                  .waitingFor(Wait.forListeningPort())
                  .withStartupTimeout(Duration.ofMinutes(5));

  // 동적 프로퍼티 등록: 컨테이너가 기동한 후 R2DBC 연결 정보를 주입
  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    // R2DBC URL에도 암호화 및 인증서 신뢰 설정을 추가합니다.
    final String r2dbcUrl = String.format(
            "r2dbc:sqlserver://%s:%d/master?encrypt=true&trustServerCertificate=true",
            sqlContainer.getHost(),
            sqlContainer.getMappedPort(1433));

    registry.add("spring.r2dbc.url", () -> r2dbcUrl);
    registry.add("spring.r2dbc.username", sqlContainer::getUsername);
    registry.add("spring.r2dbc.password", sqlContainer::getPassword);
  }

  @Autowired
  private WebTestClient webTestClient;

  // JDBC를 통해 스토어드 프로시저를 생성하는 초기화 코드
  @BeforeAll
  static void setUpDatabase() throws Exception {
    // getJdbcUrl()가 컨테이너 생성 시 withUrlParam으로 설정한 파라미터를 포함하는지 확인합니다.
    // 만약 포함되지 않는다면 아래와 같이 수동으로 URL에 파라미터를 추가할 수 있습니다.
    String jdbcUrl = sqlContainer.getJdbcUrl();
    if (!jdbcUrl.contains("encrypt=true")) {
      jdbcUrl += ";encrypt=true;trustServerCertificate=true";
    }
    try (Connection conn = DriverManager.getConnection(
            jdbcUrl,
            sqlContainer.getUsername(),
            sqlContainer.getPassword())) {
      try (Statement stmt = conn.createStatement()) {
        // 1. 기존에 프로시저가 존재하면 DROP
        String dropProcedure = "IF OBJECT_ID('usp_getGreeting', 'P') IS NOT NULL " +
                "DROP PROCEDURE usp_getGreeting;";
        stmt.execute(dropProcedure);

// 2. 새 프로시저를 생성 (CREATE PROCEDURE가 배치의 첫 번째 문장이 되도록)
        String createProcedure = "CREATE PROCEDURE usp_getGreeting @name NVARCHAR(100) AS " +
                "BEGIN " +
                "SET NOCOUNT ON; " +
                "SELECT CONCAT('Hello, ', @name, '!') AS greeting; " +
                "END";
        stmt.execute(createProcedure);
      }
    }
  }

  @Test
  void testGreeting() {
    final String name = "Justin";
    webTestClient.get().uri("/greet?name=" + name)
                 .exchange()
                 .expectStatus().isOk()
                 .expectBody(String.class)
                 .value(response -> {
                   String expected = "Hello, Justin!";
                   if (!response.equals(expected)) {
                     throw new AssertionError("Expected: " + expected + " but got: " + response);
                   }
                 });
  }
}