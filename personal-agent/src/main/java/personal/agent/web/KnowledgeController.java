package personal.agent.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import personal.agent.knowledge.KnowledgeBase;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
final class KnowledgeController {
    private final KnowledgeService knowledge;
    KnowledgeController(KnowledgeService knowledge) { this.knowledge = knowledge; }
    @GetMapping KnowledgeService.State state() { return knowledge.status(); }
    @PostMapping("/refresh") KnowledgeService.State refresh() { return knowledge.refresh(); }
    @PostMapping("/search") KnowledgeBase.Search search(@RequestBody KnowledgeService.Query query) { return knowledge.search(query); }
    @PostMapping("/preview") KnowledgeBase.Preview preview(@RequestBody KnowledgeService.Preview request) { return knowledge.preview(request); }
    @PostMapping("/runs") AgentRuns.Detail start(@RequestBody KnowledgeService.Start request) { return knowledge.start(request); }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> invalid(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("message", error instanceof HttpMessageNotReadableException ? "知识库请求格式有误。" : error.getMessage()));
    }
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> conflict(IllegalStateException error) {
        return ResponseEntity.status(409).body(Map.of("message", error.getMessage()));
    }
}
