package ru.hackathon.heatnetwork.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.hackathon.heatnetwork.model.Model;

@RestController
public class HealthController {
    @GetMapping("/api/health")
    public Map<String, String> health() {
        // Calculation modes served by this backend.
        return Map.of("status", "UP", "contractVersion", Model.CONTRACT_VERSION,
                "implementation", "2d+depth");
    }
}
