package com.agentgate.tool;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tool-risks")
@RequiredArgsConstructor
public class ToolRiskController {

    private final ToolRiskService service;

    @GetMapping
    public ResponseEntity<List<ToolRisk>> list(@RequestParam(defaultValue = "false") boolean refresh) {
        return ResponseEntity.ok(service.list(refresh));
    }

    @PutMapping
    public ResponseEntity<ToolRisk.Tool> set(@Valid @RequestBody ToolRiskRequest request) {
        return ResponseEntity.ok(service.set(request));
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(@RequestParam String server, @RequestParam String tool) {
        service.clear(server, tool);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/apply-suggestions")
    public ResponseEntity<Map<String, Integer>> applySuggestions() {
        return ResponseEntity.ok(Map.of("applied", service.applySuggestions()));
    }
}
