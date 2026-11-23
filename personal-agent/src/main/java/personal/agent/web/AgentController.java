package personal.agent.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
final class AgentController {
    private final AgentRuns runs;

    AgentController(AgentRuns runs) {
        this.runs = runs;
    }

    @GetMapping("/config")
    AgentRuns.Config config() { return runs.config(); }

    @GetMapping("/runs")
    List<AgentRuns.Summary> runs() { return runs.list(); }

    @GetMapping("/runs/{id}")
    AgentRuns.Detail detail(@PathVariable("id") UUID id) { return runs.detail(id); }

    @GetMapping("/runs/{id}/note")
    ResponseEntity<String> note(@PathVariable("id") UUID id) {
        var detail = runs.detail(id);
        if (detail.note() == null || detail.answer() == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=\"agent-note.md\"")
                .body(detail.answer().stripTrailing() + "\n");
    }

    @PostMapping(value = "/runs", consumes = "application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    AgentRuns.Detail start(@RequestBody AgentRuns.Request request) { return runs.start(request); }

    @PostMapping(value = "/runs/{id}/resume", consumes = "application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    AgentRuns.Detail resume(@PathVariable("id") UUID id) { return runs.resume(id); }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> invalidJson() {
        return ResponseEntity.badRequest().body(Map.of("message", "任务配置格式有误。"));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> conflict(IllegalStateException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(AgentRuns.UnknownRun.class)
    ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "找不到这条运行记录。"));
    }
}
