// IndividualCotController.java
package com.example.xiangqi.web;

import com.example.xiangqi.game.XqIndividualJuge;
import com.example.xiangqi.llm.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.util.*;

@Controller
@RequestMapping("/individual-cot")
public class IndividualCotController {

    private XqIndividualJuge.Board board = XqIndividualJuge.Board.initial();
    private XqIndividualJuge.Side turn = XqIndividualJuge.Side.RED;
    private boolean gameOver = false;
    private String winner = null;
    private String gameResult = null;
    private final List<String[]> redFoulRecords = new ArrayList<>();
    private final List<String[]> blackFoulRecords = new ArrayList<>();

    private final Map<String, Integer> positionCounts = new HashMap<>();
    private String lastRepeatedPosition = null;
    private boolean isRepeatedMove = false;

    private final DeepseekCotClient deepseekCotClient;
    private final GeminiCotClient geminiCotClient;
    private final OpenAICotClient openAICotClient;
    private final BoardStateDescriber boardStateDescriber;
    private final AIBoardDescriber aiBoardDescriber;

    private boolean aiBattleMode = false;
    private boolean aiThinking = false;
    private int moveCount = 0;
    private final List<String> moveHistory = new ArrayList<>();

    private String redAIModel = "DeepSeekCoT";
    private String blackAIModel = "GeminiCoT";
    private String effectiveRedModel = "DeepSeekCoT";
    private String effectiveBlackModel = "GeminiCoT";

    private String lastAIMove = null;
    private boolean hasNewRound = false;

    private final Map<String, Integer> aiSuccessCount = new HashMap<>();
    private final Map<String, Integer> aiErrorCount = new HashMap<>();
    private final Map<String, List<Long>> aiResponseTimes = new HashMap<>();

    private int redCsvCounter = 0;
    private int blackCsvCounter = 0;

    private int redAvgTimeCsvCounter = 0;
    private int blackAvgTimeCsvCounter = 0;

    private static final int MAX_ROUNDS = 60;
    private boolean maxRoundsReached = false;

    public IndividualCotController(DeepseekCotClient deepseekCotClient,
                                   GeminiCotClient geminiCotClient,
                                   OpenAICotClient openAICotClient,
                                   BoardStateDescriber boardStateDescriber,
                                   AIBoardDescriber aiBoardDescriber) {
        this.deepseekCotClient = deepseekCotClient;
        this.geminiCotClient = geminiCotClient;
        this.openAICotClient = openAICotClient;
        this.boardStateDescriber = boardStateDescriber;
        this.aiBoardDescriber = aiBoardDescriber;

        this.redCsvCounter = readCounterFile("Red");
        this.blackCsvCounter = readCounterFile("Black");
        this.redAvgTimeCsvCounter = readAvgTimeCounterFile("Red");
        this.blackAvgTimeCsvCounter = readAvgTimeCounterFile("Black");
    }

    @GetMapping
    public String individualCot() {
        return "individual-cot";
    }

    @GetMapping("/black/best")
    @ResponseBody
    public List<Map<String, String>> getBlackBestMoves() {
        return readCSV("CSV_Individual_Black/black_best.csv");
    }

    @GetMapping("/black/foul")
    @ResponseBody
    public List<Map<String, String>> getBlackFoulMoves() {
        return readCSV("CSV_Individual_Black/black_foul.csv");
    }

    @GetMapping("/red/best")
    @ResponseBody
    public List<Map<String, String>> getRedBestMoves() {
        return readCSV("CSV_Individual_Red/red_best.csv");
    }

    @GetMapping("/red/foul")
    @ResponseBody
    public List<Map<String, String>> getRedFoulMoves() {
        return readCSV("CSV_Individual_Red/red_foul.csv");
    }

    @PostMapping("/new")
    @ResponseBody
    public Map<String, Object> newGame() {
        board = XqIndividualJuge.Board.initial();
        turn = XqIndividualJuge.Side.RED;
        gameOver = false;
        winner = null;
        gameResult = null;
        redFoulRecords.clear();
        blackFoulRecords.clear();
        positionCounts.clear();
        lastRepeatedPosition = null;
        isRepeatedMove = false;
        aiBattleMode = false;
        aiThinking = false;
        moveCount = 0;
        moveHistory.clear();
        lastAIMove = null;
        hasNewRound = false;
        maxRoundsReached = false;

        aiSuccessCount.clear();
        aiErrorCount.clear();
        aiResponseTimes.clear();

        redCsvCounter = readCounterFile("Red");
        blackCsvCounter = readCounterFile("Black");
        redAvgTimeCsvCounter = readAvgTimeCounterFile("Red");
        blackAvgTimeCsvCounter = readAvgTimeCounterFile("Black");

        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "new_game");
        resp.put("turn", turn.toString());
        resp.put("gameOver", gameOver);
        resp.put("aiBattleMode", aiBattleMode);
        resp.put("redAIModel", effectiveRedModel);
        resp.put("blackAIModel", effectiveBlackModel);
        resp.put("maxRoundsReached", maxRoundsReached);
        resp.put("message", "New game started, manual mode enabled");

