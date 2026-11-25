package io.dockvue.core;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {
    public record Status(String service, String phase) {}

    @GetMapping("/api/v1/status")
    public Status status() {
        return new Status("core-api", "01");
    }
}
