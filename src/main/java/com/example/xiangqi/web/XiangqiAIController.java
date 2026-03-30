// XiangqiAIController.java
package com.example.xiangqi.web;

import com.example.xiangqi.llm.DeepseekClient;
import com.example.xiangqi.llm.OpenAIClient;
import com.example.xiangqi.llm.GeminiClient;
import com.example.xiangqi.llm.ErrorCsvRecorder;
import com.example.xiangqi.engine.EngineService;
import com.example.xiangqi.game.XqRules.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/xiangqi")
@CrossOrigin(origins = "*")
public class XiangqiAIController {

    @Autowired
    private DeepseekClient deepseekClient;

    @Autowired
    private OpenAIClient openAIClient;

    @Autowired
    private GeminiClient geminiClient;

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
    private String currentAiType = "DeepSeek";
    private final List<ErrorCsvRecorder.ErrorRecord> currentGameErrors = new ArrayList<>();

    @PostMapping("/ai-battle/new")
    public ResponseEntity<Map<String, Object>> startNewAIBattle(
            @RequestParam(value = "aiType", defaultValue = "DeepSeek") String aiType) {

        if (!"DeepSeek".equalsIgnoreCase(aiType) &&
                !"OpenAI".equalsIgnoreCase(aiType) &&
                !"Gemini".equalsIgnoreCase(aiType)) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "Unsupported AI type. Please choose: DeepSeek, OpenAI or Gemini");
            return ResponseEntity.badRequest().body(response);
        }

        this.currentAiType = aiType;
        cancelCurrentGame();
        resetGame();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "AI battle started - " + aiType + "(Red) vs Pikafish(Black)");
        response.put("aiType", aiType);
        response.put("gameId", currentGameId);

        System.out.println("[Zero-shot] Starting new AI battle: " + aiType + "(Red) vs Pikafish(Black), gameId=" + currentGameId);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/ai-battle/next-round")
    public ResponseEntity<Map<String, Object>> playNextRound() {
        Map<String, Object> response = new HashMap<>();
        currentRound++;

        if (gameOver) {
            response.put("success", false);
            response.put("error", "Game already ended");
            response.put("gameResult", gameResult);
            return ResponseEntity.ok(response);
        }

        System.out.println("[Zero-shot] Round " + currentRound + " started (AI type: " + currentAiType + ")");

        Map<String, Object> aiResult = playAIMove();
        if (!(Boolean) aiResult.get("success")) {
            gameOver = true;
            gameResult = "Pikafish wins - " + currentAiType + " cannot continue";
            winner = "BLACK";

            recordFoul(currentRound, currentAiType + " cannot continue", currentAiType);
            recordGameResult(currentAiType + " cannot continue");
            saveAIErrorToCSV();

            response.put("success", false);
            response.put("gameOver", true);
            response.put("winner", winner);
            response.put("gameResult", gameResult);
            response.put("foul", true);
            response.put("foulPlayer", currentAiType);
            response.put("foulReason", aiResult.get("errorMessage"));
            response.put("boardState", getCurrentBoardState());
            return ResponseEntity.ok(response);
        }

        String aiMove = (String) aiResult.get("move");
        System.out.println("[Zero-shot] " + currentAiType + " move: " + aiMove);

        if (isCheckmatedStrict(Side.BLACK)) {
            gameOver = true;
            gameResult = currentAiType + " wins - checkmate";
            winner = "RED";

            recordGameResult(currentAiType + " wins - checkmate Pikafish");
            saveAIErrorToCSV();

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
            saveAIErrorToCSV();

            response.put("success", false);
            response.put("gameOver", true);
            response.put("winner", winner);
            response.put("gameResult", gameResult);
            response.put("foul", true);
            response.put("foulPlayer", "Pikafish");
            response.put("foulReason", pikafishResult.get("errorMessage"));
            response.put("boardState", getCurrentBoardState());
            return ResponseEntity.ok(response);
        }

        String pikafishMove = (String) pikafishResult.get("move");
        System.out.println("[Zero-shot] Pikafish move: " + pikafishMove);

        if (isCheckmatedStrict(Side.RED)) {
            gameOver = true;
            gameResult = "Pikafish wins - checkmate";
            winner = "BLACK";

            recordGameResult(currentAiType + " checkmated");
            saveAIErrorToCSV();

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
            saveAIErrorToCSV();

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
        response.put("aiMove", aiMove);
        response.put("pikafishMove", pikafishMove);
        response.put("aiType", currentAiType);
        response.put("round", currentRound);
        response.put("boardState", getCurrentBoardState());

        return ResponseEntity.ok(response);
    }

    private Map<String, Object> playAIMove() {
        switch (currentAiType.toLowerCase()) {
            case "openai":
                return playOpenAIMove();
            case "gemini":
                return playGeminiMove();
            default:
                return playDeepSeekMove();
        }
    }

    private Map<String, Object> playDeepSeekMove() {
        Map<String, Object> result = new HashMap<>();
        int maxAttempts = 1;
        int attempts = 0;
        boolean hasFoul = false;
        String foulReason = null;

        if (isCheckmatedStrict(Side.RED)) {
            System.out.println("[DeepSeek] Red is checkmated, cannot move");
            result.put("success", false);
            result.put("errorMessage", "Red is checkmated, no legal moves");
            return result;
        }

        List<String> legalMoves = getAllLegalMoves(Side.RED);
        if (legalMoves.isEmpty()) {
            System.out.println("[DeepSeek] Red is stalemated, cannot move");
            result.put("success", false);
            result.put("errorMessage", "Red is stalemated, no legal moves");
            return result;
        }

        System.out.println("[DeepSeek] Analyzing... AI autonomous move generation");

        while (attempts < maxAttempts) {
            attempts++;
            try {
                String boardDescription = generateEnhancedBoardDescription(attempts == 1);
                System.out.println("[Zero-shot] Round " + currentRound + " calling DeepSeek move, gameId=" + currentGameId);
                System.out.println("[DeepSeek] Board description length: " + boardDescription.length());

                String aiMove = deepseekClient.getXiangqiMove(currentGameId, boardDescription, "RED", currentRound);

                if (aiMove != null && isValidMoveFormat(aiMove)) {
                    if (legalMoves.contains(aiMove)) {
                        boolean moveApplied = applyAIMove(aiMove, Side.RED);
                        if (moveApplied) {
                            System.out.println("[DeepSeek] Attempt " + attempts + " succeeded: " + aiMove);
                            result.put("success", true);
                            result.put("move", aiMove);
                            if (hasFoul) {
                                result.put("foul", true);
                                result.put("foulReason", foulReason);
                            }
                            return result;
                        }
                    } else {
                        System.out.println("[DeepSeek] Attempt " + attempts + ": illegal move - " + aiMove);
                        recordFoul(currentRound, "Illegal move: " + aiMove, "DeepSeek");
                        hasFoul = true;
                        foulReason = "Illegal move: " + aiMove;
                        recordError("Illegal move: " + aiMove);
                    }
                } else {
                    System.out.println("[DeepSeek] Attempt " + attempts + ": invalid move - " + aiMove);
                    recordFoul(currentRound, "Invalid move format: " + aiMove, "DeepSeek");
                    hasFoul = true;
                    foulReason = "Invalid move format: " + aiMove;
                    recordError("Invalid move format: " + aiMove);
                }

                Thread.sleep(300);

            } catch (Exception e) {
                System.err.println("[DeepSeek] Attempt " + attempts + " error: " + e.getMessage());
                recordFoul(currentRound, "DeepSeek error: " + e.getMessage(), "DeepSeek");
                hasFoul = true;
                foulReason = "DeepSeek error: " + e.getMessage();
                recordError("DeepSeek error: " + e.getMessage());
            }
        }

        if (!legalMoves.isEmpty()) {
            String randomMove = getRandomMoveFromList(legalMoves);
            System.out.println("[DeepSeek] Using random move: " + randomMove);
            boolean moveApplied = applyAIMove(randomMove, Side.RED);
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
        }

        System.out.println("[DeepSeek] Still cannot provide a legal move after multiple attempts");
        result.put("success", false);
        result.put("errorMessage", "DeepSeek failed to provide a valid move after " + maxAttempts + " attempts");

        return result;
    }

    private Map<String, Object> playOpenAIMove() {
        Map<String, Object> result = new HashMap<>();
        int maxAttempts = 1;
        int attempts = 0;
        boolean hasFoul = false;
        String foulReason = null;

        if (isCheckmatedStrict(Side.RED)) {
            System.out.println("[OpenAI] Red is checkmated, cannot move");
            result.put("success", false);
            result.put("errorMessage", "Red is checkmated, no legal moves");
            return result;
        }

        List<String> legalMoves = getAllLegalMoves(Side.RED);
        if (legalMoves.isEmpty()) {
            System.out.println("[OpenAI] Red is stalemated, cannot move");
            result.put("success", false);
            result.put("errorMessage", "Red is stalemated, no legal moves");
            return result;
        }

        System.out.println("[OpenAI] Analyzing... AI autonomous move generation");

        while (attempts < maxAttempts) {
            attempts++;
            try {
                String boardDescription = generateEnhancedBoardDescription(attempts == 1);
                System.out.println("[Zero-shot] Round " + currentRound + " calling OpenAI move, gameId=" + currentGameId);
                System.out.println("[OpenAI] Board description length: " + boardDescription.length());

                String aiMove = openAIClient.getXiangqiMove(currentGameId, boardDescription, "RED", currentRound);

                if (aiMove != null && isValidMoveFormat(aiMove)) {
                    if (legalMoves.contains(aiMove)) {
                        boolean moveApplied = applyAIMove(aiMove, Side.RED);
                        if (moveApplied) {
                            System.out.println("[OpenAI] Attempt " + attempts + " succeeded: " + aiMove);
                            result.put("success", true);
                            result.put("move", aiMove);
                            if (hasFoul) {
                                result.put("foul", true);
                                result.put("foulReason", foulReason);
                            }
                            return result;
                        }
                    } else {
                        System.out.println("[OpenAI] Attempt " + attempts + ": illegal move - " + aiMove);
                        recordFoul(currentRound, "Illegal move: " + aiMove, "OpenAI");
                        hasFoul = true;
                        foulReason = "Illegal move: " + aiMove;
                        recordError("Illegal move: " + aiMove);
                    }
                } else {
                    System.out.println("[OpenAI] Attempt " + attempts + ": invalid move - " + aiMove);
                    recordFoul(currentRound, "Invalid move format: " + aiMove, "OpenAI");
                    hasFoul = true;
                    foulReason = "Invalid move format: " + aiMove;
                    recordError("Invalid move format: " + aiMove);
                }

                Thread.sleep(300);

            } catch (Exception e) {
                System.err.println("[OpenAI] Attempt " + attempts + " error: " + e.getMessage());
                recordFoul(currentRound, "OpenAI error: " + e.getMessage(), "OpenAI");
                hasFoul = true;
                foulReason = "OpenAI error: " + e.getMessage();
                recordError("OpenAI error: " + e.getMessage());
            }
        }

        if (!legalMoves.isEmpty()) {
            String randomMove = getRandomMoveFromList(legalMoves);
            System.out.println("[OpenAI] Using random move: " + randomMove);
            boolean moveApplied = applyAIMove(randomMove, Side.RED);
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
        }

        System.out.println("[OpenAI] Still cannot provide a legal move after multiple attempts");
        result.put("success", false);
        result.put("errorMessage", "OpenAI failed to provide a valid move after " + maxAttempts + " attempts");

        return result;
    }

    private Map<String, Object> playGeminiMove() {
        Map<String, Object> result = new HashMap<>();
        int maxAttempts = 1;
        int attempts = 0;
        boolean hasFoul = false;
        String foulReason = null;

        if (isCheckmatedStrict(Side.RED)) {
            System.out.println("[Gemini] Red is checkmated, cannot move");
            result.put("success", false);
            result.put("errorMessage", "Red is checkmated, no legal moves");
            return result;
        }

        List<String> legalMoves = getAllLegalMoves(Side.RED);
        if (legalMoves.isEmpty()) {
            System.out.println("[Gemini] Red is stalemated, cannot move");
            result.put("success", false);
            result.put("errorMessage", "Red is stalemated, no legal moves");
            return result;
        }

        System.out.println("[Gemini] Analyzing... AI autonomous move generation");

        while (attempts < maxAttempts) {
            attempts++;
            try {
                String boardDescription = generateEnhancedBoardDescription(attempts == 1);
                System.out.println("[Zero-shot] Round " + currentRound + " calling Gemini move, gameId=" + currentGameId);
                System.out.println("[Gemini] Board description length: " + boardDescription.length());

                String aiMove = geminiClient.getXiangqiMove(currentGameId, boardDescription, "RED", currentRound, false);

                if (aiMove != null && isValidMoveFormat(aiMove)) {
                    if (legalMoves.contains(aiMove)) {
                        boolean moveApplied = applyAIMove(aiMove, Side.RED);
                        if (moveApplied) {
                            System.out.println("[Gemini] Attempt " + attempts + " succeeded: " + aiMove);
                            result.put("success", true);
                            result.put("move", aiMove);
                            if (hasFoul) {
                                result.put("foul", true);
                                result.put("foulReason", foulReason);
                            }
                            return result;
                        }
                    } else {
                        System.out.println("[Gemini] Attempt " + attempts + ": illegal move - " + aiMove);
                        recordFoul(currentRound, "Illegal move: " + aiMove, "Gemini");
                        hasFoul = true;
                        foulReason = "Illegal move: " + aiMove;
                        recordError("Illegal move: " + aiMove);
                    }
                } else {
                    System.out.println("[Gemini] Attempt " + attempts + ": invalid move - " + aiMove);
                    recordFoul(currentRound, "Invalid move format: " + aiMove, "Gemini");
                    hasFoul = true;
                    foulReason = "Invalid move format: " + aiMove;
                    recordError("Invalid move format: " + aiMove);
                }

                Thread.sleep(300);

            } catch (Exception e) {
                System.err.println("[Gemini] Attempt " + attempts + " error: " + e.getMessage());
                recordFoul(currentRound, "Gemini error: " + e.getMessage(), "Gemini");
                hasFoul = true;
                foulReason = "Gemini error: " + e.getMessage();
                recordError("Gemini error: " + e.getMessage());
            }
        }

        if (!legalMoves.isEmpty()) {
            String randomMove = getRandomMoveFromList(legalMoves);
            System.out.println("[Gemini] Using random move: " + randomMove);
            boolean moveApplied = applyAIMove(randomMove, Side.RED);
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
        }

        System.out.println("[Gemini] Still cannot provide a legal move after multiple attempts");
        result.put("success", false);
        result.put("errorMessage", "Gemini failed to provide a valid move after " + maxAttempts + " attempts");

        return result;
    }

    private Map<String, Object> playPikafishMove() {
        Map<String, Object> result = new HashMap<>();
        boolean hasFoul = false;
        String foulReason = null;

        try {
            if (isCheckmatedStrict(Side.BLACK)) {
                System.out.println("[Pikafish] Black is checkmated, cannot move");
                result.put("success", false);
                result.put("errorMessage", "Black is checkmated, no legal moves");
                return result;
            }

            List<String> legalMoves = getAllLegalMoves(Side.BLACK);
            if (legalMoves.isEmpty()) {
                System.out.println("[Pikafish] Black is stalemated, cannot move");
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
            result.put("errorMessage", "Pikafish engine error: " + e.getMessage());
        }

        return result;
    }

    private boolean isCheckmatedStrict(Side side) {
        if (!board.inCheck(side)) {
            return false;
        }

        boolean hasEscapeMoves = hasLegalMovesThatEscapeCheck(side);

        if (!hasEscapeMoves) {
            System.out.println("Strict checkmate check: " + side + " is checkmated");
        } else {
            System.out.println("Strict checkmate check: " + side + " has escape moves, not checkmated");
        }

        return !hasEscapeMoves;
    }

    private String getRandomMoveFromList(List<String> legalMoves) {
        if (legalMoves == null || legalMoves.isEmpty()) {
            return null;
        }
        Random random = new Random();
        return legalMoves.get(random.nextInt(legalMoves.size()));
    }

    private void recordFoul(int round, String type, String player) {
        Map<String, Object> foulRecord = new HashMap<>();
        foulRecord.put("round", round);
        foulRecord.put("type", type);
        foulRecord.put("player", player);
        foulRecord.put("timestamp", System.currentTimeMillis());

        foulRecords.add(foulRecord);

        System.out.println("[Zero-shot] Recording error - round " + round + " [" + player + "]: " + type);
    }

    private void recordError(String errorType) {
        ErrorCsvRecorder.ErrorRecord record = ErrorCsvRecorder.recordError(currentRound, errorType);
        currentGameErrors.add(record);

        System.out.println("[Zero-shot] Recording error - round " + currentRound + ": " + record.getErrorType());
    }

    private void recordGameResult(String result) {
        ErrorCsvRecorder.ErrorRecord record = ErrorCsvRecorder.recordGameResult(currentRound, result);
        currentGameErrors.add(record);

        System.out.println("[Zero-shot] Recording game result: " + record.getErrorType());
    }

    private boolean isCheckmated(Side side) {
        if (!board.inCheck(side)) {
            return false;
        }

        return !hasLegalMovesThatEscapeCheck(side);
    }

    private boolean hasLegalMovesThatEscapeCheck(Side side) {
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null && piece.side == side) {
                    List<Move> moves = board.legalMovesAt(new Pos(r, c));
                    for (Move move : moves) {
                        Board newBoard = board.makeMove(move);
                        if (!newBoard.inCheck(side)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean isStalemated(Side side) {
        if (board.inCheck(side)) {
            return false;
        }

        return !hasLegalMoves(side);
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

    private void saveAIErrorToCSV() {
        ErrorCsvRecorder.RecordType recordType;
        switch (currentAiType.toLowerCase()) {
            case "openai":
                recordType = ErrorCsvRecorder.RecordType.OPENAI_ZERO_SHOT;
                break;
            case "gemini":
                recordType = ErrorCsvRecorder.RecordType.GEMINI_ZERO_SHOT;
                break;
            default:
                recordType = ErrorCsvRecorder.RecordType.DEEPSEEK_ZERO_SHOT;
                break;
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

    /**
     * Generate enhanced board description, clearly indicating AI is Red.
     * @param includeRules whether to include full rules (only on first attempt)
     */
    private String generateEnhancedBoardDescription(boolean includeRules) {
        StringBuilder description = new StringBuilder();

        if (includeRules || currentRound == 1) {
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
            description.append("\n历史移动：\n");
            for (int i = Math.max(0, moveHistory.size() - 3); i < moveHistory.size(); i++) {
                description.append("第").append(i + 1).append("回合: ").append(moveHistory.get(i)).append("\n");
            }
        }

        description.append("\n回合: ").append(currentRound).append("\n");
        description.append("请给出你的移动（4字符坐标，例如：b0c2）：");

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

            if (r == 0) {
                visual.append("   ← 红方底线（你的底线）");
            } else if (r == 4) {
                visual.append("   ← 楚河");
            } else if (r == 5) {
                visual.append("   ← 汉界");
            } else if (r == 9) {
                visual.append("   ← 黑方底线");
            }

            visual.append("\n");
        }
        visual.append("  a  b  c  d  e  f  g  h  i\n");
        visual.append("    黑方（对手棋子，小写字母）\n\n");

        visual.append("图例说明：\n");
        visual.append("[R] - 你的棋子（红方，大写字母）\n");
        visual.append("[x] - 对手棋子（黑方，小写字母）\n");
        visual.append(" .  - 空位置\n\n");

        visual.append("红方棋子位置参考：\n");
        visual.append("帅(K): e0  士(A): d0,f0  相(E): c0,g0\n");
        visual.append("马(H): b0,h0  车(R): a0,i0  炮(C): b2,h2  兵(P): a3,c3,e3,g3,i3\n");

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

    @Deprecated
    private String generateBoardDescriptionWithoutLegalMoves() {
        return generateEnhancedBoardDescription(true);
    }

    @Deprecated
    private String generateBoardDescriptionWithLegalMoves(List<String> legalMoves) {
        return generateEnhancedBoardDescription(true);
    }

    private void resetGame() {
        board = Board.initial();
        turn = Side.RED;
        moveHistory.clear();
        foulRecords.clear();
        gameOver = false;
        winner = null;
        gameResult = null;
        currentRound = 0;
        currentGameId = UUID.randomUUID().toString();
        currentGameErrors.clear();
        System.out.println("[Zero-shot] AI battle game state reset, gameId=" + currentGameId + ", AI type=" + currentAiType);
    }

    private void cancelCurrentGame() {
        try {
            switch (currentAiType.toLowerCase()) {
                case "openai":
                    System.out.println("Attempted to cancel current OpenAI API call");
                    break;
                case "gemini":
                    System.out.println("Attempted to cancel current Gemini API call");
                    break;
                default:
                    System.out.println("Attempted to cancel current Deepseek API call");
                    break;
            }
            gameOver = true;
            System.out.println("Current game cancelled (AI type: " + currentAiType + ")");
        } catch (Exception e) {
            System.err.println("Error cancelling game: " + e.getMessage());
        }
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

    @GetMapping("/ai-battle/debug-board")
    public ResponseEntity<Map<String, Object>> getDebugBoard() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("boardDescription", generateEnhancedBoardDescription(true));
        response.put("currentRound", currentRound);
        response.put("aiType", currentAiType);
        response.put("gameId", currentGameId);
        return ResponseEntity.ok(response);
    }
}