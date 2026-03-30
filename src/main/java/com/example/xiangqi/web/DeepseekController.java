package com.example.xiangqi.web;

import com.example.xiangqi.llm.DeepseekClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/llm")
public class DeepseekController {

    private final DeepseekClient deepseekClient;

    @Autowired
    public DeepseekController(DeepseekClient deepseekClient) {
        this.deepseekClient = deepseekClient;
    }

    @GetMapping("/deepseek")
    public String chat(@RequestParam("msg") String msg,
                       @RequestParam(value = "sessionId", required = false) String sessionId) {
        return deepseekClient.chat(sessionId, msg);
    }
}
