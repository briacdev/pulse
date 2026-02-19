package com.pulse.app.api;

import com.pulse.agent.PulseInstrumentationInstaller;
import com.pulse.agent.instrumentation.DispatcherServletAdvice;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/debug")
public class DebugController {

    @GetMapping("/counters")
    public ResponseEntity<Map<String, Object>> counters() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.putAll(PulseInstrumentationInstaller.debugCounters());
        output.putAll(DispatcherServletAdvice.debugCounters());
        return ResponseEntity.ok(output);
    }
}
