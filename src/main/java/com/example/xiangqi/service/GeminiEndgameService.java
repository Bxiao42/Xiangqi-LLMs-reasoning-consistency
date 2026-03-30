// GeminiEndgameService.java
package com.example.xiangqi.service;

import com.example.xiangqi.llm.GeminiClient;
import com.example.xiangqi.llm.GeminiCotClient;
import com.example.xiangqi.llm.ErrorCsvRecorder;
import com.example.xiangqi.game.XqEndgameRule.*;
import com.example.xiangqi.game.XqEndgameJudge;
import com.example.xiangqi.engine.EngineService;
import com.example.xiangqi.web.EndgameController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class GeminiEndgameService {

    @Autowired
    private GeminiClient geminiClient;

    @Autowired
    private GeminiCotClient geminiCotClient;

    @Autowired
    private EngineService engineService;

    @Autowired
    private ErrorCsvRecorder errorCsvRecorder;

    @Autowired
    private EndgameAccuracyService endgameAccuracyService;

    private final Map<String, EndgameAIBattleState> battleStates = new HashMap<>();

    private static class EndgameAIBattleState {
        Board board;
        Side turn;
        List<String> moveHistory = new ArrayList<>();
        boolean gameOver = false;
        String winner = null;
        String gameResult = null;
        int currentRound = 0;
        List<Map<String, Object>> foulRecords = new ArrayList<>();
        String gameId;
        int level;
        String mode;

        List<ErrorInfo> tempErrorInfos = new ArrayList<>();
        List<ErrorCsvRecorder.EndgameErrorRecord> currentGameErrors = new ArrayList<>();

        boolean errorsSaved = false;

        class ErrorInfo {
            int round;
            String errorType;
            ErrorInfo(int round, String errorType) {
                this.round = round;
                this.errorType = errorType;
            }
        }

        EndgameAIBattleState(Board board, Side turn, int level, String mode) {
            this.board = board;
            this.turn = turn;
            this.level = level;
            this.mode = mode;
            this.gameId = UUID.randomUUID().toString();
        }

        int getAIMoveCount() {
            return (moveHistory.size() + 1) / 2;
        }

        void createFinalErrorRecords() {
            int aiMoveCount = getAIMoveCount();
            for (ErrorInfo errorInfo : tempErrorInfos) {
                ErrorCsvRecorder.EndgameErrorRecord record =
                        ErrorCsvRecorder.recordEndgameIllegalMove(
                                errorInfo.round,
                                errorInfo.errorType,
                                aiMoveCount,
                                level
                        );
                if (record != null) {
                    currentGameErrors.add(record);
                }
            }
            tempErrorInfos.clear();
        }
    }

    // Starts a new endgame battle with the given level and mode.
    public Map<String, Object> startNewEndgameBattle(int level, String mode, Board initialBoard, Side startingSide) {
        String stateKey = level + "-" + mode;
        cancelCurrentBattle(stateKey);
        EndgameAIBattleState state = new EndgameAIBattleState(initialBoard, startingSide, level, mode);
        battleStates.put(stateKey, state);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Endgame AI battle started - Gemini " + (mode.equals("cot") ? "CoT" : "ZeroShot") + "(Red) vs Pikafish(Black)");
        response.put("gameId", state.gameId);
        response.put("level", level);
        response.put("mode", mode);
        response.put("board", serializeBoard(state.board));

        System.out.println("[Gemini Endgame AI Battle] Starting new endgame AI battle: Level " + level +
                " - Gemini " + (mode.equals("cot") ? "CoT" : "ZeroShot") + "(Red) vs Pikafish(Black)");
        return response;
    }

    // Plays the next round of the ongoing endgame battle.
    public Map<String, Object> playNextRound(int level, String mode) {
        String stateKey = level + "-" + mode;
        EndgameAIBattleState state = battleStates.get(stateKey);
        if (state == null) {
            throw new IllegalArgumentException("Please start the AI battle first");
        }

        Map<String, Object> response = new HashMap<>();
        state.currentRound++;

        if (state.currentRound > 30) {
            state.gameOver = true;
            state.gameResult = "Pikafish wins - no decisive result within 30 rounds";
            state.winner = "BLACK";
            recordEndgameGameResult(state, "30 rounds no decisive result");
            saveEndgameErrorsToCSV(state);
            saveEndgameAccuracyToCSV(state);

            response.put("success", true);
            response.put("gameOver", true);
            response.put("winner", state.winner);
            response.put("gameResult", state.gameResult);
            response.put("round", state.currentRound);
            response.put("boardState", serializeBoard(state.board));
            return response;
        }

        if (state.gameOver) {
            response.put("success", false);
            response.put("error", "Game already ended");
            response.put("gameResult", state.gameResult);
            return response;
        }

        System.out.println("[Gemini Endgame AI Battle] Level " + level + " Round " + state.currentRound + " starting");

        Map<String, Object> geminiResult = playGeminiMove(state, level, mode);
        if (!(Boolean) geminiResult.get("success")) {
            state.gameOver = true;
            state.gameResult = "Pikafish wins - Gemini cannot continue";
            state.winner = "BLACK";
            recordEndgameFoul(state, state.currentRound, "Illegal move", "Gemini");
            recordEndgameGameResult(state, "Gemini cannot continue");
            saveEndgameErrorsToCSV(state);
            saveEndgameAccuracyToCSV(state);

            response.put("success", false);
            response.put("gameOver", true);
            response.put("winner", state.winner);
            response.put("gameResult", state.gameResult);
            response.put("foul", true);
            response.put("foulPlayer", "Gemini");
            response.put("foulReason", geminiResult.get("errorMessage"));
            response.put("boardState", serializeBoard(state.board));
            return response;
        }

        String geminiMove = (String) geminiResult.get("move");
        System.out.println("[Gemini Endgame AI Battle] Gemini" + (mode.equals("cot") ? " CoT" : "") + " move: " + geminiMove);

        if (noCrossingPieces(state.board)) {
            state.gameOver = true;
            state.gameResult = "Pikafish wins - no crossing pieces";
            state.winner = "BLACK";
            recordEndgameGameResult(state, "No crossing pieces - pikafish win");
            saveEndgameErrorsToCSV(state);
            saveEndgameAccuracyToCSV(state);
            response.put("success", true);
            response.put("gameOver", true);
            response.put("winner", state.winner);
            response.put("gameResult", state.gameResult);
            response.put("geminiMove", geminiMove);
            response.put("round", state.currentRound);
            response.put("boardState", serializeBoard(state.board));
            return response;
        }

        if (XqEndgameJudge.isCheckmated(state.board, Side.BLACK)) {
            state.gameOver = true;
            state.gameResult = "Gemini wins - checkmate";
            state.winner = "RED";
            recordEndgameGameResult(state, "gemini win");
            saveEndgameErrorsToCSV(state);
            saveEndgameAccuracyToCSV(state);
            response.put("success", true);
            response.put("gameOver", true);
            response.put("winner", state.winner);
            response.put("gameResult", state.gameResult);
            response.put("geminiMove", geminiMove);
            response.put("round", state.currentRound);
            response.put("boardState", serializeBoard(state.board));
            return response;
        }

        List<String> geminiLegalMoves = getAllLegalMoves(state.board, Side.RED);
        if (geminiLegalMoves.isEmpty()) {
            state.gameOver = true;
            state.gameResult = "Pikafish wins - Gemini has no legal moves";
            state.winner = "BLACK";
            recordEndgameGameResult(state, "pikafish win");
            saveEndgameErrorsToCSV(state);
            saveEndgameAccuracyToCSV(state);
            response.put("success", true);
            response.put("gameOver", true);
            response.put("winner", state.winner);
            response.put("gameResult", state.gameResult);
            response.put("geminiMove", geminiMove);
            response.put("round", state.currentRound);
            response.put("boardState", serializeBoard(state.board));
            return response;
        }

        Map<String, Object> pikafishResult = playPikafishMove(state, level);
        if (!(Boolean) pikafishResult.get("success")) {
            state.gameOver = true;
            state.gameResult = "Gemini wins - Pikafish cannot continue";
            state.winner = "RED";
            recordEndgameFoul(state, state.currentRound, "Illegal move", "Pikafish");
            recordEndgameGameResult(state, "gemini win");
            saveEndgameErrorsToCSV(state);
            saveEndgameAccuracyToCSV(state);
            response.put("success", false);
            response.put("gameOver", true);
            response.put("winner", state.winner);
            response.put("gameResult", state.gameResult);
            response.put("foul", true);
            response.put("foulPlayer", "Pikafish");
            response.put("foulReason", pikafishResult.get("errorMessage"));
            response.put("boardState", serializeBoard(state.board));
            return response;
        }

        String pikafishMove = (String) pikafishResult.get("move");
        System.out.println("[Gemini Endgame AI Battle] Pikafish move: " + pikafishMove);

        if (XqEndgameJudge.isCheckmated(state.board, Side.RED)) {
            state.gameOver = true;
            state.gameResult = "Pikafish wins - checkmate";
            state.winner = "BLACK";
            recordEndgameGameResult(state, "pikafish win");
            saveEndgameErrorsToCSV(state);
            saveEndgameAccuracyToCSV(state);
            response.put("success", true);
            response.put("gameOver", true);
            response.put("winner", state.winner);
            response.put("gameResult", state.gameResult);
            response.put("geminiMove", geminiMove);
            response.put("pikafishMove", pikafishMove);
            response.put("round", state.currentRound);
            response.put("boardState", serializeBoard(state.board));
            return response;
        }

        if (XqEndgameJudge.isStalemate(state.board, Side.RED)) {
            state.gameOver = true;
            state.gameResult = "Pikafish wins - stalemate";
            state.winner = "BLACK";
            recordEndgameGameResult(state, "pikafish win");
            saveEndgameErrorsToCSV(state);
            saveEndgameAccuracyToCSV(state);
            response.put("success", true);
            response.put("gameOver", true);
            response.put("winner", state.winner);
            response.put("gameResult", state.gameResult);
            response.put("geminiMove", geminiMove);
            response.put("pikafishMove", pikafishMove);
            response.put("round", state.currentRound);
            response.put("boardState", serializeBoard(state.board));
            return response;
        }

        response.put("success", true);
        response.put("gameOver", false);
        response.put("geminiMove", geminiMove);
        response.put("pikafishMove", pikafishMove);
        response.put("round", state.currentRound);
        response.put("boardState", serializeBoard(state.board));
        response.put("foulRecords", state.foulRecords);
        return response;
    }

    private void saveEndgameAccuracyToCSV(EndgameAIBattleState state) {
        if (endgameAccuracyService == null) {
            System.err.println("[Accuracy] EndgameAccuracyService not injected");
            return;
        }
        if (state == null || state.moveHistory == null || state.moveHistory.isEmpty()) {
            System.out.println("[Accuracy] No move history, cannot calculate accuracy");
            return;
        }
        try {
            List<String> aiMoves = new ArrayList<>();
            for (int i = 0; i < state.moveHistory.size(); i++) {
                if (i % 2 == 0) {
                    aiMoves.add(state.moveHistory.get(i));
                }
            }
            System.out.println("[Accuracy] AI move sequence (Level " + state.level + "): " + aiMoves);
            String fullMode = "gemini-" + state.mode;
            endgameAccuracyService.recordComparisonResult(state.level, fullMode, aiMoves);
            System.out.println("[Accuracy] Recorded and saved to CSV");
        } catch (Exception e) {
            System.err.println("[Accuracy] Failed to save accuracy: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Executes Gemini's move for the current round.
    private Map<String, Object> playGeminiMove(EndgameAIBattleState state, int level, String mode) {
        Map<String, Object> result = new HashMap<>();

        if (geminiClient == null) {
            System.err.println("Gemini client not initialized");
            result.put("success", false);
            result.put("errorMessage", "Gemini client not initialized");
            return result;
        }

        if (XqEndgameJudge.isCheckmated(state.board, Side.RED)) {
            System.out.println("Gemini is checkmated, cannot move");
            result.put("success", false);
            result.put("errorMessage", "Red is checkmated, no legal moves");
            return result;
        }

        List<String> legalMoves = getAllLegalMoves(state.board, Side.RED);
        if (legalMoves.isEmpty()) {
            System.out.println("Gemini is stalemated, cannot move");
            result.put("success", false);
            result.put("errorMessage", "Red is stalemated, no legal moves");
            return result;
        }

        System.out.println("Gemini analyzing... legal moves: " + legalMoves.size());

        int maxAttempts = 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String sessionId = "endgame-" + state.gameId + "-" + attempt;
                String boardDescription = generateEndgameBoardDescription(state.board, legalMoves, level, mode, state);
                String aiMove = null;
                String rawResponse = null;

                if ("zeroshot".equals(mode)) {
                    try {
                        rawResponse = geminiClient.chat(sessionId, boardDescription);
                        if (rawResponse == null || rawResponse.trim().isEmpty()) {
                            System.err.println("Gemini zeroshot returned empty response");
                            recordEndgameFoul(state, state.currentRound, "Illegal move", "Gemini");
                            recordEndgameTempError(state, "Illegal move");
                            continue;
                        }
                        aiMove = extractMoveFromAnswer(rawResponse);
                        if (aiMove == null || aiMove.isEmpty()) {
                            aiMove = extractMoveFromText(rawResponse);
                        }
                    } catch (Exception e) {
                        System.err.println("Gemini zeroshot call exception: " + e.getClass().getName() + ": " + e.getMessage());
                        e.printStackTrace();
                        recordEndgameFoul(state, state.currentRound, "Illegal move", "Gemini");
                        recordEndgameTempError(state, "Illegal move");
                        continue;
                    }
                } else if ("cot".equals(mode)) {
                    try {
                        GeminiCotClient.GeminiCotResult cotResult = geminiCotClient.chatStructured(sessionId, boardDescription);
                        if (cotResult != null && cotResult.isSuccess()) {
                            aiMove = cotResult.getMove();
                            if (aiMove == null || aiMove.isEmpty()) {
                                aiMove = extractMoveFromText(cotResult.getAnswer());
                            }
                        } else {
                            System.out.println("Gemini CoT call failed: " +
                                    (cotResult != null ? cotResult.getError() : "null"));
                            recordEndgameFoul(state, state.currentRound, "Illegal move", "Gemini");
                            recordEndgameTempError(state, "Illegal move");
                            continue;
                        }
                    } catch (Exception e) {
                        System.err.println("Gemini CoT call exception: " + e.getClass().getName() + ": " + e.getMessage());
                        e.printStackTrace();
                        recordEndgameFoul(state, state.currentRound, "Illegal move", "Gemini");
                        recordEndgameTempError(state, "Illegal move");
                        continue;
                    }
                }

                if (aiMove != null && !aiMove.isEmpty() && isValidMoveFormat(aiMove)) {
                    if (legalMoves.contains(aiMove)) {
                        boolean moveApplied = applyAIMove(state, aiMove, Side.RED);
                        if (moveApplied) {
                            System.out.println("Gemini move succeeded (attempt " + attempt + "): " + aiMove);
                            result.put("success", true);
                            result.put("move", aiMove);
                            result.put("attempt", attempt);
                            return result;
                        } else {
                            System.out.println("Gemini move application failed: " + aiMove);
                            recordEndgameFoul(state, state.currentRound, "Illegal move", "Gemini");
                            recordEndgameTempError(state, "Illegal move");
                        }
                    } else {
                        System.out.println("Gemini returned illegal move (attempt " + attempt + "): " + aiMove +
                                " (legal moves: " + legalMoves.size() + ")");
                        recordEndgameFoul(state, state.currentRound, "Illegal move", "Gemini");
                        recordEndgameTempError(state, "Illegal move");
                    }
                } else {
                    System.out.println("Gemini returned invalid format (attempt " + attempt + "): " +
                            (aiMove != null ? "invalid format: " + aiMove : "empty response"));
                    recordEndgameFoul(state, state.currentRound, "Illegal move", "Gemini");
                    recordEndgameTempError(state, "Illegal move");
                }

            } catch (Exception e) {
                System.err.println("Gemini call error (attempt " + attempt + "): " + e.getMessage());
                e.printStackTrace();
                recordEndgameFoul(state, state.currentRound, "Illegal move", "Gemini");
                recordEndgameTempError(state, "Illegal move");
            }
        }

        if (!legalMoves.isEmpty()) {
            String randomMove = getRandomMoveFromList(legalMoves);
            System.out.println("Gemini 1 attempt failed, using random move: " + randomMove);
            boolean moveApplied = applyAIMove(state, randomMove, Side.RED);
            if (moveApplied) {
                result.put("success", true);
                result.put("move", randomMove);
                result.put("randomFallback", true);
                result.put("attemptsFailed", 1);
                return result;
            }
        }

        System.out.println("Gemini cannot provide a legal move");
        result.put("success", false);
        result.put("errorMessage", "Gemini cannot provide a valid move");
        result.put("attemptsFailed", 1);
        return result;
    }

    // Executes Pikafish's move for the current round.
    private Map<String, Object> playPikafishMove(EndgameAIBattleState state, int level) {
        Map<String, Object> result = new HashMap<>();

        try {
            if (XqEndgameJudge.isCheckmated(state.board, Side.BLACK)) {
                System.out.println("Pikafish is checkmated, cannot move");
                result.put("success", false);
                result.put("errorMessage", "Black is checkmated, no legal moves");
                return result;
            }

            List<String> legalMoves = getAllLegalMoves(state.board, Side.BLACK);
            if (legalMoves.isEmpty()) {
                System.out.println("Pikafish is stalemated, cannot move");
                result.put("success", false);
                result.put("errorMessage", "Black is stalemated, no legal moves");
                return result;
            }

            String fen = boardToEngineFen(state.board, state.turn);
            String bestMoveUci = engineService.bestMove(fen, state.moveHistory, true);

            if (bestMoveUci != null && !bestMoveUci.isEmpty() && legalMoves.contains(bestMoveUci)) {
                boolean moveApplied = applyAIMove(state, bestMoveUci, Side.BLACK);
                if (moveApplied) {
                    System.out.println("Pikafish move: " + bestMoveUci);
                    result.put("success", true);
                    result.put("move", bestMoveUci);
                    return result;
                }
            }

            String randomMove = getRandomMoveFromList(legalMoves);
            System.out.println("Pikafish using random move: " + randomMove);
            boolean moveApplied = applyAIMove(state, randomMove, Side.BLACK);
            if (moveApplied) {
                result.put("success", true);
                result.put("move", randomMove);
                result.put("randomFallback", true);
                return result;
            }

            result.put("success", false);
            result.put("errorMessage", "Pikafish cannot provide a valid move");

        } catch (Exception e) {
            System.err.println("Pikafish move error: " + e.getMessage());
            result.put("success", false);
            result.put("errorMessage", "Pikafish engine error: " + e.getMessage());
        }
        return result;
    }

    // Returns all legal UCI moves for a given side.
    private List<String> getAllLegalMoves(Board board, Side side) {
        List<String> legalMoves = new ArrayList<>();
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null && piece.side == side) {
                    List<Move> moves = board.legalMovesAt(new Pos(r, c));
                    for (Move move : moves) {
                        legalMoves.add(coordToUci(move));
                    }
                }
            }
        }
        return legalMoves;
    }

    // Applies an AI move given as UCI string.
    private boolean applyAIMove(EndgameAIBattleState state, String uci, Side side) {
        try {
            Move move = parseUciMove(uci);
            if (move == null) return false;
            Piece fromPiece = state.board.at(move.from.r, move.from.c);
            if (fromPiece == null || fromPiece.side != side) return false;
            List<Move> legalMoves = state.board.legalMovesAt(move.from);
            boolean isLegal = legalMoves.stream().anyMatch(m -> m.to.equals(move.to));
            if (!isLegal) return false;
            state.board = state.board.makeMove(move);
            state.turn = state.turn.opponent();
            state.moveHistory.add(uci);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // Checks if both sides have no pieces that can cross the river.
    private boolean noCrossingPieces(Board board) {
        boolean redHasCrossing = false;
        boolean blackHasCrossing = false;
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null) {
                    if (canPieceCrossRiver(piece, r, c)) {
                        if (piece.side == Side.RED) redHasCrossing = true;
                        else blackHasCrossing = true;
                    }
                    if (redHasCrossing && blackHasCrossing) return false;
                }
            }
        }
        return !redHasCrossing && !blackHasCrossing;
    }

    private boolean canPieceCrossRiver(Piece piece, int row, int col) {
        String type = piece.type.toString().toUpperCase();
        if (type.equals("ROOK") || type.equals("HORSE") || type.equals("CANNON")) {
            return true;
        }
        if (type.equals("PAWN")) {
            if (piece.side == Side.RED) return row >= 5;
            else return row <= 4;
        }
        return false;
    }

    // Records a temporary error during the game.
    private void recordEndgameTempError(EndgameAIBattleState state, String errorType) {
        state.tempErrorInfos.add(state.new ErrorInfo(state.currentRound, errorType));
        System.out.println("[Gemini Endgame AI] Recording temp error - Level " + state.level +
                " Round " + state.currentRound + ": " + errorType);
    }

    // Records the final game result.
    private void recordEndgameGameResult(EndgameAIBattleState state, String result) {
        state.createFinalErrorRecords();
        int aiMoveCount = state.getAIMoveCount();
        ErrorCsvRecorder.validateLevel(state.level);
        ErrorCsvRecorder.EndgameErrorRecord record =
                ErrorCsvRecorder.recordFinalEndgameResult(
                        state.currentRound,
                        result,
                        aiMoveCount,
                        state.level
                );
        if (record != null) {
            state.currentGameErrors.add(record);
        }
        System.out.println("[Gemini Endgame AI] Recording game result - Level " + state.level +
                " Round " + state.currentRound + ": " + result + " AI moves: " + aiMoveCount);
    }

    // Records a foul (illegal move).
    private void recordEndgameFoul(EndgameAIBattleState state, int round, String type, String player) {
        Map<String, Object> foulRecord = new HashMap<>();
        foulRecord.put("round", round);
        foulRecord.put("type", type);
        foulRecord.put("player", player);
        foulRecord.put("timestamp", System.currentTimeMillis());
        state.foulRecords.add(foulRecord);
        System.out.println("[Gemini Endgame AI] Recording foul - Level " + state.level +
                " Round " + round + " [" + player + "]: " + type);
    }

    // Saves endgame errors to CSV and marks as saved.
    private void saveEndgameErrorsToCSV(EndgameAIBattleState state) {
        if (state.currentGameErrors == null || state.currentGameErrors.isEmpty()) {
            System.out.println("[Gemini Endgame AI] No error records to save");
            return;
        }
        if (state.mode.contains("zeroshot") || "zeroshot".equals(state.mode)) {
            errorCsvRecorder.saveEndgameErrorsToCsv(
                    state.currentGameErrors,
                    ErrorCsvRecorder.RecordType.GEMINI_ZERO_SHOT_ENDGAME
            );
        } else if (state.mode.contains("cot") || "cot".equals(state.mode)) {
            errorCsvRecorder.saveEndgameErrorsToCsv(
                    state.currentGameErrors,
                    ErrorCsvRecorder.RecordType.GEMINI_COT_ENDGAME
            );
        } else {
            errorCsvRecorder.saveEndgameErrorsToCsv(
                    state.currentGameErrors,
                    ErrorCsvRecorder.RecordType.GEMINI_ZERO_SHOT_ENDGAME
            );
        }
        state.errorsSaved = true;
    }

    // Cancels the current battle and saves errors if not already saved.
    private void cancelCurrentBattle(String stateKey) {
        EndgameAIBattleState state = battleStates.get(stateKey);
        if (state != null) {
            if (!state.errorsSaved) {
                saveEndgameErrorsToCSV(state);
            }
        }
        battleStates.remove(stateKey);
        System.out.println("[Gemini Endgame AI] Cancelling battle: " + stateKey);
    }

    public List<Map<String, Object>> getFoulRecords(int level, String mode) {
        String stateKey = level + "-" + mode;
        EndgameAIBattleState state = battleStates.get(stateKey);
        if (state != null) {
            return state.foulRecords;
        }
        return new ArrayList<>();
    }

    public Map<String, Object> getCurrentBoardState(int level, String mode) {
        String stateKey = level + "-" + mode;
        EndgameAIBattleState state = battleStates.get(stateKey);
        if (state == null) return null;
        return serializeBoard(state.board);
    }

    private Map<String, Object> serializeBoard(Board board) {
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
        return boardState;
    }

    // Converts a Move to UCI string.
    private String coordToUci(Move m) {
        return "" + (char)('a' + m.from.c) + (char)('0' + m.from.r) +
                (char)('a' + m.to.c) + (char)('0' + m.to.r);
    }

    // Parses a UCI string into a Move.
    private Move parseUciMove(String uci) {
        if (uci == null || uci.length() < 4) return null;
        try {
            int fromC = uci.charAt(0) - 'a';
            int fromR = uci.charAt(1) - '0';
            int toC = uci.charAt(2) - 'a';
            int toR = uci.charAt(3) - '0';
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

    private String extractMoveFromAnswer(String reply) {
        if (reply == null) return null;
        String text = reply.trim();
        String[] tokens = text.split("\\s+");
        for (String t : tokens) {
            String candidate = t.trim();
            if (isValidMoveFormat(candidate)) return candidate;
        }
        for (int i = 0; i + 3 < text.length(); i++) {
            String sub = text.substring(i, i + 4);
            if (isValidMoveFormat(sub)) return sub;
        }
        return null;
    }

    private String extractMoveFromText(String text) {
        if (text == null || text.isBlank()) return null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("[a-i][0-9][a-i][0-9]");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    private String getPieceChineseName(Piece piece) {
        switch (piece.type.toString().toUpperCase()) {
            case "ROOK": return "车";
            case "HORSE": return "马";
            case "ELEPHANT": return "相";
            case "ADVISOR": return "士";
            case "GENERAL": return "帅";
            case "CANNON": return "炮";
            case "PAWN": return "兵";
            default: return piece.type.toString();
        }
    }

    // Converts board to FEN string for engine.
    private String boardToEngineFen(Board board, Side turn) {
        StringBuilder fen = new StringBuilder();
        for (int r = 9; r >= 0; r--) {
            int empty = 0;
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece == null) {
                    empty++;
                } else {
                    if (empty > 0) {
                        fen.append(empty);
                        empty = 0;
                    }
                    fen.append(getPieceChar(piece));
                }
            }
            if (empty > 0) fen.append(empty);
            if (r > 0) fen.append('/');
        }
        fen.append(' ').append(turn == Side.RED ? 'w' : 'b');
        return fen.toString();
    }

    private char getPieceChar(Piece piece) {
        char baseChar;
        switch (piece.type) {
            case ROOK: baseChar = 'r'; break;
            case HORSE: baseChar = 'n'; break;
            case ELEPHANT: baseChar = 'b'; break;
            case ADVISOR: baseChar = 'a'; break;
            case GENERAL: baseChar = 'k'; break;
            case CANNON: baseChar = 'c'; break;
            case PAWN: baseChar = 'p'; break;
            default: baseChar = '?';
        }
        return piece.side == Side.RED ? Character.toUpperCase(baseChar) : baseChar;
    }

    // Generates the prompt for the AI (Chinese part remains unchanged).
    private String generateEndgameBoardDescription(Board board, List<String> legalMoves, int level, String mode, EndgameAIBattleState state) {
        StringBuilder description = new StringBuilder();

        if (state.currentRound == 1) {
            description.append("中国象棋残局解题 - 关卡 ").append(level).append(":\n");
            description.append("你现在正在解象棋残局，目标是：\n");
            description.append("1. 用最少的步数将死对方的将/帅\n");
            description.append("2. 如果无法直接将死，设法让对方困毙（无子可动）\n");
            description.append("3. 注意：如果30回合内未分胜负，你会被判负！\n\n");
            description.append("将军（Check）：当你的帅被对方棋子攻击时，你处于“被将军”状态。此时你必须走一步来解除将军（移动帅、吃掉攻击的棋子，或用其他棋子挡住攻击）。如果你无法解除将军，则被将死（Checkmate），游戏结束。\n\n");
        }

        description.append("当前棋盘布局（红方在下方，黑方在上方）：\n");
        description.append(EndgameController.generateBoardVisualization(board));
        description.append("\n");

        description.append("当前你的棋子（红方）位置：\n");
        boolean hasRedPieces = false;
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null && piece.side == Side.RED) {
                    hasRedPieces = true;
                    String coord = (char)('a' + c) + String.valueOf(r);
                    description.append(getPieceChineseName(piece)).append(" 在 ").append(coord).append("\n");
                }
            }
        }
        if (!hasRedPieces) {
            description.append("（你没有任何棋子了！）\n");
        }
        description.append("\n");

        if (state.moveHistory != null && !state.moveHistory.isEmpty()) {
            description.append("历史移动（从第1回合开始，最多显示最近20回合）：\n");
            int totalMoves = state.moveHistory.size();
            int startIdx = Math.max(0, totalMoves - 40);
            for (int i = startIdx; i < totalMoves; i++) {
                int roundNumber = i / 2 + 1;
                String player = (i % 2 == 0) ? "红方(AI)" : "黑方(皮卡鱼)";
                description.append("第").append(roundNumber).append("回合 [").append(player).append("]: ").append(state.moveHistory.get(i)).append("\n");
            }
            description.append("\n");
        }

        description.append("回合: ").append(state.currentRound).append("\n");
        if (board.inCheck(Side.RED)) {
            description.append("警告：你正被将军！必须走一步解除将军。\n");
        }

        if ("cot".equals(mode)) {
            description.append("请进行思维链推理，分析当前局面，然后给出最佳走法。\n");
            description.append("格式要求：\n");
            description.append("Explanation: [你的推理]\n");
            description.append("Final answer: [4字符坐标，例如：a6a5]");
        } else {
            description.append("只返回移动坐标，如'a6a5'。");
        }

        return description.toString();
    }
}