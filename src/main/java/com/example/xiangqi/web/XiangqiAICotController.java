// XiangqiAICotController.java
package com.example.xiangqi.web;

import com.example.xiangqi.llm.DeepseekCotClient;
import com.example.xiangqi.llm.OpenAICotClient;
import com.example.xiangqi.llm.GeminiCotClient;
import com.example.xiangqi.llm.DeepseekCotClient.DeepseekCotResult;
import com.example.xiangqi.llm.OpenAICotClient.OpenAICotResult;
import com.example.xiangqi.llm.GeminiCotClient.GeminiCotResult;
import com.example.xiangqi.llm.ErrorCsvRecorder;
import com.example.xiangqi.engine.EngineService;
import com.example.xiangqi.game.XqRules.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/xiangqi-cot")
@CrossOrigin(origins = "*")
public class XiangqiAICotController {

    @Autowired
    private DeepseekCotClient deepseekCotClient;

    @Autowired
    private OpenAICotClient openAICotClient;

    @Autowired
    private GeminiCotClient geminiCotClient;

    @Autowired
    private EngineService engineService;

    @Autowired
    private ErrorCsvRecorder errorCsvRecorder;

    private Board board = Board.initial();
    private Side turn = Side.RED;
    private final List<String> moveHistory = new ArrayList<>();
    private boolean gameOver = false;
    private String winner = null;
    private String gameResult = null;
    private int currentRound = 0;
    private final List<Map<String, Object>> foulRecords = new ArrayList<>();
    private String currentGameId = null;

    private String currentAiType = "DeepSeek CoT";

    private final List<ErrorCsvRecorder.ErrorRecord> currentGameErrors = new ArrayList<>();

