// GeminiCotController.java
package com.example.xiangqi.web;

import com.example.xiangqi.llm.GeminiCotClient;
import com.example.xiangqi.llm.GeminiCotClient.GeminiCotResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/llm/gemini-cot")
public class GeminiCotController {

    private final GeminiCotClient geminiCotClient;

    public GeminiCotController(GeminiCotClient geminiCotClient) {
        this.geminiCotClient = geminiCotClient;
    }

    // Test endpoint (CoT)
    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> test(
            @RequestParam("msg") String msg,
            @RequestParam(value = "sessionId", required = false) String sessionId) {

        Map<String, Object> resp = new HashMap<>();
        try {
            GeminiCotResult r = geminiCotClient.chatStructured(sessionId, msg);
            buildResponse(resp, r);
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    // CoT chat endpoint (GET)
    @GetMapping
    public ResponseEntity<Map<String, Object>> chat(
            @RequestParam("msg") String msg,
            @RequestParam(value = "sessionId", required = false) String sessionId) {

        Map<String, Object> resp = new HashMap<>();
        try {
            GeminiCotResult r = geminiCotClient.chatStructured(sessionId, msg);
            buildResponse(resp, r);
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    // CoT chat endpoint (POST)
    @PostMapping
    public ResponseEntity<Map<String, Object>> chatPost(@RequestBody GeminiCotChatRequest req) {
        Map<String, Object> resp = new HashMap<>();
        try {
            GeminiCotResult r = geminiCotClient.chatStructured(req.sessionId(), req.message());
            buildResponse(resp, r);
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    // Xiangqi move suggestion (CoT mode)
    @PostMapping("/xiangqi")
    public ResponseEntity<Map<String, Object>> xiangqiMove(@RequestBody XiangqiRequest req) {
        Map<String, Object> resp = new HashMap<>();
        try {
            String userMessage = buildSimpleXiangqiMessage(
                    req.boardState(),
                    req.currentPlayer(),
                    req.legalMoves(),
                    req.round()
            );

            GeminiCotResult r = geminiCotClient.chatStructured(req.sessionId(), userMessage);

            resp.put("success", r.isSuccess() && r.getMove() != null);
            resp.put("sessionId", req.sessionId() != null ? req.sessionId() : "xiangqi-default");

            if (r.isSuccess()) {
                resp.put("reasoning", r.getReasoning());
                resp.put("answer", r.getAnswer());
                resp.put("move", r.getMove());
                resp.put("round", req.round());
                resp.put("player", req.currentPlayer());
            } else {
                resp.put("error", r.getError());
            }
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    // Simple message building – only passes information (AI prompts remain in Chinese)
    private String buildSimpleXiangqiMessage(String boardState, String playerSide,
                                             List<String> legalMoves, int round) {
        return String.format(
                "棋盘状态:\n%s\n\n当前行棋方: %s\n回合: %d\n合法走法: %s",
                boardState, playerSide, round, String.join(", ", legalMoves)
        );
    }

    // Clear session history
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Map<String, Object>> clearSession(@PathVariable String sessionId) {
        Map<String, Object> resp = new HashMap<>();
        try {
            geminiCotClient.clearSessionHistory(sessionId);
            resp.put("success", true);
            resp.put("message", "CoT session cleared");
            resp.put("sessionId", sessionId);
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    // Get session statistics
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestParam(value = "sessionId", required = false) String sessionId) {

        Map<String, Object> resp = new HashMap<>();
        try {
            if (sessionId != null && !sessionId.isBlank()) {
                Map<String, Object> sessionStats = geminiCotClient.getSessionStats(sessionId);
                resp.put("success", true);
                resp.put("sessionId", sessionId);
                resp.put("stats", sessionStats);
            } else {
                resp.put("success", true);
                resp.put("message", "Please provide sessionId to get session stats");
                resp.put("availableSessions", "All sessions");
            }
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    // Helper to build response
    private void buildResponse(Map<String, Object> resp, GeminiCotResult r) {
        if (r == null) {
            resp.put("success", false);
            resp.put("error", "API returned null result");
            return;
        }

        resp.put("success", r.isSuccess());
        if (r.isSuccess()) {
            resp.put("reasoning", r.getReasoning());
            resp.put("answer", r.getAnswer());
            resp.put("move", r.getMove());
        } else {
            resp.put("error", r.getError());
        }
    }

    // Request record classes
    public record GeminiCotChatRequest(String message, String sessionId) {}

    public record XiangqiRequest(
            String sessionId,
            String boardState,
            String currentPlayer,
            List<String> legalMoves,
            int round
    ) {}
}