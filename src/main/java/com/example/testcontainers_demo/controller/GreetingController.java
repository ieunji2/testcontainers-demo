package com.example.testcontainers_demo.controller;

import com.example.testcontainers_demo.service.GreetingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
@RestController
public class GreetingController {

  private final GreetingService greetingService;

  @GetMapping("/greet")
  public Mono<String> greet(@RequestParam String name) {
    return greetingService.getGreeting(name);
  }
}