    @PostMapping("/ai-battle/new")
    public ResponseEntity<Map<String, Object>> startNewAIBattle(
            @RequestParam(value = "aiType", defaultValue = "DeepSeek CoT") String aiType) {

        if (!"DeepSeek CoT".equalsIgnoreCase(aiType) &&
                !"OpenAI CoT".equalsIgnoreCase(aiType) &&
                !"Gemini CoT".equalsIgnoreCase(aiType)) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "Unsupported AI type. Please choose: DeepSeek CoT, OpenAI CoT or Gemini CoT");
            return ResponseEntity.badRequest().body(response);
        }

        this.currentAiType = aiType;
        cancelCurrentGame();
        resetGame();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "AI battle started - " + aiType + "(Red) vs Pikafish(Black)");
        response.put("gameId", currentGameId);
        response.put("aiType", aiType);

        System.out.println("[CoT] Starting new AI battle: " + aiType + "(Red) vs Pikafish(Black), gameId=" + currentGameId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/ai-battle/next-round")
    public ResponseEntity<Map<String, Object>> playNextRound() {
        Map<String, Object> response = new HashMap<>();

        if (gameOver) {
            response.put("success", false);
            response.put("error", "Game already ended");
            response.put("gameResult", gameResult);
            return ResponseEntity.ok(response);
        }

        currentRound++;
        System.out.println("[CoT] Round " + currentRound + " started (AI type: " + currentAiType + ")");

        Map<String, Object> aiResult = playAICotMove();
        if (!(Boolean) aiResult.get("success")) {
            gameOver = true;
            gameResult = "Pikafish wins - " + currentAiType + " cannot continue";
            winner = "BLACK";

            recordFoul(currentRound, currentAiType + " cannot continue", currentAiType);
            recordGameResult(currentAiType + " cannot continue");
            saveAICotErrorToFile();

            response.put("success", false);
            response.put("gameOver", true);
            response.put("winner", winner);
            response.put("gameResult", gameResult);
            response.put("foul", true);
            response.put("foulPlayer", currentAiType);
            response.put("foulReason", aiResult.get("errorMessage"));
            response.put("boardState", getCurrentBoardState());
            response.put("reasoning", aiResult.get("reasoning"));
            response.put("aiAnswer", aiResult.get("rawAnswer"));
            response.put("aiType", currentAiType);
            return ResponseEntity.ok(response);
        }

        String aiMove = (String) aiResult.get("move");
        System.out.println("[CoT] " + currentAiType + " move: " + aiMove);

        if (isCheckmatedStrict(Side.BLACK)) {
            gameOver = true;
            gameResult = currentAiType + " wins - checkmate";
            winner = "RED";

            recordGameResult(currentAiType + " wins - checkmate Pikafish");
            saveAICotErrorToFile();

            response.put("success", true);
            response.put("gameOver", true);
            response.put("winner", winner);
            response.put("gameResult", gameResult);
            response.put("aiMove", aiMove);
            response.put("aiType", currentAiType);
            response.put("round", currentRound);
            response.put("boardState", getCurrentBoardState());
            return ResponseEntity.ok(response);
        }

        Map<String, Object> pikafishResult = playPikafishMove();
        if (!(Boolean) pikafishResult.get("success")) {
            gameOver = true;
            gameResult = currentAiType + " wins - Pikafish cannot continue";
            winner = "RED";

            recordFoul(currentRound, "Pikafish cannot continue", "Pikafish");
            recordGameResult("Pikafish cannot continue");
            saveAICotErrorToFile();

            response.put("success", false);
            response.put("gameOver", true);
            response.put("winner", winner);
            response.put("gameResult", gameResult);
            response.put("foul", true);
            response.put("foulPlayer", "Pikafish");
            response.put("foulReason", pikafishResult.get("errorMessage"));
            response.put("boardState", getCurrentBoardState());
            response.put("aiMove", aiMove);
            response.put("aiType", currentAiType);
            return ResponseEntity.ok(response);
        }

        String pikafishMove = (String) pikafishResult.get("move");
        System.out.println("[CoT] Pikafish move: " + pikafishMove);

        if (isCheckmatedStrict(Side.RED)) {
            gameOver = true;
            gameResult = "Pikafish wins - checkmate";
            winner = "BLACK";

            recordGameResult(currentAiType + " checkmated");
            saveAICotErrorToFile();

            response.put("success", true);
            response.put("gameOver", true);
            response.put("winner", winner);
            response.put("gameResult", gameResult);
            response.put("aiMove", aiMove);
            response.put("pikafishMove", pikafishMove);
            response.put("aiType", currentAiType);
            response.put("round", currentRound);
            response.put("boardState", getCurrentBoardState());
            return ResponseEntity.ok(response);
        }

        if (isStalemated(Side.RED)) {
            gameOver = true;
            gameResult = "Pikafish wins - stalemate";
            winner = "BLACK";

            recordGameResult(currentAiType + " stalemated");
            saveAICotErrorToFile();

            response.put("success", true);
            response.put("gameOver", true);
            response.put("winner", winner);
            response.put("gameResult", gameResult);
            response.put("aiMove", aiMove);
            response.put("pikafishMove", pikafishMove);
            response.put("aiType", currentAiType);
            response.put("round", currentRound);
            response.put("boardState", getCurrentBoardState());
            return ResponseEntity.ok(response);
        }

        response.put("success", true);
        response.put("gameOver", false);
        response.put("winner", null);
        response.put("gameResult", null);
        response.put("aiMove", aiMove);
        response.put("pikafishMove", pikafishMove);
        response.put("round", currentRound);
        response.put("aiType", currentAiType);
        response.put("boardState", getCurrentBoardState());
        response.put("reasoning", aiResult.get("reasoning"));
        response.put("aiAnswer", aiResult.get("rawAnswer"));

        return ResponseEntity.ok(response);
    }

    private Map<String, Object> playAICotMove() {
        switch (currentAiType) {
            case "OpenAI CoT": return playOpenAICotMove();
            case "Gemini CoT": return playGeminiCotMove();
            default: return playDeepSeekCotMove();
        }
    }

    private Map<String, Object> playDeepSeekCotMove() {
        Map<String, Object> result = new HashMap<>();
        boolean hasFoul = false;
        String foulReason = null;

        if (isCheckmatedStrict(Side.RED)) {
            System.out.println("[DeepSeek CoT] Red is checkmated, cannot move");
            result.put("success", false);
            result.put("errorMessage", "Red is checkmated, no legal moves");
            return result;
        }

        List<String> legalMoves = getAllLegalMoves(Side.RED);
        if (legalMoves.isEmpty()) {
            System.out.println("[DeepSeek CoT] Red is stalemated, cannot move");
            result.put("success", false);
            result.put("errorMessage", "Red is stalemated, no legal moves");
            return result;
        }

        System.out.println("[DeepSeek CoT] Analyzing... AI autonomous move generation");

        try {
            String boardDescription = generateEnhancedBoardDescription(currentRound == 1);
            System.out.println("[CoT Controller] Round " + currentRound + " calling DeepSeek CoT");

            DeepseekCotResult cotResult = deepseekCotClient.chatStructured(
                    currentGameId, boardDescription, currentRound, "RED");

            if (cotResult == null || !cotResult.isSuccess()) {
                System.err.println("DeepSeek CoT call failed");
                hasFoul = true;
                foulReason = cotResult == null ? "DeepSeek CoT call failed" : cotResult.getError();
            } else {
                String aiMove = extractMoveFromAnswer(cotResult.getAnswer());
                if (aiMove != null && isValidMoveFormat(aiMove)) {
                    if (legalMoves.contains(aiMove)) {
                        boolean moveApplied = applyAIMove(aiMove, Side.RED);
                        if (moveApplied) {
                            System.out.println("[DeepSeek CoT] Succeeded: " + aiMove);
                            result.put("success", true);
                            result.put("move", aiMove);
                            result.put("reasoning", cotResult.getReasoning());
                            result.put("rawAnswer", cotResult.getAnswer());
                            if (hasFoul) {
                                result.put("foul", true);
                                result.put("foulReason", foulReason);
                            }
                            return result;
                        }
                    } else {
                        System.out.println("[DeepSeek CoT] Move not in legal list: " + aiMove);
                        hasFoul = true;
                        foulReason = "Illegal move (not in legal list): " + aiMove;
                        recordFoul(currentRound, "Illegal move: " + aiMove, "DeepSeek CoT");
                        recordError("Illegal move: " + aiMove);
                    }
                } else {
                    System.out.println("[DeepSeek CoT] Invalid move format: " + aiMove);
                    hasFoul = true;
                    foulReason = "Invalid move format: " + aiMove;
                    recordFoul(currentRound, "Invalid move format: " + aiMove, "DeepSeek CoT");
                    recordError("Invalid move format: " + aiMove);
                }
            }
        } catch (Exception e) {
            System.err.println("[DeepSeek CoT] Exception: " + e.getMessage());
            hasFoul = true;
            foulReason = "DeepSeek CoT exception: " + e.getMessage();
        }

        System.out.println("[DeepSeek CoT] AI call failed, trying random move");
        String randomMove = getRandomMoveFromList(legalMoves);
        if (randomMove != null) {
            boolean moveApplied = applyAIMove(randomMove, Side.RED);
            if (moveApplied) {
                System.out.println("[DeepSeek CoT] Random move succeeded: " + randomMove);
                result.put("success", true);
                result.put("move", randomMove);
                result.put("randomFallback", true);
                result.put("reasoning", "DeepSeek CoT failed, using random move");
                result.put("rawAnswer", "Random move: " + randomMove);
                if (hasFoul) {
                    result.put("foul", true);
                    result.put("foulReason", foulReason);
                }
                return result;
            }
        }

        result.put("success", false);
        result.put("errorMessage", hasFoul ? foulReason : "DeepSeek CoT cannot provide a valid move");
        return result;
    }

    private Map<String, Object> playOpenAICotMove() {
        Map<String, Object> result = new HashMap<>();
        boolean hasFoul = false;
        String foulReason = null;

        if (isCheckmatedStrict(Side.RED)) {
            System.out.println("[OpenAI CoT] Red is checkmated, cannot move");
            result.put("success", false);
            result.put("errorMessage", "Red is checkmated, no legal moves");
            return result;
        }

        List<String> legalMoves = getAllLegalMoves(Side.RED);
        if (legalMoves.isEmpty()) {
            System.out.println("[OpenAI CoT] Red is stalemated, cannot move");
            result.put("success", false);
            result.put("errorMessage", "Red is stalemated, no legal moves");
            return result;
        }

        System.out.println("[OpenAI CoT] Analyzing... AI autonomous move generation");

        try {
            String boardDescription = generateEnhancedBoardDescription(currentRound == 1);
            System.out.println("[CoT Controller] Round " + currentRound + " calling OpenAI CoT");

            OpenAICotResult cotResult = openAICotClient.chatStructured(
                    currentGameId, boardDescription, currentRound, "RED");

            if (cotResult == null || !cotResult.isSuccess()) {
                System.err.println("OpenAI CoT call failed");
                hasFoul = true;
                foulReason = cotResult == null ? "OpenAI CoT call failed" : cotResult.getError();
            } else {
                String aiMove = cotResult.getMove();
                if (aiMove == null || aiMove.isEmpty()) {
                    aiMove = extractMoveFromAnswer(cotResult.getAnswerText());
                }

                if (aiMove != null && isValidMoveFormat(aiMove)) {
                    if (legalMoves.contains(aiMove)) {
                        boolean moveApplied = applyAIMove(aiMove, Side.RED);
                        if (moveApplied) {
                            System.out.println("[OpenAI CoT] Succeeded: " + aiMove);
                            result.put("success", true);
                            result.put("move", aiMove);
                            result.put("reasoning", cotResult.getReasoningSummary());
                            result.put("rawAnswer", cotResult.getAnswerText());
                            if (hasFoul) {
                                result.put("foul", true);
                                result.put("foulReason", foulReason);
                            }
                            return result;
                        }
                    } else {
                        System.out.println("[OpenAI CoT] Move not in legal list: " + aiMove);
                        hasFoul = true;
                        foulReason = "Illegal move (not in legal list): " + aiMove;
                        recordFoul(currentRound, "Illegal move: " + aiMove, "OpenAI CoT");
                        recordError("Illegal move: " + aiMove);
                    }
                } else {
                    System.out.println("[OpenAI CoT] Invalid move format: " + aiMove);
                    hasFoul = true;
                    foulReason = "Invalid move format: " + aiMove;
                    recordFoul(currentRound, "Invalid move format: " + aiMove, "OpenAI CoT");
                    recordError("Invalid move format: " + aiMove);
                }
            }
        } catch (Exception e) {
            System.err.println("[OpenAI CoT] Exception: " + e.getMessage());
            hasFoul = true;
            foulReason = "OpenAI CoT exception: " + e.getMessage();
        }

        System.out.println("[OpenAI CoT] AI call failed, trying random move");
        String randomMove = getRandomMoveFromList(legalMoves);
        if (randomMove != null) {
            boolean moveApplied = applyAIMove(randomMove, Side.RED);
            if (moveApplied) {
                System.out.println("[OpenAI CoT] Random move succeeded: " + randomMove);
                result.put("success", true);
                result.put("move", randomMove);
                result.put("randomFallback", true);
                result.put("reasoning", "OpenAI CoT failed, using random move");
                result.put("rawAnswer", "Random move: " + randomMove);
                if (hasFoul) {
                    result.put("foul", true);
                    result.put("foulReason", foulReason);
                }
                return result;
            }
        }

        result.put("success", false);
        result.put("errorMessage", hasFoul ? foulReason : "OpenAI CoT cannot provide a valid move");
        return result;
    }

    private Map<String, Object> playGeminiCotMove() {
        Map<String, Object> result = new HashMap<>();
        boolean hasFoul = false;
        String foulReason = null;

        if (isCheckmatedStrict(Side.RED)) {
            System.out.println("[Gemini CoT] Red is checkmated, cannot move");
            result.put("success", false);
            result.put("errorMessage", "Red is checkmated, no legal moves");
            return result;
        }

        List<String> legalMoves = getAllLegalMoves(Side.RED);
        if (legalMoves.isEmpty()) {
            System.out.println("[Gemini CoT] Red is stalemated, cannot move");
            result.put("success", false);
            result.put("errorMessage", "Red is stalemated, no legal moves");
            return result;
        }

        System.out.println("[Gemini CoT] Analyzing... AI autonomous move generation");

        try {
            String boardDescription = generateEnhancedBoardDescription(currentRound == 1);
            System.out.println("[CoT Controller] Round " + currentRound + " calling Gemini CoT");

            GeminiCotResult cotResult = geminiCotClient.chatStructured(
                    currentGameId, boardDescription, currentRound, "RED");

            if (cotResult == null || !cotResult.isSuccess()) {
                System.err.println("Gemini CoT call failed");
                hasFoul = true;
                foulReason = cotResult == null ? "Gemini CoT call failed" : cotResult.getError();
            } else {
                String aiMove = cotResult.getMove();
                if (aiMove == null || aiMove.isEmpty()) {
                    aiMove = extractMoveFromAnswer(cotResult.getAnswer());
                }

                if (aiMove != null && isValidMoveFormat(aiMove)) {
                    if (legalMoves.contains(aiMove)) {
                        boolean moveApplied = applyAIMove(aiMove, Side.RED);
                        if (moveApplied) {
                            System.out.println("[Gemini CoT] Succeeded: " + aiMove);
                            result.put("success", true);
                            result.put("move", aiMove);
                            result.put("reasoning", cotResult.getReasoning());
                            result.put("rawAnswer", cotResult.getAnswer());
                            if (hasFoul) {
                                result.put("foul", true);
                                result.put("foulReason", foulReason);
                            }
                            return result;
                        }
                    } else {
                        System.out.println("[Gemini CoT] Move not in legal list: " + aiMove);
                        hasFoul = true;
                        foulReason = "Illegal move (not in legal list): " + aiMove;
                        recordFoul(currentRound, "Illegal move: " + aiMove, "Gemini CoT");
                        recordError("Illegal move: " + aiMove);
                    }
                } else {
                    System.out.println("[Gemini CoT] Invalid move format: " + aiMove);
                    hasFoul = true;
                    foulReason = "Invalid move format: " + aiMove;
                    recordFoul(currentRound, "Invalid move format: " + aiMove, "Gemini CoT");
                    recordError("Invalid move format: " + aiMove);
                }
            }
        } catch (Exception e) {
            System.err.println("[Gemini CoT] Exception: " + e.getMessage());
            hasFoul = true;
            foulReason = "Gemini CoT exception: " + e.getMessage();
        }

        System.out.println("[Gemini CoT] AI call failed, trying random move");
        String randomMove = getRandomMoveFromList(legalMoves);
        if (randomMove != null) {
            boolean moveApplied = applyAIMove(randomMove, Side.RED);
            if (moveApplied) {
                System.out.println("[Gemini CoT] Random move succeeded: " + randomMove);
                result.put("success", true);
                result.put("move", randomMove);
                result.put("randomFallback", true);
                result.put("reasoning", "Gemini CoT failed, using random move");
                result.put("rawAnswer", "Random move: " + randomMove);
                if (hasFoul) {
                    result.put("foul", true);
                    result.put("foulReason", foulReason);
                }
                return result;
            }
        }

        result.put("success", false);
        result.put("errorMessage", hasFoul ? foulReason : "Gemini CoT cannot provide a valid move");
        return result;
    }

    private Map<String, Object> playPikafishMove() {
        Map<String, Object> result = new HashMap<>();
        boolean hasFoul = false;
        String foulReason = null;

        try {
            if (isCheckmatedStrict(Side.BLACK)) {
                System.out.println("[CoT] Pikafish is checkmated, cannot move");
                result.put("success", false);
                result.put("errorMessage", "Black is checkmated, no legal moves");
                return result;
            }

            List<String> legalMoves = getAllLegalMoves(Side.BLACK);
            if (legalMoves.isEmpty()) {
                System.out.println("[CoT] Pikafish is stalemated, cannot move");
                result.put("success", false);
                result.put("errorMessage", "Black is stalemated, no legal moves");
                return result;
            }

            String bestMoveUci = engineService.bestMove(null, moveHistory, false);

            if (bestMoveUci != null && !bestMoveUci.isEmpty()) {
                if (legalMoves.contains(bestMoveUci)) {
                    boolean moveApplied = applyAIMove(bestMoveUci, Side.BLACK);
                    if (moveApplied) {
                        System.out.println("[Pikafish] Move: " + bestMoveUci);
                        result.put("success", true);
                        result.put("move", bestMoveUci);
                        return result;
                    }
                } else {
                    System.out.println("[Pikafish] Illegal move: " + bestMoveUci);
                    recordFoul(currentRound, "Illegal move: " + bestMoveUci, "Pikafish");
                    hasFoul = true;
                    foulReason = "Illegal move: " + bestMoveUci;
                }
            }

            String randomMove = getRandomMoveFromList(legalMoves);
            System.out.println("[Pikafish] Using random move: " + randomMove);
            boolean moveApplied = applyAIMove(randomMove, Side.BLACK);
            if (moveApplied) {
                result.put("success", true);
                result.put("move", randomMove);
                result.put("randomFallback", true);
                if (hasFoul) {
                    result.put("foul", true);
                    result.put("foulReason", foulReason);
                }
                return result;
            }

            result.put("success", false);
            result.put("errorMessage", "Pikafish cannot provide a valid move");

        } catch (Exception e) {
            System.err.println("[Pikafish] Move error: " + e.getMessage());
            result.put("success", false);
            result.put("errorMessage", "Pikafish error: " + e.getMessage());
        }

        return result;
    }

    private List<String> getAllLegalMoves(Side side) {
        List<String> legalMoves = new ArrayList<>();
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null && piece.side == side) {
                    List<Move> moves = board.legalMovesAt(new Pos(r, c));
                    for (Move move : moves) {
                        String uciMove = coordToUci(move);
                        legalMoves.add(uciMove);
                    }
                }
            }
        }
        return legalMoves;
    }

    private boolean applyAIMove(String uciMove, Side side) {
        try {
            Move move = parseUciMove(uciMove);
            if (move == null) return false;

            Piece fromPiece = board.at(move.from.r, move.from.c);
            if (fromPiece == null || fromPiece.side != side) return false;

            List<Move> legalMoves = board.legalMovesAt(move.from);
            boolean isLegal = legalMoves.stream().anyMatch(m -> m.to.equals(move.to));
            if (!isLegal) return false;

            board = board.makeMove(move);
            turn = turn.opponent();
            moveHistory.add(uciMove);
            return true;

        } catch (Exception e) {
            return false;
        }
    }

    private String coordToUci(Move m) {
        return "" + (char)('a' + m.from.c) + (9 - m.from.r) +
                (char)('a' + m.to.c) + (9 - m.to.r);
    }

    private Move parseUciMove(String uci) {
        if (uci == null || uci.length() < 4) return null;
        try {
            int fromC = uci.charAt(0) - 'a';
            int fromR = 9 - (uci.charAt(1) - '0');
            int toC = uci.charAt(2) - 'a';
            int toR = 9 - (uci.charAt(3) - '0');
            return new Move(new Pos(fromR, fromC), new Pos(toR, toC));
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isValidMoveFormat(String move) {
        return move != null && move.matches("[a-i][0-9][a-i][0-9]");
    }

    private String getRandomMoveFromList(List<String> legalMoves) {
        if (legalMoves == null || legalMoves.isEmpty()) return null;
        Random random = new Random();
        return legalMoves.get(random.nextInt(legalMoves.size()));
    }

    /**
     * Generate enhanced board description (shows full move history, up to 20 rounds).
     */
    private String generateEnhancedBoardDescription(boolean includeRules) {
        StringBuilder description = new StringBuilder();

        if (currentRound == 1) {
            description.append("中国象棋棋盘状态（AI vs 皮卡鱼对战）：\n");
            description.append("棋盘坐标系统：\n");
            description.append("列：从左到右为 a,b,c,d,e,f,g,h,i\n");
            description.append("行：从下到上为 0,1,2,3,4,5,6,7,8,9\n");
            description.append("红方在棋盘下方（行0-4），黑方在上方（行5-9）\n\n");

            description.append("棋子表示：\n");
            description.append("红方（大写）：车(R)、马(H)、相(E)、士(A)、帅(K)、炮(C)、兵(P)\n");
            description.append("黑方（小写）：车(r)、马(h)、相(e)、士(a)、将(k)、炮(c)、卒(p)\n\n");

            description.append("重要提示：你是红方，使用大写棋子！\n");
            description.append("你只能移动红方棋子（大写字母）。\n");
            description.append("红方向黑方前进是行数增加（0→1→2...→9）。\n\n");

            description.append("坐标示例：\n");
            description.append("- 红方右边马从b0到c2：b0c2\n");
            description.append("- 红方左边炮从b2到e2：b2e2\n");
            description.append("- 红方三路兵前进：e3e4\n\n");
        }

        description.append("当前棋盘布局（红方在下方，黑方在上方）：\n");
        description.append(getBoardVisualizationWithColorHint());

        if (!moveHistory.isEmpty()) {
            description.append("\n历史移动（从第1回合开始，最多显示最近20回合）：\n");
            int totalMoves = moveHistory.size();
            int maxStepsToShow = 20 * 2;
            int startIdx = 0;
            if (totalMoves > maxStepsToShow) {
                startIdx = totalMoves - maxStepsToShow;
                description.append("...（省略前").append(startIdx / 2).append("回合）\n");
            }
            for (int i = startIdx; i < totalMoves; i++) {
                int roundNumber = i / 2 + 1;
                String player = (i % 2 == 0) ? "红方(AI)" : "黑方(皮卡鱼)";
                description.append("第").append(roundNumber).append("回合 [").append(player).append("]: ").append(moveHistory.get(i)).append("\n");
            }
        }

        description.append("\n回合: ").append(currentRound).append("\n");
        description.append("请进行思维链推理，分析当前局面，然后给出最佳走法：");
        description.append("\n\n推理格式要求：");
        description.append("\nExplanation: [简要解释你的选择理由，分析局面]");
        description.append("\nFinal answer: [4字符坐标，例如：b0c2]");

        return description.toString();
    }

    /**
     * Board visualization with color hints (Red at bottom, Black at top).
     */
    private String getBoardVisualizationWithColorHint() {
        StringBuilder visual = new StringBuilder();
        visual.append("    红方（你的棋子，大写字母）\n");
        visual.append("  a  b  c  d  e  f  g  h  i\n");

        for (int r = 9; r >= 0; r--) {
            visual.append(r).append(" ");
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece == null) {
                    visual.append(" . ");
                } else {
                    String pieceChar = getPieceChar(piece);
                    if (piece.side == Side.RED) {
                        visual.append("[R]");
                    } else {
                        visual.append("[").append(pieceChar).append("]");
                    }
                }
                visual.append(" ");
            }
            visual.append(r);

            if (r == 0) visual.append("   ← 红方底线（你的底线）");
            else if (r == 4) visual.append("   ← 楚河");
            else if (r == 5) visual.append("   ← 汉界");
            else if (r == 9) visual.append("   ← 黑方底线");

            visual.append("\n");
        }
        visual.append("  a  b  c  d  e  f  g  h  i\n");
        visual.append("    黑方（对手棋子，小写字母）\n\n");

        if (currentRound == 1) {
            visual.append("图例说明：\n");
            visual.append("[R] - 你的棋子（红方，大写字母）\n");
            visual.append("[x] - 对手棋子（黑方，小写字母）\n");
            visual.append(" .  - 空位置\n\n");

            visual.append("红方棋子位置参考：\n");
            visual.append("帅(K): e0  士(A): d0,f0  相(E): c0,g0\n");
            visual.append("马(H): b0,h0  车(R): a0,i0  炮(C): b2,h2  兵(P): a3,c3,e3,g3,i3\n");
        }

        return visual.toString();
    }

    private String getPieceChar(Piece piece) {
        if (piece.side == Side.RED) {
            switch (piece.type.toString().toUpperCase()) {
                case "ROOK": return "R";
                case "HORSE": return "H";
                case "ELEPHANT": return "E";
                case "ADVISOR": return "A";
                case "GENERAL": return "K";
                case "CANNON": return "C";
                case "PAWN": return "P";
                default: return "?";
            }
        } else {
            switch (piece.type.toString().toUpperCase()) {
                case "ROOK": return "r";
                case "HORSE": return "h";
                case "ELEPHANT": return "e";
                case "ADVISOR": return "a";
                case "KING": return "k";
                case "CANNON": return "c";
                case "PAWN": return "p";
                default: return "?";
            }
        }
    }

    private String getPieceChineseName(Piece piece) {
        switch (piece.type.toString().toUpperCase()) {
            case "ROOK": return "车";
            case "HORSE": return "马";
            case "ELEPHANT": return "相";
            case "ADVISOR": return "士";
            case "GENERAL": return "帅";
            case "KING": return "将";
            case "CANNON": return "炮";
            case "PAWN": return "兵";
            default: return piece.type.toString();
        }
    }

    private Map<String, Object> getCurrentBoardState() {
        Map<String, Object> boardState = new HashMap<>();
        List<Map<String, Object>> pieces = new ArrayList<>();

        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null) {
                    Map<String, Object> pieceInfo = new HashMap<>();
                    pieceInfo.put("row", r);
                    pieceInfo.put("col", c);
                    pieceInfo.put("side", piece.side == Side.RED ? "RED" : "BLACK");
                    pieceInfo.put("type", piece.type.toString());
                    pieces.add(pieceInfo);
                }
            }
        }

        boardState.put("pieces", pieces);
        boardState.put("currentPlayer", turn == Side.RED ? "RED" : "BLACK");
        return boardState;
    }

    private String extractMoveFromAnswer(String reply) {
        if (reply == null) return null;
        String text = reply.trim();

        int finalAnswerIndex = text.indexOf("Final answer:");
        if (finalAnswerIndex != -1) {
            String afterFinalAnswer = text.substring(finalAnswerIndex + "Final answer:".length()).trim();
            String[] tokens = afterFinalAnswer.split("\\s+");
            for (String t : tokens) {
                String candidate = t.trim();
                if (isValidMoveFormat(candidate)) {
                    return candidate;
                }
            }
        }

        String[] tokens = text.split("\\s+");
        for (String t : tokens) {
            String candidate = t.trim();
            if (isValidMoveFormat(candidate)) {
                return candidate;
            }
        }

        for (int i = 0; i + 3 < text.length(); i++) {
            String sub = text.substring(i, i + 4);
            if (isValidMoveFormat(sub)) {
                return sub;
            }
        }
        return null;
    }

    private boolean isCheckmatedStrict(Side side) {
        if (!board.inCheck(side)) return false;
        boolean hasEscapeMoves = hasLegalMovesThatEscapeCheck(side);
        return !hasEscapeMoves;
    }

    private boolean hasLegalMovesThatEscapeCheck(Side side) {
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null && piece.side == side) {
                    List<Move> moves = board.legalMovesAt(new Pos(r, c));
                    for (Move move : moves) {
                        Board newBoard = board.makeMove(move);
                        if (!newBoard.inCheck(side)) return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isStalemated(Side side) {
        if (board.inCheck(side)) return false;
        return !hasLegalMoves(side);
    }

    private boolean hasLegalMoves(Side side) {
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null && piece.side == side) {
                    List<Move> legalMoves = board.legalMovesAt(new Pos(r, c));
                    if (!legalMoves.isEmpty()) return true;
                }
            }
        }
        return false;
    }

    private void recordFoul(int round, String type, String player) {
        Map<String, Object> foulRecord = new HashMap<>();
        foulRecord.put("round", round);
        foulRecord.put("type", type);
        foulRecord.put("player", player);
        foulRecord.put("timestamp", System.currentTimeMillis());
        foulRecords.add(foulRecord);
        System.out.println("[CoT] Recording error - round " + round + " [" + player + "]: " + type);
    }

    private void recordError(String errorType) {
        ErrorCsvRecorder.ErrorRecord record = ErrorCsvRecorder.recordError(currentRound, errorType);
        currentGameErrors.add(record);
        System.out.println("[CoT] Recording error - round " + currentRound + ": " + record.getErrorType());
    }

    private void recordGameResult(String result) {
        ErrorCsvRecorder.ErrorRecord record = ErrorCsvRecorder.recordGameResult(currentRound, result);
        currentGameErrors.add(record);
        System.out.println("[CoT] Recording game result: " + record.getErrorType());
    }

    private void saveAICotErrorToFile() {
        ErrorCsvRecorder.RecordType recordType;
        switch (currentAiType) {
            case "OpenAI CoT": recordType = ErrorCsvRecorder.RecordType.OPENAI_COT; break;
            case "Gemini CoT": recordType = ErrorCsvRecorder.RecordType.GEMINI_COT; break;
            default: recordType = ErrorCsvRecorder.RecordType.DEEPSEEK_COT; break;
        }
        errorCsvRecorder.saveErrorsToCsv(currentGameErrors, recordType);
    }

    @GetMapping("/ai-battle/fouls")
    public ResponseEntity<Map<String, Object>> getFoulRecords() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("fouls", foulRecords);
        response.put("currentAiType", currentAiType);
        response.put("gameId", currentGameId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/ai-battle/status")
    public ResponseEntity<Map<String, Object>> getGameStatus() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("gameId", currentGameId);
        response.put("aiType", currentAiType);
        response.put("gameOver", gameOver);
        response.put("winner", winner);
        response.put("gameResult", gameResult);
        response.put("round", currentRound);
        response.put("boardState", getCurrentBoardState());
        response.put("moveHistory", moveHistory);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/ai-battle/reset")
    public ResponseEntity<Map<String, Object>> resetGameManually() {
        cancelCurrentGame();
        resetGame();
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Game reset");
        response.put("gameId", currentGameId);
        response.put("aiType", currentAiType);
        return ResponseEntity.ok(response);
    }

    private void resetGame() {
        board = Board.initial();
        turn = Side.RED;
        moveHistory.clear();
        gameOver = false;
        winner = null;
        gameResult = null;
        currentRound = 0;
        foulRecords.clear();
        currentGameErrors.clear();
        currentGameId = UUID.randomUUID().toString();

        deepseekCotClient.clearSessionHistory(currentGameId);
        openAICotClient.clearSessionHistory(currentGameId);
        geminiCotClient.clearSessionHistory(currentGameId);

        System.out.println("[CoT] AI battle game state reset, gameId=" + currentGameId + ", AI type=" + currentAiType);
    }

    private void cancelCurrentGame() {
        try {
            switch (currentAiType) {
                case "OpenAI CoT": System.out.println("Attempted to cancel current OpenAI CoT API call"); break;
                case "Gemini CoT": System.out.println("Attempted to cancel current Gemini CoT API call"); break;
                default: System.out.println("Attempted to cancel current DeepSeek CoT API call"); break;
            }
        } catch (Exception e) {
            System.err.println("[CoT] Error cancelling game: " + e.getMessage());
        }
    }
}