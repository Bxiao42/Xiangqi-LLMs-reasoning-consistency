package com.example.xiangqi.web;

import com.example.xiangqi.llm.OpenAIClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/llm")
public class OpenAIController {

    private final OpenAIClient openAIClient;

    @Autowired
    public OpenAIController(OpenAIClient openAIClient) {
        this.openAIClient = openAIClient;
    }

    @GetMapping("/openai")
    public String chat(@RequestParam("msg") String msg,
                       @RequestParam(value = "sessionId", required = false) String sessionId) {
        return openAIClient.chat(sessionId, msg);
    }
}