        return resp;
    }

    @PostMapping("/ai/new")
    @ResponseBody
    public Map<String, Object> newAIGame() {
        board = XqIndividualJuge.Board.initial();
        turn = XqIndividualJuge.Side.RED;
        gameOver = false;
        winner = null;
        gameResult = null;
        redFoulRecords.clear();
        blackFoulRecords.clear();
        positionCounts.clear();
        lastRepeatedPosition = null;
        isRepeatedMove = false;
        aiBattleMode = false;
        aiThinking = false;
        moveCount = 0;
        moveHistory.clear();
        lastAIMove = null;
        hasNewRound = false;
        maxRoundsReached = false;

        aiSuccessCount.clear();
        aiErrorCount.clear();
        aiResponseTimes.clear();

        redCsvCounter = readCounterFile("Red");
        blackCsvCounter = readCounterFile("Black");
        redAvgTimeCsvCounter = readAvgTimeCounterFile("Red");
        blackAvgTimeCsvCounter = readAvgTimeCounterFile("Black");

        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "new_ai_game_ready");
        resp.put("turn", turn.toString());
        resp.put("gameOver", gameOver);
        resp.put("aiBattleMode", aiBattleMode);
        resp.put("redAIModel", effectiveRedModel);
        resp.put("blackAIModel", effectiveBlackModel);
        resp.put("maxRoundsReached", maxRoundsReached);
        resp.put("message", "AI battle game initialized, waiting to start");

        return resp;
    }

    @PostMapping("/move")
    @ResponseBody
    public Map<String, Object> playerMove(@RequestBody Map<String, Integer> move) throws Exception {
        if (aiBattleMode) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("result", "ai_battle_mode");
            resp.put("message", "Currently in AI battle mode, manual moves are not allowed");
            resp.put("suggestion", "Please use the control buttons to manage AI battle");
            return resp;
        }

        Map<String, Object> resp = new HashMap<>();
        int fromR = move.get("fromR");
        int fromC = move.get("fromC");
        int toR = move.get("toR");
        int toC = move.get("toC");

        resp.put("result", "ok");
        resp.put("move", "e2e4");
        resp.put("turn", turn == XqIndividualJuge.Side.RED ? "RED" : "BLACK");
        resp.put("player", turn == XqIndividualJuge.Side.RED ? "red" : "black");

        return resp;
    }

    @PostMapping("/ai/set-models")
    @ResponseBody
    public Map<String, Object> setAIModels(@RequestBody Map<String, String> modelConfig) {
        Map<String, Object> resp = new HashMap<>();

        if (aiBattleMode) {
            resp.put("success", false);
            resp.put("error", "AI battle already started, cannot change model configuration");
            return resp;
        }

        String redModel = modelConfig.get("redModel");
        String blackModel = modelConfig.get("blackModel");

        System.out.println("[setAIModels] Received model config: red=" + redModel + ", black=" + blackModel);

        if ("Default".equals(redModel)) {
            redModel = "DeepSeekCoT";
        }

        if ("Default".equals(blackModel)) {
            blackModel = "GeminiCoT";
        }

        System.out.println("[setAIModels] Processed config: red=" + redModel + ", black=" + blackModel);

        Set<String> validModels = Set.of("DeepSeekCoT", "GeminiCoT", "OpenAICoT");
        if (!validModels.contains(redModel)) {
            resp.put("success", false);
            resp.put("error", "Invalid red model name, must be 'DeepSeekCoT', 'GeminiCoT' or 'OpenAICoT'");
            return resp;
        }

        if (!validModels.contains(blackModel)) {
            resp.put("success", false);
            resp.put("error", "Invalid black model name, must be 'DeepSeekCoT', 'GeminiCoT' or 'OpenAICoT'");
            return resp;
        }

        if (redModel.equals(blackModel)) {
            resp.put("success", false);
            resp.put("error", "Red and black cannot use the same AI model");
            return resp;
        }

        this.effectiveRedModel = redModel;
        this.effectiveBlackModel = blackModel;

        System.out.println("[setAIModels] Config updated: effectiveRedModel=" + effectiveRedModel + ", effectiveBlackModel=" + effectiveBlackModel);

        resp.put("success", true);
        resp.put("redAIModel", effectiveRedModel);
        resp.put("blackAIModel", effectiveBlackModel);
        resp.put("message", "AI model config updated: " + effectiveRedModel + "(Red) vs " + effectiveBlackModel + "(Black)");

        return resp;
    }

    @PostMapping("/ai/start")
    @ResponseBody
    public Map<String, Object> startAIBattle() {
        Map<String, Object> resp = new HashMap<>();

        if (aiBattleMode) {
            resp.put("status", "already_started");
            resp.put("message", "AI battle already in progress");
            resp.put("redAIModel", effectiveRedModel);
            resp.put("blackAIModel", effectiveBlackModel);
            resp.put("maxRoundsReached", maxRoundsReached);
            return resp;
        }

        if (gameOver) {
            resp.put("status", "game_over");
            resp.put("message", "Game already ended, please start a new game");
            return resp;
        }

        aiBattleMode = true;
        aiThinking = false;
        hasNewRound = false;

        System.out.println("[startAIBattle] CoT AI battle started, current config: " + effectiveRedModel + "(Red) vs " + effectiveBlackModel + "(Black)");

        resp.put("status", "started");
        resp.put("message", "CoT AI battle started: " + effectiveRedModel + "(Red) vs " + effectiveBlackModel + "(Black)");
        resp.put("aiBattleMode", aiBattleMode);
        resp.put("redAIModel", effectiveRedModel);
        resp.put("blackAIModel", effectiveBlackModel);
        resp.put("maxRoundsReached", maxRoundsReached);
        return resp;
    }

    @GetMapping("/ai/models")
    @ResponseBody
    public Map<String, Object> getAIModels() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("redAIModel", effectiveRedModel);
        resp.put("blackAIModel", effectiveBlackModel);
        resp.put("aiBattleMode", aiBattleMode);
        resp.put("maxRoundsReached", maxRoundsReached);
        resp.put("maxRoundsLimit", MAX_ROUNDS);
        resp.put("currentRound", moveCount);
        resp.put("aiPerformance", getAIPerformanceStats());
        return resp;
    }

    private Map<String, Object> getAIPerformanceStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("successCount", aiSuccessCount);
        stats.put("errorCount", aiErrorCount);

        Map<String, Double> avgResponseTimes = new HashMap<>();
        for (Map.Entry<String, List<Long>> entry : aiResponseTimes.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                double avg = entry.getValue().stream().mapToLong(Long::longValue).average().orElse(0);
                avgResponseTimes.put(entry.getKey(), avg);
            }
        }
        stats.put("avgResponseTimes", avgResponseTimes);

        return stats;
    }

    private void recordAIPerformance(String model, boolean success, long responseTime) {
        String key = model + (success ? "_success" : "_error");
        aiSuccessCount.put(key, aiSuccessCount.getOrDefault(key, 0) + (success ? 1 : 0));
        aiErrorCount.put(key, aiErrorCount.getOrDefault(key, 0) + (success ? 0 : 1));

        aiResponseTimes.computeIfAbsent(model, k -> new ArrayList<>()).add(responseTime);
    }

    @PostMapping("/ai/next-round")
    @ResponseBody
    public Map<String, Object> getNextAIRound() {
        Map<String, Object> resp = new HashMap<>();

        if (!aiBattleMode) {
            resp.put("success", false);
            resp.put("error", "AI battle not started");
            return resp;
        }

        if (gameOver) {
            resp.put("success", false);
            resp.put("error", "Game already ended");
            resp.put("gameOver", true);
            resp.put("winner", winner);
            resp.put("gameResult", gameResult);
            resp.put("redFoulRecords", formatFoulRecords(redFoulRecords));
            resp.put("blackFoulRecords", formatFoulRecords(blackFoulRecords));
            return resp;
        }

        try {
            System.out.println("[CoT AI Round] Frontend requesting next AI round, current round: " + moveCount +
                    ", side: " + turn + ", model: " +
                    (turn == XqIndividualJuge.Side.RED ? effectiveRedModel : effectiveBlackModel));

            hasNewRound = false;

            if (moveCount >= MAX_ROUNDS) {
                System.out.println("Maximum rounds reached: " + MAX_ROUNDS);
                handleMaxRoundsReached();
                resp.put("success", false);
                resp.put("error", "Maximum rounds reached: " + MAX_ROUNDS);
                resp.put("gameOver", true);
                resp.put("maxRoundsReached", true);
                resp.put("winner", winner);
                resp.put("gameResult", gameResult);
                resp.put("redFoulRecords", formatFoulRecords(redFoulRecords));
                resp.put("blackFoulRecords", formatFoulRecords(blackFoulRecords));
                return resp;
            }

            int redFoulCountBefore = redFoulRecords.size();
            int blackFoulCountBefore = blackFoulRecords.size();

            long startTime = System.currentTimeMillis();
            makeAIMove();
            long responseTime = System.currentTimeMillis() - startTime;

            resp.put("success", true);

            if (lastAIMove != null) {
                resp.put("aiMove", lastAIMove);
                lastAIMove = null;
            }

            boolean hasNewFoul = (redFoulRecords.size() > redFoulCountBefore) ||
                    (blackFoulRecords.size() > blackFoulCountBefore);

            if (hasNewFoul) {
                resp.put("hasNewFoul", true);
                if (redFoulRecords.size() > redFoulCountBefore) {
                    List<String[]> newRedFouls = redFoulRecords.subList(
                            redFoulCountBefore, redFoulRecords.size());
                    resp.put("newRedFouls", formatFoulRecords(newRedFouls));
                }
                if (blackFoulRecords.size() > blackFoulCountBefore) {
                    List<String[]> newBlackFouls = blackFoulRecords.subList(
                            blackFoulCountBefore, blackFoulRecords.size());
                    resp.put("newBlackFouls", formatFoulRecords(newBlackFouls));
                }
            }

            if (moveCount >= MAX_ROUNDS) {
                System.out.println("Maximum rounds reached: " + MAX_ROUNDS);
                handleMaxRoundsReached();
                resp.put("gameOver", true);
                resp.put("maxRoundsReached", true);
                resp.put("winner", winner);
                resp.put("gameResult", gameResult);
                resp.put("redFoulRecords", formatFoulRecords(redFoulRecords));
                resp.put("blackFoulRecords", formatFoulRecords(blackFoulRecords));
            } else {
                XqIndividualJuge.GameResult gameState = checkGameState();
                if (gameState != XqIndividualJuge.GameResult.IN_PROGRESS) {
                    gameOver = true;
                    hasNewRound = false;

                    switch (gameState) {
                        case RED_WIN:
                            winner = "RED";
                            gameResult = effectiveRedModel + " (Red) wins";
                            break;
                        case BLACK_WIN:
                            winner = "BLACK";
                            gameResult = effectiveBlackModel + " (Black) wins";
                            break;
                        case DRAW:
                            winner = null;
                            gameResult = "Draw";
                            break;
                        default:
                            gameResult = "Game ended";
                    }

                    resp.put("gameOver", true);
                    resp.put("gameResult", gameResult);
                    resp.put("winner", winner);

                    exportGameResultToCSV(false, null);
                } else {
                    resp.put("gameOver", false);
                    resp.put("turn", turn.toString());
                }
            }

            resp.put("redFoulRecords", formatFoulRecords(redFoulRecords));
            resp.put("blackFoulRecords", formatFoulRecords(blackFoulRecords));
            resp.put("redAIModel", effectiveRedModel);
            resp.put("blackAIModel", effectiveBlackModel);
            resp.put("currentRound", moveCount);
            resp.put("maxRoundsLimit", MAX_ROUNDS);
            resp.put("maxRoundsReached", maxRoundsReached);

        } catch (Exception e) {
            System.err.println("Failed to get CoT AI round: " + e.getMessage());
            e.printStackTrace();
            resp.put("success", false);
            resp.put("error", "Failed to get CoT AI round: " + e.getMessage());
        }

        return resp;
    }

    private void handleMaxRoundsReached() {
        gameOver = true;
        maxRoundsReached = true;

        XqIndividualJuge.AdvantageResult advantage = XqIndividualJuge.evaluateBoardAdvantage(board);

        switch (advantage) {
            case RED_GREAT_ADVANTAGE:
                winner = "RED";
                gameResult = effectiveRedModel + " has great advantage";
                break;
            case RED_ADVANTAGE:
                winner = "RED";
                gameResult = effectiveRedModel + " has advantage";
                break;
            case BLACK_GREAT_ADVANTAGE:
                winner = "BLACK";
                gameResult = effectiveBlackModel + " has great advantage";
                break;
            case BLACK_ADVANTAGE:
                winner = "BLACK";
                gameResult = effectiveBlackModel + " has advantage";
                break;
            case EVEN:
                winner = null;
                gameResult = "Even";
                break;
        }

        System.out.println("Max rounds reached, evaluation result: " + advantage.getDescription());
        System.out.println("Final result: " + gameResult);

        exportGameResultToCSV(true, advantage);
    }

    private void makeAIMove() {
        if (gameOver || !aiBattleMode) {
            return;
        }

        try {
            List<String> legalMoves = getLegalMoves();

            if (legalMoves.isEmpty()) {
                handleNoLegalMoves();
                return;
            }

            String aiMove = null;
            String aiName = "";
            boolean isFoul = false;
            String foulReason = "";

            String currentAIModel;
            String playerSide;

            if (turn == XqIndividualJuge.Side.RED) {
                currentAIModel = effectiveRedModel;
                playerSide = "RED";
                aiName = effectiveRedModel + " (Red)";
            } else {
                currentAIModel = effectiveBlackModel;
                playerSide = "BLACK";
                aiName = effectiveBlackModel + " (Black)";
            }

            System.out.println("[makeAIMove] CoT mode current round: " + moveCount +
                    ", side: " + turn + ", model: " + currentAIModel +
                    ", config: " + effectiveRedModel + "(Red) vs " + effectiveBlackModel + "(Black)");

            String[][] boardArray = boardToArray();
            String boardDescription = aiBoardDescriber.describeBoardForAI(
                    boardArray, playerSide, currentAIModel);

            System.out.println("[CoT AI Turn] Legal moves count: " + legalMoves.size());

            System.out.println((turn == XqIndividualJuge.Side.RED ? "[Red]" : "[Black]") +
                    " [" + currentAIModel + "] Calling...");

            String sessionId = "ai_battle_cot_" + playerSide.toLowerCase() + "_" + currentAIModel.toLowerCase();

            long aiStartTime = System.currentTimeMillis();
            boolean aiSuccess = false;
            String reasoning = "";
            String answer = "";

            switch (currentAIModel) {
                case "DeepSeekCoT":
                    DeepseekCotClient.DeepseekCotResult deepseekResult = deepseekCotClient.chatStructured(
                            sessionId,
                            buildCotPrompt(boardDescription, playerSide, legalMoves, moveCount)
                    );
                    aiSuccess = deepseekResult.isSuccess();
                    if (aiSuccess) {
                        reasoning = deepseekResult.getReasoning();
                        answer = deepseekResult.getAnswer();
                        aiMove = deepseekResult.getMove();
                    }
                    break;
                case "GeminiCoT":
                    GeminiCotClient.GeminiCotResult geminiResult = geminiCotClient.chatStructured(
                            sessionId,
                            buildCotPrompt(boardDescription, playerSide, legalMoves, moveCount)
                    );
                    aiSuccess = geminiResult.isSuccess();
                    if (aiSuccess) {
                        reasoning = geminiResult.getReasoning();
                        answer = geminiResult.getAnswer();
                        aiMove = geminiResult.getMove();
                    }
                    break;
                case "OpenAICoT":
                    OpenAICotClient.OpenAICotResult openaiResult = openAICotClient.chatStructured(
                            sessionId,
                            buildCotPrompt(boardDescription, playerSide, legalMoves, moveCount)
                    );
                    aiSuccess = openaiResult.isSuccess();
                    if (aiSuccess) {
                        reasoning = openaiResult.getReasoningSummary();
                        answer = openaiResult.getAnswerText();
                        aiMove = openaiResult.getMove();
                    }
                    break;
                default:
                    System.err.println("Unknown CoT AI model: " + currentAIModel);
                    aiMove = getRandomLegalMove(legalMoves);
                    break;
            }

            long aiResponseTime = System.currentTimeMillis() - aiStartTime;
            recordAIPerformance(currentAIModel, aiSuccess, aiResponseTime);

            System.out.println((turn == XqIndividualJuge.Side.RED ? "[Red]" : "[Black]") +
                    " [" + currentAIModel + "] raw move: " + aiMove + " (response time: " + aiResponseTime + "ms)");

            if (aiMove == null || aiMove.isEmpty()) {
                System.out.println(aiName + " returned empty move");
                isFoul = true;
                foulReason = "Illegal move";

                recordFoulToCSV(turn, "Illegal move");

                aiMove = getRandomLegalMove(legalMoves);
                System.out.println("Using random move: " + aiMove);
            } else {
                XqIndividualJuge.Move move = uciToMove(aiMove);
                if (move == null) {
                    System.out.println(aiName + " move format error: " + aiMove);
                    isFoul = true;
                    foulReason = "Illegal move";

                    recordFoulToCSV(turn, "Illegal move");

                    aiMove = getRandomLegalMove(legalMoves);
                    move = uciToMove(aiMove);
                    System.out.println("Using random move: " + aiMove);
                } else {
                    XqIndividualJuge.Piece fromPiece = board.at(move.from.r, move.from.c);
                    if (fromPiece == null || fromPiece.side != turn) {
                        System.out.println(aiName + " no own piece at start position: " + aiMove);
                        isFoul = true;
                        foulReason = "Illegal move";

                        recordFoulToCSV(turn, "Illegal move");

                        aiMove = getRandomLegalMove(legalMoves);
                        move = uciToMove(aiMove);
                        System.out.println("Using random move: " + aiMove);
                    } else {
                        List<XqIndividualJuge.Move> legalMovesAtFrom = board.legalMovesAt(move.from);
                        boolean isLegal = false;
                        if (legalMovesAtFrom != null) {
                            for (XqIndividualJuge.Move legalMove : legalMovesAtFrom) {
                                if (legalMove.to != null && legalMove.to.equals(move.to)) {
                                    isLegal = true;
                                    break;
                                }
                            }
                        }

                        if (!isLegal) {
                            System.out.println(aiName + " illegal move: " + aiMove);
                            isFoul = true;
                            foulReason = "Illegal move";

                            recordFoulToCSV(turn, "Illegal move");

                            aiMove = getRandomLegalMove(legalMoves);
                            move = uciToMove(aiMove);
                            System.out.println("Using random move: " + aiMove);
                        }
                    }
                }
            }

            String currentPosition = getBoardPosition(board, turn);
            if (isRepeatedMove && lastRepeatedPosition != null &&
                    currentPosition.equals(lastRepeatedPosition)) {
                System.out.println("Perpetual check/chase prohibited: " + aiMove);
                isFoul = true;
                foulReason = "Illegal move";

                recordFoulToCSV(turn, "Illegal move");

                legalMoves.remove(aiMove);
                if (!legalMoves.isEmpty()) {
                    aiMove = getRandomLegalMove(legalMoves);
                    System.out.println("Using move: " + aiMove);
                }
            }

            XqIndividualJuge.Move finalMove = uciToMove(aiMove);
            if (finalMove == null) {
                aiMove = getRandomLegalMove(legalMoves);
                finalMove = uciToMove(aiMove);
            }

            XqIndividualJuge.Board newBoard = board.makeMove(finalMove);

            String newPosition = getBoardPosition(newBoard, turn.opponent());
            int repeatCount = positionCounts.getOrDefault(newPosition, 0) + 1;
            positionCounts.put(newPosition, repeatCount);

            if (repeatCount >= 5) {
                isRepeatedMove = true;
                lastRepeatedPosition = newPosition;
                System.out.println("Repeated position reached 5 times: " + newPosition);

                if (!isFoul) {
                    isFoul = true;
                    foulReason = "Illegal move";
                    recordFoulToCSV(turn, "Illegal move");
                }
            } else {
                isRepeatedMove = false;
                lastRepeatedPosition = null;
            }

            board = newBoard;
            turn = turn.opponent();
            moveCount++;
            moveHistory.add(aiMove);
            lastAIMove = aiMove;

            System.out.println("Move " + moveCount + ": " + aiName + " -> " +
                    aiMove + (isFoul ? " (foul: " + foulReason + ")" : ""));

        } catch (Exception e) {
            System.err.println("CoT AI move generation error: " + e.getMessage());
            e.printStackTrace();

            recordFoulToCSV(turn, "Illegal move");

            try {
                List<String> legalMoves = getLegalMoves();
                if (!legalMoves.isEmpty()) {
                    String randomMove = getRandomLegalMove(legalMoves);
                    XqIndividualJuge.Move finalMove = uciToMove(randomMove);
                    if (finalMove != null) {
                        board = board.makeMove(finalMove);
                        turn = turn.opponent();
                        moveCount++;
                        System.out.println("Recovery: using random move " + randomMove);
                    }
                }
            } catch (Exception recoveryEx) {
                System.err.println("Recovery failed: " + recoveryEx.getMessage());
            }
        }
    }

    private String buildCotPrompt(String boardDescription, String playerSide, List<String> legalMoves, int round) {
        return String.format(
                "Board state:\n%s\nCurrent side: %s\nLegal moves: %s",
                boardDescription,
                playerSide,
                String.join(", ", legalMoves)
        );
    }

    private void recordFoulToCSV(XqIndividualJuge.Side side, String foulType) {
        if (!"Illegal move".equals(foulType)) {
            return;
        }

        String currentModel = side == XqIndividualJuge.Side.RED ? effectiveRedModel : effectiveBlackModel;
        String opponentModel = side == XqIndividualJuge.Side.RED ? effectiveBlackModel : effectiveRedModel;

        List<String[]> foulRecords = side == XqIndividualJuge.Side.RED ? redFoulRecords : blackFoulRecords;

        String[] record = new String[4];
        record[0] = String.valueOf(moveCount);
        record[1] = foulType;
        record[2] = currentModel;
        record[3] = opponentModel;

        foulRecords.add(record);

        String player = side == XqIndividualJuge.Side.RED ? "Red" : "Black";
        System.out.println("CoT " + player + " foul record added to CSV: round " + moveCount +
                ", " + currentModel + " vs " + opponentModel);
    }

    private void exportGameResultToCSV(boolean maxRoundsReached, XqIndividualJuge.AdvantageResult advantageResult) {
        try {
            redCsvCounter = readCounterFile("Red");
            blackCsvCounter = readCounterFile("Black");
            redAvgTimeCsvCounter = readAvgTimeCounterFile("Red");
            blackAvgTimeCsvCounter = readAvgTimeCounterFile("Black");

            System.out.println("CoT current counter values - Red: " + redCsvCounter + ", Black: " + blackCsvCounter);
            System.out.println("CoT current avg time counter values - Red: " + redAvgTimeCsvCounter + ", Black: " + blackAvgTimeCsvCounter);

            int newRedCounter = redCsvCounter + 1;
            int newBlackCounter = blackCsvCounter + 1;
            int newRedAvgTimeCounter = redAvgTimeCsvCounter + 1;
            int newBlackAvgTimeCounter = blackAvgTimeCsvCounter + 1;

            boolean redGenerated = false;
            boolean blackGenerated = false;
            boolean redAvgTimeGenerated = false;
            boolean blackAvgTimeGenerated = false;

            try {
                generateCSVForFolderWithCounter("Red", maxRoundsReached, advantageResult, newRedCounter);
                redGenerated = true;
            } catch (Exception e) {
                System.err.println("Red folder CSV generation failed: " + e.getMessage());
                e.printStackTrace();
            }

            try {
                generateCSVForFolderWithCounter("Black", maxRoundsReached, advantageResult, newBlackCounter);
                blackGenerated = true;
            } catch (Exception e) {
                System.err.println("Black folder CSV generation failed: " + e.getMessage());
                e.printStackTrace();
            }

            try {
                generateAvgTimeCSVForFolderWithCounter("Red", newRedAvgTimeCounter);
                redAvgTimeGenerated = true;
            } catch (Exception e) {
                System.err.println("Red avg time folder CSV generation failed: " + e.getMessage());
                e.printStackTrace();
            }

            try {
                generateAvgTimeCSVForFolderWithCounter("Black", newBlackAvgTimeCounter);
                blackAvgTimeGenerated = true;
            } catch (Exception e) {
                System.err.println("Black avg time folder CSV generation failed: " + e.getMessage());
                e.printStackTrace();
            }

            if (redGenerated) {
                updateCounterFile("Red", newRedCounter);
                redCsvCounter = newRedCounter;
            }

            if (blackGenerated) {
                updateCounterFile("Black", newBlackCounter);
                blackCsvCounter = newBlackCounter;
            }

            if (redAvgTimeGenerated) {
                updateAvgTimeCounterFile("Red", newRedAvgTimeCounter);
                redAvgTimeCsvCounter = newRedAvgTimeCounter;
            }

            if (blackAvgTimeGenerated) {
                updateAvgTimeCounterFile("Black", newBlackAvgTimeCounter);
                blackAvgTimeCsvCounter = newBlackAvgTimeCounter;
            }

            if (redGenerated || blackGenerated || redAvgTimeGenerated || blackAvgTimeGenerated) {
                System.out.println("CoT CSV files generated, counters updated");
                System.out.println("CoT new counter values - Red: " + redCsvCounter + ", Black: " + blackCsvCounter);
                System.out.println("CoT new avg time counter values - Red: " + redAvgTimeCsvCounter + ", Black: " + blackAvgTimeCsvCounter);
            } else {
                System.err.println("CoT all folder CSV generation failed");
            }

        } catch (Exception e) {
            System.err.println("Export CoT CSV failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void generateCSVForFolderWithCounter(String folder, boolean maxRoundsReached,
                                                 XqIndividualJuge.AdvantageResult advantageResult, int counter) {
        try {
            System.out.println("CoT generating CSV for " + folder + " folder, using counter: " + counter);

            String fileName;
            if (folder.equals("Red")) {
                fileName = String.format("%s cot VS %s cot%02d.csv",
                        effectiveRedModel, effectiveBlackModel, counter);
            } else {
                fileName = String.format("%s cot VS %s cot%02d.csv",
                        effectiveBlackModel, effectiveRedModel, counter);
            }

            String dirPath = "src/main/resources/CSV_Individual_Cot_" + folder;
            java.nio.file.Path csvDir = java.nio.file.Paths.get(dirPath);
            if (!java.nio.file.Files.exists(csvDir)) {
                java.nio.file.Files.createDirectories(csvDir);
            }

            java.nio.file.Path filePath = csvDir.resolve(fileName);

            try (FileOutputStream fos = new FileOutputStream(filePath.toFile());
                 OutputStreamWriter writer = new OutputStreamWriter(fos, java.nio.charset.StandardCharsets.UTF_8)) {

                writer.write('\uFEFF');

                writer.write("Round,Foul Type,Current Model,Opponent Model\n");

                if (folder.equals("Red")) {
                    for (String[] record : redFoulRecords) {
                        writer.write(String.join(",", record) + "\n");
                    }
                } else {
                    for (String[] record : blackFoulRecords) {
                        writer.write(String.join(",", record) + "\n");
                    }
                }

                String resultLine;
                String currentModel, opponentModel;

                if (folder.equals("Red")) {
                    currentModel = effectiveRedModel;
                    opponentModel = effectiveBlackModel;
                } else {
                    currentModel = effectiveBlackModel;
                    opponentModel = effectiveRedModel;
                }

                if (maxRoundsReached) {
                    if (advantageResult != null) {
                        switch (advantageResult) {
                            case RED_GREAT_ADVANTAGE:
                                resultLine = moveCount + "," + effectiveRedModel + " has great advantage," + currentModel + "," + opponentModel;
                                break;
                            case RED_ADVANTAGE:
                                resultLine = moveCount + "," + effectiveRedModel + " has advantage," + currentModel + "," + opponentModel;
                                break;
                            case BLACK_GREAT_ADVANTAGE:
                                resultLine = moveCount + "," + effectiveBlackModel + " has great advantage," + currentModel + "," + opponentModel;
                                break;
                            case BLACK_ADVANTAGE:
                                resultLine = moveCount + "," + effectiveBlackModel + " has advantage," + currentModel + "," + opponentModel;
                                break;
                            case EVEN:
                                resultLine = moveCount + ",Even," + currentModel + "," + opponentModel;
                                break;
                            default:
                                resultLine = moveCount + ",Draw," + currentModel + "," + opponentModel;
                        }
                    } else {
                        resultLine = moveCount + ",Max rounds reached," + currentModel + "," + opponentModel;
                    }
                } else {
                    if (winner == null) {
                        resultLine = moveCount + ",Draw," + currentModel + "," + opponentModel;
                    } else if ("RED".equals(winner)) {
                        resultLine = moveCount + "," + effectiveRedModel + " win," + currentModel + "," + opponentModel;
                    } else {
                        resultLine = moveCount + "," + effectiveBlackModel + " win," + currentModel + "," + opponentModel;
                    }
                }
                writer.write(resultLine + "\n");

                System.out.println("CoT " + folder + " folder CSV generated: " + fileName);

            } catch (Exception e) {
                System.err.println("Write CoT " + folder + " folder CSV failed: " + e.getMessage());
                e.printStackTrace();
            }

        } catch (Exception e) {
            System.err.println("Generate CoT " + folder + " folder CSV failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void generateAvgTimeCSVForFolderWithCounter(String folder, int counter) {
        try {
            System.out.println("CoT generating avg time CSV for " + folder + " folder, using counter: " + counter);

            String fileName;
            if (folder.equals("Red")) {
                fileName = String.format("CoT_Red_AverageTime%02d.csv", counter);
            } else {
                fileName = String.format("CoT_Black_AverageTime%02d.csv", counter);
            }

            String dirPath = "src/main/resources/CSV_Individual_Cot_" + folder + "_AverageTime";
            java.nio.file.Path csvDir = java.nio.file.Paths.get(dirPath);
            if (!java.nio.file.Files.exists(csvDir)) {
                java.nio.file.Files.createDirectories(csvDir);
            }

            java.nio.file.Path filePath = csvDir.resolve(fileName);

            Map<String, Double> avgResponseTimes = new HashMap<>();
            for (Map.Entry<String, List<Long>> entry : aiResponseTimes.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    double avg = entry.getValue().stream().mapToLong(Long::longValue).average().orElse(0);
                    avgResponseTimes.put(entry.getKey(), avg);
                }
            }

            try (FileOutputStream fos = new FileOutputStream(filePath.toFile());
                 OutputStreamWriter writer = new OutputStreamWriter(fos, java.nio.charset.StandardCharsets.UTF_8)) {

                writer.write('\uFEFF');

                writer.write("Model Name,Average Response Time (ms),Opponent Model Name\n");

                if (folder.equals("Red")) {
                    String currentModel = effectiveRedModel;
                    String opponentModel = effectiveBlackModel;
                    double avgTime = avgResponseTimes.getOrDefault(currentModel, 0.0);
                    writer.write(String.format("%s,%.2f,%s\n", currentModel, avgTime, opponentModel));
                } else {
                    String currentModel = effectiveBlackModel;
                    String opponentModel = effectiveRedModel;
                    double avgTime = avgResponseTimes.getOrDefault(currentModel, 0.0);
                    writer.write(String.format("%s,%.2f,%s\n", currentModel, avgTime, opponentModel));
                }

                System.out.println("CoT " + folder + " avg time folder CSV generated: " + fileName);
                System.out.println("CoT average response time stats: " + avgResponseTimes);

            } catch (Exception e) {
                System.err.println("Write CoT " + folder + " avg time folder CSV failed: " + e.getMessage());
                e.printStackTrace();
            }

        } catch (Exception e) {
            System.err.println("Generate CoT " + folder + " avg time folder CSV failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private int readAvgTimeCounterFile(String folder) {
        try {
            String dirPath = "src/main/resources/CSV_Individual_Cot_" + folder + "_AverageTime";
            String counterFileName = folder.equals("Red") ? "CoT_Red_AverageTime_counter.txt" : "CoT_Black_AverageTime_counter.txt";
            java.nio.file.Path counterPath = java.nio.file.Paths.get(dirPath, counterFileName);

            if (!java.nio.file.Files.exists(counterPath.getParent())) {
                java.nio.file.Files.createDirectories(counterPath.getParent());
            }

            if (!java.nio.file.Files.exists(counterPath)) {
                java.nio.file.Files.write(counterPath, "0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return 0;
            }

            String content = new String(java.nio.file.Files.readAllBytes(counterPath),
                    java.nio.charset.StandardCharsets.UTF_8);
            return Integer.parseInt(content.trim());
        } catch (Exception e) {
            System.err.println("Read CoT " + folder + " avg time counter file failed: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }
    }

    private void updateAvgTimeCounterFile(String folder, int newCounter) {
        try {
            String dirPath = "src/main/resources/CSV_Individual_Cot_" + folder + "_AverageTime";
            String counterFileName = folder.equals("Red") ? "CoT_Red_AverageTime_counter.txt" : "CoT_Black_AverageTime_counter.txt";
            java.nio.file.Path counterPath = java.nio.file.Paths.get(dirPath, counterFileName);

            java.nio.file.Files.createDirectories(counterPath.getParent());

            java.nio.file.Files.write(counterPath, String.valueOf(newCounter).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("CoT " + folder + " avg time counter updated to: " + newCounter);

            if (folder.equals("Red")) {
                redAvgTimeCsvCounter = newCounter;
            } else {
                blackAvgTimeCsvCounter = newCounter;
            }
        } catch (Exception e) {
            System.err.println("Update CoT " + folder + " avg time counter file failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private int readCounterFile(String folder) {
        try {
            String dirPath = "src/main/resources/CSV_Individual_Cot_" + folder;
            String counterFileName = folder.equals("Red") ? "CoT_Red_counter.txt" : "CoT_Black_counter.txt";
            java.nio.file.Path counterPath = java.nio.file.Paths.get(dirPath, counterFileName);

            if (!java.nio.file.Files.exists(counterPath.getParent())) {
                java.nio.file.Files.createDirectories(counterPath.getParent());
            }

            if (!java.nio.file.Files.exists(counterPath)) {
                java.nio.file.Files.write(counterPath, "0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return 0;
            }

            String content = new String(java.nio.file.Files.readAllBytes(counterPath),
                    java.nio.charset.StandardCharsets.UTF_8);
            return Integer.parseInt(content.trim());
        } catch (Exception e) {
            System.err.println("Read CoT " + folder + " counter file failed: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }
    }

    private void updateCounterFile(String folder, int newCounter) {
        try {
            String dirPath = "src/main/resources/CSV_Individual_Cot_" + folder;
            String counterFileName = folder.equals("Red") ? "CoT_Red_counter.txt" : "CoT_Black_counter.txt";
            java.nio.file.Path counterPath = java.nio.file.Paths.get(dirPath, counterFileName);

            java.nio.file.Files.createDirectories(counterPath.getParent());

            java.nio.file.Files.write(counterPath, String.valueOf(newCounter).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("CoT " + folder + " counter updated to: " + newCounter);

            if (folder.equals("Red")) {
                redCsvCounter = newCounter;
            } else {
                blackCsvCounter = newCounter;
            }
        } catch (Exception e) {
            System.err.println("Update CoT " + folder + " counter file failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String[][] boardToArray() {
        String[][] boardArray = new String[10][9];

        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                XqIndividualJuge.Piece piece = board.at(r, c);
                if (piece != null) {
                    char pieceChar = getPieceChar(piece.type);
                    if (piece.side == XqIndividualJuge.Side.BLACK) {
                        pieceChar = Character.toLowerCase(pieceChar);
                    }
                    boardArray[r][c] = String.valueOf(pieceChar);
                } else {
                    boardArray[r][c] = null;
                }
            }
        }

        return boardArray;
    }

    private char getPieceChar(XqIndividualJuge.PieceType type) {
        switch (type.toString().charAt(0)) {
            case 'R': return 'R';
            case 'N': return 'H';
            case 'B': return 'E';
            case 'A': return 'A';
            case 'K': return 'K';
            case 'C': return 'C';
            case 'P': return 'P';
            default: return '?';
        }
    }

    private List<String> getLegalMoves() {
        List<String> legalMoves = new ArrayList<>();

        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                XqIndividualJuge.Piece piece = board.at(r, c);
                if (piece != null && piece.side == turn) {
                    List<XqIndividualJuge.Move> moves = board.legalMovesAt(new XqIndividualJuge.Pos(r, c));
                    for (XqIndividualJuge.Move move : moves) {
                        legalMoves.add(coordToUci(move));
                    }
                }
            }
        }

        return legalMoves;
    }

    private String getRandomLegalMove(List<String> legalMoves) {
        if (legalMoves.isEmpty()) {
            return null;
        }
        Random random = new Random();
        return legalMoves.get(random.nextInt(legalMoves.size()));
    }

    private XqIndividualJuge.Move uciToMove(String uci) {
        if (uci == null || uci.length() < 4) {
            return null;
        }

        try {
            int fromC = uci.charAt(0) - 'a';
            int fromR = 9 - (uci.charAt(1) - '0');
            int toC = uci.charAt(2) - 'a';
            int toR = 9 - (uci.charAt(3) - '0');

            if (fromR < 0 || fromR >= 10 || fromC < 0 || fromC >= 9 ||
                    toR < 0 || toR >= 10 || toC < 0 || toC >= 9) {
                System.out.println("Coordinates out of board: " + uci);
                return null;
            }

            return new XqIndividualJuge.Move(new XqIndividualJuge.Pos(fromR, fromC), new XqIndividualJuge.Pos(toR, toC));
        } catch (Exception e) {
            System.out.println("Failed to parse UCI move: " + uci + ", error: " + e.getMessage());
            return null;
        }
    }

    private void handleNoLegalMoves() {
        gameOver = true;

        if (turn == XqIndividualJuge.Side.RED) {
            winner = "BLACK";
            gameResult = "Red has no legal moves, " + effectiveBlackModel + " (Black) wins";
        } else {
            winner = "RED";
            gameResult = "Black has no legal moves, " + effectiveRedModel + " (Red) wins";
        }

        System.out.println("CoT game ended: " + gameResult);
        exportGameResultToCSV(false, null);
    }

    private String getBoardPosition(XqIndividualJuge.Board board, XqIndividualJuge.Side turn) {
        StringBuilder sb = new StringBuilder();
        sb.append(turn.toString()).append("|");

        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                XqIndividualJuge.Piece piece = board.at(r, c);
                if (piece != null) {
                    sb.append(piece.type.toString()).append(piece.side.toString())
                            .append(r).append(c).append(";");
                }
            }
        }
        return sb.toString();
    }

    @PostMapping("/game/end")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> endGame(@RequestBody Map<String, Object> gameData) {
        try {
            String winner = (String) gameData.get("winner");
            String reason = (String) gameData.get("reason");

            processGameEnd(winner, reason);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "CoT Game ended, foul records generated");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    @GetMapping("/ai/state")
    @ResponseBody
    public Map<String, Object> getAIGameState() {
        Map<String, Object> state = new HashMap<>();

        state.put("board", boardToString());
        state.put("turn", turn.toString());
        state.put("gameOver", gameOver);
        state.put("winner", winner);
        state.put("gameResult", gameResult);
        state.put("moveCount", moveCount);
        state.put("moveHistory", moveHistory);
        state.put("aiBattleMode", aiBattleMode);
        state.put("aiThinking", aiThinking);
        state.put("redAIModel", effectiveRedModel);
        state.put("blackAIModel", effectiveBlackModel);
        state.put("maxRoundsReached", maxRoundsReached);
        state.put("maxRoundsLimit", MAX_ROUNDS);
        state.put("currentRound", moveCount);
        state.put("aiPerformance", getAIPerformanceStats());

        state.put("redFoulRecords", formatFoulRecords(redFoulRecords));
        state.put("blackFoulRecords", formatFoulRecords(blackFoulRecords));

        String currentPlayerPerspective;
        if (turn == XqIndividualJuge.Side.RED) {
            currentPlayerPerspective = "Red (" + effectiveRedModel + ")";
        } else {
            currentPlayerPerspective = "Black (" + effectiveBlackModel + ")";
        }
        state.put("currentPlayerPerspective", currentPlayerPerspective);

        state.put("redInCheck", isInCheck(board, XqIndividualJuge.Side.RED));
        state.put("blackInCheck", isInCheck(board, XqIndividualJuge.Side.BLACK));

        return state;
    }

    private List<Map<String, String>> formatFoulRecords(List<String[]> foulRecords) {
        List<Map<String, String>> formatted = new ArrayList<>();
        for (String[] record : foulRecords) {
            Map<String, String> map = new HashMap<>();
            map.put("round", record[0]);
            map.put("errorType", record[1]);
            map.put("currentModel", record[2]);
            map.put("opponentModel", record[3]);
            formatted.add(map);
        }
        return formatted;
    }

    private void processGameEnd(String winner, String reason) {
        exportGameResultToCSV(false, null);
    }

    private XqIndividualJuge.GameResult checkGameState() {
        return XqIndividualJuge.checkGameState(board, turn);
    }

    private boolean isInCheck(XqIndividualJuge.Board board, XqIndividualJuge.Side side) {
        return board.inCheck(side);
    }

    private String coordToUci(XqIndividualJuge.Move m) {
        return "" + (char)('a' + m.from.c) + (9 - m.from.r)
                + (char)('a' + m.to.c)   + (9 - m.to.r);
    }

    private String boardToString() {
        String[][] boardArray = boardToArray();
        return aiBoardDescriber.getFormattedBoard(boardArray);
    }

    private List<Map<String, String>> readCSV(String filePath) {
        List<Map<String, String>> data = new ArrayList<>();
        try {
            Resource resource = new ClassPathResource(filePath);
            if (!resource.exists()) {
                return data;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream()));

            String line;
            boolean firstLine = true;
            String[] headers = new String[0];

            while ((line = reader.readLine()) != null) {
                String[] values = line.split(",");
                if (firstLine) {
                    headers = values;
                    firstLine = false;
                } else {
                    Map<String, String> row = new HashMap<>();
                    for (int i = 0; i < headers.length && i < values.length; i++) {
                        row.put(headers[i].trim(), values[i].trim());
                    }
                    data.add(row);
                }
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return data;
    }
}