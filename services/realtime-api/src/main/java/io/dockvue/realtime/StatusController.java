package io.dockvue.realtime;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class StatusController {
    public record Status(String service, String phase) {}

    @GetMapping("/api/v1/status")
    public Mono<Status> status() {
        return Mono.just(new Status("realtime-api", "01"));
    }
}
