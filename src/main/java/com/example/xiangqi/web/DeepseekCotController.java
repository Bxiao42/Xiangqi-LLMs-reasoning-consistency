package com.example.xiangqi.web;

import com.example.xiangqi.llm.DeepseekCotClient;
import com.example.xiangqi.llm.DeepseekCotClient.DeepseekCotResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/llm/deepseek-cot")
public class DeepseekCotController {

    private final DeepseekCotClient deepseekCotClient;

    public DeepseekCotController(DeepseekCotClient deepseekCotClient) {
        this.deepseekCotClient = deepseekCotClient;
    }

    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> test(@RequestParam("msg") String msg) {
        DeepseekCotResult result = deepseekCotClient.chat(msg);

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", result.isSuccess());

        if (result.isSuccess()) {
            resp.put("reasoning", result.getReasoning());
            resp.put("answer", result.getAnswer());
        } else {
            resp.put("error", result.getError());
        }

        return ResponseEntity.ok(resp);
    }
}

