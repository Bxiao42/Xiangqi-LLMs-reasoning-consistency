// XqEndgameJudge.java
package com.example.xiangqi.game;

import com.example.xiangqi.game.XqEndgameRule.*;
import java.util.List;

/**
 * Judge for endgame scenarios.
 */
public class XqEndgameJudge {

    /**
     * Represents a piece placement in an endgame configuration.
     */
    public static class PiecePlacement {
        public String type;   // piece type character (K,A,B,N,R,C,P)
        public Side side;
        public int r, c;
        public PiecePlacement() {}
        public PiecePlacement(String type, Side side, int r, int c) {
            this.type = type; this.side = side; this.r = r; this.c = c;
        }
    }

    /**
     * Possible game outcomes.
     */
    public enum GameResult {
        RED_WIN("Red wins"), BLACK_WIN("Black wins"), DRAW("Draw"), IN_PROGRESS("In progress");
        private final String desc;
        GameResult(String desc) { this.desc = desc; }
        public String getDescription() { return desc; }
    }

    /**
     * Creates a Board from an endgame configuration (list of pieces).
     */
    public static Board createBoardFromConfig(int level, Side startingSide, List<PiecePlacement> pieces) {
        Board board = new Board();
        for (PiecePlacement p : pieces) {
            Piece piece = XqEndgameRule.createPiece(p.type, p.side, p.r, p.c);
            if (piece != null) board.set(p.r, p.c, piece);
        }
        return board;
    }

    /**
     * Checks the game state (win/loss/draw) after the current side has moved.
     */
    public static GameResult checkGameState(Board board, Side currentTurn) {
        if (isCheckmated(board, currentTurn)) {
            return currentTurn == Side.RED ? GameResult.BLACK_WIN : GameResult.RED_WIN;
        }
        if (isStalemate(board, currentTurn)) {
            return currentTurn == Side.RED ? GameResult.BLACK_WIN : GameResult.RED_WIN;
        }
        return GameResult.IN_PROGRESS;
    }

    /**
     * Returns true if the given side is checkmated.
     */
    public static boolean isCheckmated(Board board, Side side) {
        if (!board.inCheck(side)) return false;
        return !hasLegalMoves(board, side);
    }

    /**
     * Returns true if the given side is stalemated.
     */
    public static boolean isStalemate(Board board, Side side) {
        if (board.inCheck(side)) return false;
        return !hasLegalMoves(board, side);
    }

    /**
     * Returns true if the side has at least one legal move.
     */
    public static boolean hasLegalMoves(Board board, Side side) {
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece p = board.at(r, c);
                if (p != null && p.side == side) {
                    if (!board.legalMovesAt(new Pos(r, c)).isEmpty()) return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns a human‑readable description of the game result for the player.
     */
    public static String getResultDescription(GameResult result, Side playerSide) {
        switch (result) {
            case RED_WIN: return playerSide == Side.RED ? "Congratulations! You checkmated the AI!" : "AI checkmated you!";
            case BLACK_WIN: return playerSide == Side.BLACK ? "Congratulations! You checkmated the AI!" : "AI checkmated you!";
            case DRAW: return "Stalemate!";
            default: return "Game in progress";
        }
    }
}