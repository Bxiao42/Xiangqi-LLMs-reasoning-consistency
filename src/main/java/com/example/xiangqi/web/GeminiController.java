// GeminiController.java
package com.example.xiangqi.web;

import com.example.xiangqi.llm.GeminiClient;
import com.example.xiangqi.llm.GeminiClient.GeminiResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/llm/gemini")
public class GeminiController {

    private final GeminiClient geminiClient;

    public GeminiController(GeminiClient geminiClient) {
        this.geminiClient = geminiClient;
    }

    // Test endpoint (zero-shot)
    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> test(@RequestParam("msg") String msg) {
        Map<String, Object> resp = new HashMap<>();
        try {
            GeminiResult r = geminiClient.generateZeroShot(msg);
            resp.put("success", true);
            resp.put("model", r.model());
            resp.put("output", r.output());
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    // Chat endpoint (zero-shot)
    @GetMapping
    public ResponseEntity<Map<String, Object>> chat(
            @RequestParam("msg") String msg,
            @RequestParam(value = "sessionId", required = false) String sessionId) {

        Map<String, Object> resp = new HashMap<>();
        try {
            String reply = geminiClient.chat(sessionId, msg);
            resp.put("success", true);
            resp.put("reply", reply);
            resp.put("sessionId", sessionId != null ? sessionId : "default");
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    // POST chat endpoint (zero-shot)
    @PostMapping
    public ResponseEntity<Map<String, Object>> chatPost(@RequestBody GeminiChatRequest req) {
        Map<String, Object> resp = new HashMap<>();
        try {
            String reply = geminiClient.chat(req.sessionId(), req.message());
            resp.put("success", true);
            resp.put("reply", reply);
            resp.put("sessionId", req.sessionId() != null ? req.sessionId() : "default");
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    // Xiangqi move suggestion (zero-shot)
    @PostMapping("/xiangqi")
    public ResponseEntity<Map<String, Object>> xiangqiMove(@RequestBody XiangqiRequest req) {
        Map<String, Object> resp = new HashMap<>();
        try {
            String move = geminiClient.getXiangqiMove(
                    req.sessionId(),
                    req.boardState(),
                    req.currentPlayer(),
                    req.legalMoves(),
                    req.round()
            );

            resp.put("success", move != null);
            resp.put("move", move);
            resp.put("sessionId", req.sessionId() != null ? req.sessionId() : "xiangqi-default");
            if (move == null) {
                resp.put("error", "Cannot generate valid move");
            }
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    // Clear session history
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Map<String, Object>> clearSession(@PathVariable String sessionId) {
        Map<String, Object> resp = new HashMap<>();
        try {
            geminiClient.clearSessionHistory(sessionId);
            resp.put("success", true);
            resp.put("message", "Session cleared");
            resp.put("sessionId", sessionId);
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    // Get statistics (global or session)
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestParam(required = false) String sessionId) {

        Map<String, Object> resp = new HashMap<>();
        try {
            if (sessionId != null && !sessionId.isBlank()) {
                Map<String, Object> sessionStats = geminiClient.getSessionStats(sessionId);
                resp.put("success", true);
                resp.put("sessionId", sessionId);
                resp.put("stats", sessionStats);
            } else {
                Map<String, Object> globalStats = geminiClient.getStats();
                resp.put("success", true);
                resp.put("stats", globalStats);
            }
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    // Reset global statistics
    @PostMapping("/reset-stats")
    public ResponseEntity<Map<String, Object>> resetStats() {
        Map<String, Object> resp = new HashMap<>();
        try {
            geminiClient.resetStats();
            resp.put("success", true);
            resp.put("message", "Statistics reset");
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    // Request record classes
    public record GeminiChatRequest(String message, String sessionId) {}

    public record XiangqiRequest(
            String sessionId,
            String boardState,
            String currentPlayer,
            List<String> legalMoves,
            int round
    ) {}
}