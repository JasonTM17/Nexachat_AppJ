package com.nexachat.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ActuatorMetricsProbeController {

    @GetMapping("/actuator/metrics")
    public String metrics() {
        return "available";
    }
}
