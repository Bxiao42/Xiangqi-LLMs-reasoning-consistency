// GameExecutor.java
package com.example.xiangqi.game;

import com.example.xiangqi.game.XqRules.*;
import com.example.xiangqi.llm.DeepseekClient;
import com.example.xiangqi.llm.OpenAIClient;
import com.example.xiangqi.llm.GeminiClient;
import com.example.xiangqi.engine.EngineService;
import com.example.xiangqi.llm.ErrorCsvRecorder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class GameExecutor {

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

    /**
     * Executes a single game between AI (Red) and Pikafish engine (Black).
     */
    public Map<String, Object> executeSingleGame(String aiType, String mode, int gameNumber) {
        Map<String, Object> gameResult = new HashMap<>();
        gameResult.put("gameNumber", gameNumber);
        gameResult.put("startTime", new Date());
        gameResult.put("aiType", aiType);
        gameResult.put("mode", mode);

        Board board = Board.initial();                 // initial board state
        List<String> moveHistory = new ArrayList<>();  // UCI move list
        boolean gameOver = false;
        String winner = null;
        String result = null;
        int round = 0;

        String gameId = UUID.randomUUID().toString();  // unique game ID for AI calls

        try {
            while (!gameOver && round < 200) {         // max 200 rounds to avoid infinite loop
                round++;

                // ----- AI (Red) move -----
                String aiMove = playAIMove(board, aiType, mode, gameId, round, moveHistory);

                if (aiMove == null) {                  // AI cannot move -> Black wins
                    gameOver = true;
                    winner = "BLACK";
                    result = "pikafish win - " + aiType + " cannot continue";
                    break;
                }

                Move move = parseUciMove(aiMove);
                if (move != null) {
                    board = board.makeMove(move);
                    moveHistory.add(aiMove);
                } else {                                // invalid AI move -> Black wins
                    gameOver = true;
                    winner = "BLACK";
                    result = "pikafish win - invalid AI move";
                    break;
                }

                // Check if Black is checkmated after AI move
                if (isCheckmated(Side.BLACK, board)) {
                    gameOver = true;
                    winner = "RED";
                    result = aiType + " win - checkmate";
                    break;
                }

                // ----- Pikafish (Black) move -----
                String pikafishMove = playPikafishMove(board, moveHistory);

                if (pikafishMove == null) {            // Pikafish cannot move -> Red wins
                    gameOver = true;
                    winner = "RED";
                    result = aiType + " win - pikafish cannot continue";
                    break;
                }

                move = parseUciMove(pikafishMove);
                if (move != null) {
                    board = board.makeMove(move);
                    moveHistory.add(pikafishMove);
                } else {                                // invalid Pikafish move -> Red wins
                    gameOver = true;
                    winner = "RED";
                    result = aiType + " win - invalid pikafish move";
                    break;
                }

                // Check if Red is checkmated after Pikafish move
                if (isCheckmated(Side.RED, board)) {
                    gameOver = true;
                    winner = "BLACK";
                    result = "pikafish win - checkmate";
                    break;
                }

                // Check stalemate for Red
                if (isStalemated(Side.RED, board)) {
                    gameOver = true;
                    winner = "BLACK";
                    result = "pikafish win - stalemate";
                    break;
                }

                // 30‑round rule: draw if no pawn has crossed river
                if (round >= 30 && !hasCrossedRiverPieces(board)) {
                    gameOver = true;
                    winner = "draw";
                    result = "draw - 30回合内未分胜负"; // keep Chinese for AI prompt
                    break;
                }
            }

            if (!gameOver && round >= 200) {            // max rounds reached -> draw
                gameOver = true;
                winner = "draw";
                result = "draw - 达到最大回合数";      // keep Chinese for AI prompt
            }

        } catch (Exception e) {
            gameOver = true;
            winner = "error";
            result = "error - " + e.getMessage();
            gameResult.put("error", e.getMessage());
        }

        gameResult.put("endTime", new Date());
        gameResult.put("rounds", round);
        gameResult.put("winner", winner);
        gameResult.put("result", result);
        gameResult.put("moveHistory", moveHistory);

        return gameResult;
    }

    /**
     * Obtains a move from the specified AI (Red side).
     */
    private String playAIMove(Board board, String aiType, String mode, String gameId, int round, List<String> moveHistory) {
        List<String> legalMoves = getAllLegalMoves(board, Side.RED);   // all legal UCI moves for Red

        if (legalMoves.isEmpty()) {
            return null;            // no legal moves -> AI loses
        }

        String boardDescription = generateBoardDescription(board);     // textual board for AI prompt

        try {
            String aiMove = null;

            if ("cot".equalsIgnoreCase(mode)) {
                // CoT mode (same as zero‑shot for now)
                aiMove = getZeroShotAIMove(aiType, boardDescription, legalMoves, gameId, round);
            } else {
                // Zero‑shot mode
                aiMove = getZeroShotAIMove(aiType, boardDescription, legalMoves, gameId, round);
            }

            // Validate the returned move
            if (aiMove != null && legalMoves.contains(aiMove)) {
                return aiMove;
            } else {
                // If illegal, pick a random legal move as fallback
                if (!legalMoves.isEmpty()) {
                    String randomMove = legalMoves.get(new Random().nextInt(legalMoves.size()));
                    System.err.println("Warning: AI returned illegal move, using random move: " + randomMove);
                    return randomMove;
                }
            }

        } catch (Exception e) {
            System.err.println("AI move error: " + e.getMessage());
            if (!legalMoves.isEmpty()) {
                return legalMoves.get(new Random().nextInt(legalMoves.size()));
            }
        }

        return null;
    }

    /**
     * Calls the appropriate AI client for a zero‑shot move.
     */
    private String getZeroShotAIMove(String aiType, String boardDescription, List<String> legalMoves, String gameId, int round) {
        String lowerAIType = aiType.toLowerCase();

        if (lowerAIType.contains("openai")) {
            return openAIClient.getXiangqiMove(gameId, boardDescription, "RED", legalMoves, round);
        } else if (lowerAIType.contains("gemini")) {
            return geminiClient.getXiangqiMove(gameId, boardDescription, "RED", legalMoves, round);
        } else {
            // default to DeepSeek
            return deepseekClient.getXiangqiMove(gameId, boardDescription, "RED", legalMoves, round);
        }
    }

    /**
     * Obtains a move from the Pikafish engine (Black side).
     */
    private String playPikafishMove(Board board, List<String> moveHistory) {
        List<String> legalMoves = getAllLegalMoves(board, Side.BLACK); // all legal UCI moves for Black

        if (legalMoves.isEmpty()) {
            return null;            // no legal moves -> Pikafish loses
        }

        try {
            String bestMove = engineService.bestMove(null, moveHistory, false);  // get engine's best move

            if (bestMove != null && legalMoves.contains(bestMove)) {
                return bestMove;
            }

            // Fallback: random move if engine returns illegal move
            if (!legalMoves.isEmpty()) {
                String randomMove = legalMoves.get(new Random().nextInt(legalMoves.size()));
                System.err.println("Warning: Engine returned illegal move, using random move: " + randomMove);
                return randomMove;
            }

        } catch (Exception e) {
            System.err.println("Pikafish move error: " + e.getMessage());
            if (!legalMoves.isEmpty()) {
                return legalMoves.get(new Random().nextInt(legalMoves.size()));
            }
        }

        return null;
    }

    /**
     * Returns all legal UCI moves for a given side.
     */
    private List<String> getAllLegalMoves(Board board, Side side) {
        List<String> legalMoves = new ArrayList<>();
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null && piece.side == side) {
                    List<Move> moves = board.legalMovesAt(new Pos(r, c));
                    if (moves != null) {
                        for (Move move : moves) {
                            String uciMove = coordToUci(move);
                            legalMoves.add(uciMove);
                        }
                    }
                }
            }
        }
        return legalMoves;
    }

    /**
     * Converts internal Move to UCI string (e.g., "a1b1").
     */
    private String coordToUci(Move m) {
        return "" + (char)('a' + m.from.c) + (9 - m.from.r) +
                (char)('a' + m.to.c) + (9 - m.to.r);
    }

    /**
     * Parses a UCI string into a Move object. Returns null if invalid.
     */
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

    /**
     * Checks if the given side is checkmated.
     */
    private boolean isCheckmated(Side side, Board board) {
        if (!board.inCheck(side)) {
            return false;
        }
        return !hasLegalMovesThatEscapeCheck(side, board);
    }

    /**
     * Returns true if there exists at least one legal move for 'side' that removes the check.
     */
    private boolean hasLegalMovesThatEscapeCheck(Side side, Board board) {
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null && piece.side == side) {
                    List<Move> moves = board.legalMovesAt(new Pos(r, c));
                    if (moves != null) {
                        for (Move move : moves) {
                            Board newBoard = board.makeMove(move);
                            if (!newBoard.inCheck(side)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Checks if the given side is stalemated (no legal moves but not in check).
     */
    private boolean isStalemated(Side side, Board board) {
        if (board.inCheck(side)) {
            return false;
        }
        return getAllLegalMoves(board, side).isEmpty();
    }

    /**
     * Determines whether any pawn has crossed the river.
     * Used for the 30‑round draw rule.
     */
    private boolean hasCrossedRiverPieces(Board board) {
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null) {
                    if (piece.type == PieceType.PAWN) {
                        if (piece.side == Side.RED && r >= 5) {
                            return true;   // red pawn crossed river
                        }
                        if (piece.side == Side.BLACK && r <= 4) {
                            return true;   // black pawn crossed river
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Generates a Chinese textual description of the board for the AI prompt.
     */
    private String generateBoardDescription(Board board) {
        StringBuilder sb = new StringBuilder();
        sb.append("中国象棋棋盘状态：\n");
        sb.append("棋盘坐标：列(a-i)，行(0-9)，红方在下方，黑方在上方\n\n");

        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null) {
                    char colChar = (char) ('a' + c);
                    String position = colChar + String.valueOf(9 - r);
                    String side = piece.side == Side.RED ? "红方" : "黑方";
                    String pieceName = getPieceChineseName(piece);
                    sb.append(String.format("- %s %s 在 %s\n", side, pieceName, position));
                }
            }
        }

        return sb.toString();
    }

    /**
     * Returns the Chinese name of a piece.
     */
    private String getPieceChineseName(Piece piece) {
        if (piece.type == null) return "未知";

        String typeStr = piece.type.toString().toUpperCase();
        switch (typeStr) {
            case "ROOK": return "车";
            case "HORSE": return "马";
            case "ELEPHANT": return "相";
            case "ADVISOR": return "士";
            case "GENERAL": return "帅";
            case "CANNON": return "炮";
            case "PAWN": return "兵";
            default: return typeStr;
        }
    }
}