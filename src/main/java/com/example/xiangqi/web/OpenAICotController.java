// OpenAICotController.java
package com.example.xiangqi.web;

import com.example.xiangqi.llm.OpenAICotClient;
import com.example.xiangqi.llm.OpenAICotClient.OpenAICotResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/llm")
public class OpenAICotController {

    private final OpenAICotClient openAICotClient;

    public OpenAICotController(OpenAICotClient openAICotClient) {
        this.openAICotClient = openAICotClient;
    }

    // Endpoint for OpenAI CoT chat
    @GetMapping("/openai-cot")
    public ResponseEntity<Map<String, Object>> chat(
            @RequestParam("msg") String msg,
            @RequestParam(value = "sessionId", required = false) String sessionId) {

        Map<String, Object> resp = new HashMap<>();

        try {
            OpenAICotResult r = openAICotClient.chatStructured(sessionId, msg);

            resp.put("success", r != null && r.isSuccess());
            if (r != null && r.isSuccess()) {
                resp.put("answer", r.getAnswerText());
                resp.put("reasoning", r.getReasoningSummary() == null ? "" : r.getReasoningSummary());
                resp.put("move", r.getMove());
            } else {
                resp.put("error", r == null ? "OpenAI CoT returned null" : r.getError());
            }

        } catch (Exception e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }

        return ResponseEntity.ok(resp);
    }
}