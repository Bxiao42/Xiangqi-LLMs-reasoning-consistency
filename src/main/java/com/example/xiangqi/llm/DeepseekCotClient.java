// DeepseekCotClient.java
package com.example.xiangqi.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class DeepseekCotClient {

    @Value("${deepseek.api.url}")
    private String apiUrl;

    @Value("${deepseek.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Multi-turn chat history (keep last 5 turns)
    private final Map<String, List<Map<String, String>>> chatHistories = new HashMap<>();
    private static final int MAX_HISTORY_LENGTH = 5;

    // Turn count per session
    private final Map<String, Integer> sessionTurnCount = new HashMap<>();

    // Basic Xiangqi rules (without "Current board:")
    private static final String XIANGQI_RULES =
            "中国象棋规则详解：\n" +
                    "1. 红方棋子（大写）：帅(K)、士(A)、相(E)、马(H)、车(R)、炮(C)、兵(P)\n" +
                    "2. 黑方棋子（小写）：将(k)、士(a)、相(e)、马(h)、车(r)、炮(c)、卒(p)\n" +
                    "3. 棋盘坐标：列a-i（从左到右），行0-9（红方在下方行0-4，黑方在上方行5-9）\n" +
                    "   特别注意：a列是最左边，i列是最右边！行0在最下面，行9在最上面！\n" +
                    "   红方在下方（行0-4），黑方在上方（行5-9），别走反了！\n" +
                    "4. 棋子走法规则及犯规案例（非常重要，避免走错）：\n" +
                    "   - 车(R/r)：直行任意格，不能斜走，不能越子\n" +
                    "     合法：a0a3（垂直移动）、c2e2（水平移动）\n" +
                    "     犯规：a0b1（斜走）、a0a3但a1有子（越子）\n" +
                    "\n" +
                    "   - 马(H/h)：走'日'字，先直一格再斜一格，不能蹩腿（前进方向有子则不能走）\n" +
                    "     合法：b0c2（向右走日字）、b0a2（向左走日字）\n" +
                    "     犯规：b0c2但b1有子（蹩马腿）、b0d1（不是日字）\n" +
                    "     蹩腿规则：如果马要从b0走到c2，需要检查b1是否有子阻挡\n" +
                    "\n" +
                    "   - 相(E/e)：走'田'字，对角移动两格，不能过河（红相不能到行5-9，黑象不能到行0-4），田字中心有子不能走\n" +
                    "     合法：红相：c0a2（左上）、c0e2（右上）\n" +
                    "     合法：黑象：c9a7（左下）、c9e7（右下）\n" +
                    "     犯规：红相c0a4（过河到行4以上）、红相c0a2但b1有子（田心蹩脚）\n" +
                    "     犯规：黑象c9a5（过河到行4以下）、黑象c9e7但d8有子（田心蹩脚）\n" +
                    "\n" +
                    "   - 士(A/a)：九宫内斜走一格，只能在九宫（红方d7-f9，黑方d0-f2）\n" +
                    "     合法：红士：e8d7（左上）、e8f7（右上）\n" +
                    "     合法：黑士：e1d0（左下）、e1f0（右下）\n" +
                    "     犯规：红士e8e7（直走）、红士e8c9（走出九宫）\n" +
                    "     犯规：黑士e1e2（直走）、黑士e1c0（走出九宫）\n" +
                    "\n" +
                    "   - 帅(K)/将(k)：九宫内直走一格，不能出九宫，不能对面将（两将不能在同一列无遮挡）\n" +
                    "     合法：红帅：e9e8（下走）、e9f9（右走）\n" +
                    "     合法：黑将：e0e1（上走）、e0d0（左走）\n" +
                    "     犯规：红帅e9e7（走两格）、红帅e9d8（斜走）\n" +
                    "     犯规：黑将e0c0（走出九宫）、帅将同在e列中间无子（对面将）\n" +
                    "\n" +
                    "   - 炮(C/c)：直行任意格，吃子需隔一子（必须隔且只能隔一个棋子）\n" +
                    "     合法移动：a2a6（无子时直行）\n" +
                    "     合法吃子：a2a6中间a4有子（隔一子吃a6）\n" +
                    "     犯规：a2a6中间a3和a4都有子（隔多子）、a2a6直接吃子（无隔子）\n" +
                    "\n" +
                    "   - 兵(P)/卒(p)：前进一格，过河（红兵过河指行≥5，黑卒过河指行≤4）后可左右移动一格，不能后退\n" +
                    "     合法：红兵a3a4（前进）、红兵a5a5（过河后左右）\n" +
                    "     合法：黑卒a6a5（前进）、黑卒a4a4（过河后左右）\n" +
                    "     犯规：红兵a3a2（后退）、红兵a3b4（斜走）\n" +
                    "     犯规：黑卒a6a7（后退）、红兵a4a4但未过河（未过河横移）\n" +
                    "\n" +
                    "5. 特殊规则：\n" +
                    "   - 将军：攻击对方帅/将\n" +
                    "   - 将死：被将军且无法解除\n" +
                    "   - 困毙：未被将军但无合法移动\n" +
                    "   - 长将、长捉：重复局面可能犯规\n" +
                    "\n" +
                    "输出要求：\n" +
                    "1. 你必须返回4个字符的移动坐标，格式如：a0a1（起点列行到终点列行）\n" +
                    "2. 只返回移动坐标，不要解释，不要分析\n" +
                    "3. 确保移动符合象棋规则，否则会被记录犯规\n" +
                    "\n" +
                    "犯规警告：\n" +
                    "1. 非法移动（如马蹩腿、相过河、将出九宫等）会被记录为犯规\n" +
                    "2. 无效格式（不是4字符坐标）会被记录为犯规\n" +
                    "3. 连续犯规或无法生成合法移动会导致判负\n" +
                    "\n" +
                    "以下是五套经典棋谱，请认真学习走法策略：\n" +
                    "\n" +
                    "第一套：\n" +
                    "b2e2(炮二平五) b9c7(马2进3) b0c2(马二进三) c9e7(象3进5) h0g2(马八进七) d9e8(士4进5) i0h0(车九进一) a9d9(车1平4) c0e2(车一平二) h7f7(炮8平6) g0h2(炮八平九) h7h6(马8进7) h0e0(车九平八) b7b5(炮2平1) e3e4(兵三进一) d9d4(车4进5) e0e1(兵五进一) a6a5(卒9进1) e0e9(车八进六) b5a5(炮1退2) g2g6(炮九进四) c7a8(马3退4) e9e8(车八进二) a5b5(炮1进2) g2f4(马七进五) e6e5(卒7进1) e4e5(兵三进一) e7e5(象5进7) g6e6(炮九平五) f7e7(炮6平5) e1e2(兵五进一) a9a6(车9进3) e6c6(炮五平二) e7e4(炮5进4) c2e4(马三进五) b5e5(炮1平5) e4f6(马五进六) e5e2(炮5进5) c0e2(相七进五) c6c5(卒3进1) g3g4(兵九进一) d4e4(车4平5) e2e3(兵五平四) h6g8(马7退9) c6a6(炮二退五) e4h4(车5平8) f6d7(马六进四) a6d6(车9平7) a6c6(炮二平三) h4h0(车8进4) c6c9(炮三进五) h0h7(车8退8) e2c0(相五退七)\n" +
                    "\n" +
                    "第二套：\n" +
                    "b2e2(炮二平五) b9c7(马2进3) b0c2(马二进三) b7b5(炮2退1) g0h2(炮八平六) h7h6(马8进7) h0g2(马八进七) e6e5(卒7进1) i0h0(车九平八) b5b7(炮2平7) c0e2(车一平二) h7e7(炮8进2) e2e3(车二进四) e6e5(卒7进1) e3e4(兵三进一) b7b7(炮7平8) e4e5(兵三进一) b7b3(炮8进4) c2d4(马三进二) a9c9(车9平8) h0h9(车八进六) a9a8(车1进1) h9h7(车八平七) h6g8(马7退5) h2g4(炮六进五) b3b7(炮8退2) e5e6(兵三进一) b7b3(炮8进2) d4f5(马二进四) a8d8(车1平4) e2e6(炮五进四) c9e7(象7进5) f5e7(马四进五) b3e3(炮8平5) c0e2(相七进五) e3e6(炮5退2) h7h6(车七进一) c9d9(车8进4) e6f6(兵三平四) d9d5(车8平4) f6f7(兵四进一) d5d4(车4进1) h6h5(车七进一) d4d5(车4平5) e6e5(炮五进二) d9d4(士6进5) f7f8(兵四平五) d5d6(车5退2) h5h6(车七退二) d8d3(车4平3) h6i6(车九平一) d3d2(车3进4) i6i7(车一进一)\n" +
                    "\n" +
                    "第三套：\n" +
                    "b2e2(炮二平五) b9c7(马2进3) g0h2(炮八平七) b7b5(炮2退1) e3e4(兵三进一) b5b7(炮2平7) b0c2(马二进三) a9b9(车1平2) h0g2(马八进九) b9b4(车2进5) c0e2(相三进一) h7h6(马8进9) c0e2(车一平二) a9c9(车9平8) i0h0(车九平八) b4d4(车2平4) d0d1(仕四进五) h7e7(炮8进4) h0h9(车八进七) c9c7(车8进2) h2h6(炮七进四) c9e7(象3进1) h6h7(炮七平三) b7b3(炮7平3) e2c2(炮五平七) d4d7(车4平7) c2c7(炮七进五) d7d2(车7进2) h7h9(炮三平九) d7d5(车7退5) c0e2(相七进五) d5d3(车7平3) h9h7(车八平七) c7d7(车8平3) e2e3(车二进三) d3d2(车3进1) h9h7(炮九退二) b3b2(炮3进5) g2f4(马九进七) d2d3(车3进3) h7e7(炮九平五) d9e8(士4进5) g3g4(兵九进一) e6e5(卒5进1)\n" +
                    "\n" +
                    "第四套：\n" +
                    "b2e2(炮二平五) b9c7(马2进3) e3e4(兵三进一) h7e7(炮8平5) b0c2(马二进三) c6c5(卒3进1) g0h2(炮八平七) c7d5(马3进4) h2h5(炮七进三) h7h6(马8进7) h0g2(马八进七) b7b3(炮2平3) i0h0(车九平八) a9c9(车9平8) c0d0(车一进一) b3b4(炮3平4) e4e5(兵三进一) e6e5(卒7进1) h5h7(炮七平三) c9d9(车8进4) h7h9(炮三进四) d9e8(士6进5) h0h5(车八进五) d5d2(马4进3) h5h7(车八平二) h6g8(马7进8) d0d2(车一平二) g8h6(马8退7) c2d4(马三进四) d2e0(马3进5) c0e2(相三进五) a9b9(车1平2) h9h0(炮三平一) e9f9(将5平6) g2f4(马七进六) e7e4(炮5进4) d1d2(仕四进五) b9b4(车2进5) f4e6(马六进五) h6g8(马7进5) d4e6(马四进五) b4b7(车2平7) d2d6(车二平四) b4b6(炮4平6) d6d7(车四进二) b7b6(车7退2) d7d6(车四退三)\n" +
                    "\n" +
                    "第五套：\n" +
                    "b2e2(炮二平五) b9c7(马2进3) b0c2(马二进三) e6e5(卒7进1) c0e2(车一平二) c6c5(卒3进1) g0h2(炮八平七) a9b9(车9进2) g3g4(兵七进一) c7d5(马3进4) e2e3(车二进四) c9e7(象7进5) e2e6(炮五进四) d9e8(士4进5) g4g5(兵七进一) d5c7(马4退3) e6e5(炮五退一) b7b3(炮2进4) e3e4(兵三进一) e6e5(卒7进1) e3e5(车二平三) h7g7(炮8平7) c0e2(相七进五) e9f9(将5平4) g5g6(兵七进一) c7e6(马3进5) e5e6(车三平六) f9e9(将4平5) c2d4(马三进四) e6d8(马5进7) d4f5(马四进六) g9i9(象3进1) f5h6(马六进八) b3b2(炮2平3) e6e3(车六平七) g7g6(炮7退1) e3e2(车七退一) e9f9(将5平4) g6g7(兵七进一) a9b9(车1平2) e2e6(车七平六) f9e9(将4平5) h6g8(马八进六)\n" +
                    "\n" +
                    "当前局面：\n";

    // System prompt for first round: rules + CoT requirements
    private static final String SYSTEM_PROMPT_FIRST_ROUND =
            XIANGQI_RULES +
                    "\n\n=== 思维链推理要求 ===\n" +
                    "你是一个中国象棋AI推理助手。\n" +
                    "你需要进行思维链推理（Chain-of-Thought），仔细分析棋盘局面。\n" +
                    "在最终给出移动建议之前，先进行推理分析。\n" +
                    "\n" +
                    "输出格式要求：\n" +
                    "1. 先进行推理分析（用中文）\n" +
                    "2. 然后给出最终移动建议（格式为：a0a1）\n";

    public DeepseekCotClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(300_000);
        this.restTemplate = new RestTemplate(factory);
    }

    // Two-parameter overload (backward compatibility)
    public DeepseekCotResult chatStructured(String sessionId, String userMessage) {
        return chatStructured(sessionId, userMessage, 0, "UNKNOWN");
    }

    // Structured chat with four parameters
    public DeepseekCotResult chatStructured(String sessionId, String userMessage, int round, String player) {
        try {
            if (sessionId == null || sessionId.isBlank()) sessionId = "default";

            List<Map<String, String>> history = chatHistories.computeIfAbsent(sessionId, k -> new ArrayList<>());

            // Add system prompt only in first round
            if (history.isEmpty()) {
                history.add(Map.of("role", "system", "content", SYSTEM_PROMPT_FIRST_ROUND));
            }

            history.add(Map.of("role", "user", "content", userMessage));

            // Limit history length, always keep first system message
            if (history.size() > MAX_HISTORY_LENGTH * 2 + 1) {
                List<Map<String, String>> newHistory = new ArrayList<>();
                newHistory.add(history.get(0));
                newHistory.addAll(history.subList(history.size() - MAX_HISTORY_LENGTH * 2, history.size()));
                history = newHistory;
                chatHistories.put(sessionId, history);
            }

            // Build request
            List<Map<String, String>> messages = new ArrayList<>(history);

            System.out.println("[Deepseek CoT] Sending API request, session: " + sessionId +
                    ", round: " + round + ", history turns: " + (history.size() / 2));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> body = new HashMap<>();
            body.put("model", "deepseek-reasoner");
            body.put("messages", messages);
            body.put("stream", false);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                return DeepseekCotResult.error("HTTP error: " + response.getStatusCodeValue());
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return DeepseekCotResult.error("No choices in response");
            }

            JsonNode messageNode = choices.get(0).path("message");
            String reasoning = messageNode.path("reasoning_content").asText("");
            String content = messageNode.path("content").asText("");

            // Add assistant reply to history
            history.add(Map.of("role", "assistant", "content", content));

            // Update turn count
            sessionTurnCount.put(sessionId, sessionTurnCount.getOrDefault(sessionId, 0) + 1);

            String extractedReasoning = reasoning.isEmpty() ? content : reasoning;
            String move = extractMoveFromText(content);

            System.out.println("[Deepseek CoT] Suggested move: " + (move != null ? move : "not found"));
            return DeepseekCotResult.success(extractedReasoning, move, move);

        } catch (HttpClientErrorException e) {
            String errorMsg = "HTTP " + e.getStatusCode() + " - " + e.getResponseBodyAsString();
            System.err.println("[Deepseek CoT] HTTP error: " + errorMsg);
            e.printStackTrace();
            return DeepseekCotResult.error(errorMsg);
        } catch (ResourceAccessException e) {
            String errorMsg = "Deepseek API request timeout or network error: " + e.getMessage();
            System.err.println("[Deepseek CoT] " + errorMsg);
            e.printStackTrace();
            return DeepseekCotResult.error(errorMsg);
        } catch (Exception e) {
            String errorMsg = "Exception: " + e.getMessage();
            System.err.println("[Deepseek CoT] Unknown exception: " + errorMsg);
            e.printStackTrace();
            return DeepseekCotResult.error(errorMsg);
        }
    }

    // Get Xiangqi move for given session and round
    public String getXiangqiMove(String sessionId, String boardState, String currentPlayer, int round) {
        if (sessionId == null || sessionId.isBlank()) sessionId = "xiangqi-default";
        System.out.println("[Deepseek CoT Xiangqi] Session: " + sessionId + ", round: " + round);
        DeepseekCotResult result = chatStructured(sessionId, boardState, round, currentPlayer);
        if (!result.isSuccess()) return null;
        return result.getMove();
    }

    // Backward compatibility
    public DeepseekCotResult chat(String userMessage) {
        return chatStructured("default", userMessage, 0, "UNKNOWN");
    }

    // Clear session history
    public void clearSessionHistory(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            chatHistories.remove(sessionId);
            sessionTurnCount.remove(sessionId);
            System.out.println("[Deepseek CoT] Cleared session: " + sessionId);
        }
    }

    // Get session stats
    public Map<String, Object> getSessionStats(String sessionId) {
        Map<String, Object> stats = new HashMap<>();
        if (sessionId != null) {
            List<Map<String, String>> history = chatHistories.get(sessionId);
            stats.put("historyLength", history != null ? history.size() : 0);
            stats.put("turnCount", sessionTurnCount.getOrDefault(sessionId, 0));
            stats.put("hasHistory", history != null && !history.isEmpty());
        }
        return stats;
    }

    // Extract move from text using regex
    private String extractMoveFromText(String text) {
        if (text == null || text.isBlank()) return null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("[a-i][0-9][a-i][0-9]");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    // Inner result class
    public static class DeepseekCotResult {
        private final boolean success;
        private final String reasoning;
        private final String answer;
        private final String move;
        private final String error;

        private DeepseekCotResult(boolean success, String reasoning, String answer, String move, String error) {
            this.success = success;
            this.reasoning = reasoning;
            this.answer = answer;
            this.move = move;
            this.error = error;
        }

        public static DeepseekCotResult success(String reasoning, String answer, String move) {
            return new DeepseekCotResult(true, reasoning, answer, move, null);
        }

        public static DeepseekCotResult error(String error) {
            return new DeepseekCotResult(false, null, null, null, error);
        }

        public boolean isSuccess() { return success; }
        public String getReasoning() { return reasoning; }
        public String getAnswer() { return answer; }
        public String getMove() { return move; }
        public String getError() { return error; }
    }
}