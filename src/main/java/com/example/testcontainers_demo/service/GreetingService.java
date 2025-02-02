package com.example.testcontainers_demo.service;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
@Service
public class GreetingService {

  private final DatabaseClient databaseClient;

  public Mono<String> getGreeting(String name) {
    return databaseClient
            .sql("EXEC usp_getGreeting @name = :name")
            .bind("name", name)
            .map(row -> row.get("greeting", String.class))
            .one();
  }
}
