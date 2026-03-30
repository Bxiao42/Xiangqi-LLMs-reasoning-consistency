// XqIndividualJuge.java
package com.example.xiangqi.game;

import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class XqIndividualJuge {

    /** Possible game results. */
    public enum GameResult {
        RED_WIN("Red wins"),
        BLACK_WIN("Black wins"),
        DRAW("Draw"),
        IN_PROGRESS("In progress"),
        MAX_ROUNDS_REACHED("Max rounds reached");

        private final String description;

        GameResult(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /** Advantage evaluation result. */
    public enum AdvantageResult {
        RED_ADVANTAGE("Red has advantage"),
        BLACK_ADVANTAGE("Black has advantage"),
        EVEN("Even"),
        RED_GREAT_ADVANTAGE("Red has great advantage"),
        BLACK_GREAT_ADVANTAGE("Black has great advantage");

        private final String description;

        AdvantageResult(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Checks the game state from the perspective of the side about to move.
     * If the current side has no legal moves, the opponent wins (including stalemate).
     */
    public static GameResult checkGameState(Board board, Side currentTurn) {
        if (hasNoLegalMoves(board, currentTurn)) {
            return currentTurn == Side.RED ? GameResult.BLACK_WIN : GameResult.RED_WIN;
        }
        if (isCheckmated(board, currentTurn)) {
            return currentTurn == Side.RED ? GameResult.BLACK_WIN : GameResult.RED_WIN;
        }
        if (isFaceToFaceGenerals(board)) {
            return GameResult.DRAW;
        }
        if (isInsufficientMaterial(board)) {
            return GameResult.DRAW;
        }
        return GameResult.IN_PROGRESS;
    }

    /**
     * Evaluates the board advantage based on material, piece positions, and threats.
     */
    public static AdvantageResult evaluateBoardAdvantage(Board board) {
        int redScore = 0;
        int blackScore = 0;

        // Basic piece values
        Map<PieceType, Integer> pieceValues = new HashMap<>();
        pieceValues.put(PieceType.ROOK, 900);
        pieceValues.put(PieceType.HORSE, 400);
        pieceValues.put(PieceType.CANNON, 450);
        pieceValues.put(PieceType.ELEPHANT, 200);
        pieceValues.put(PieceType.ADVISOR, 200);
        pieceValues.put(PieceType.GENERAL, 10000);
        pieceValues.put(PieceType.PAWN, 100);

        // Positional bonuses
        Map<String, Double> positionFactors = new HashMap<>();
        for (int r = 3; r <= 6; r++) {
            for (int c = 3; c <= 5; c++) {
                positionFactors.put(r + "," + c, 1.2);   // central area
            }
        }
        for (int r = 0; r <= 2; r++) {
            for (int c = 3; c <= 5; c++) {
                positionFactors.put(r + "," + c, 1.3);   // red palace (rows 0-2)
            }
        }
        for (int r = 7; r <= 9; r++) {
            for (int c = 3; c <= 5; c++) {
                positionFactors.put(r + "," + c, 1.3);   // black palace (rows 7-9)
            }
        }

        // Evaluate each piece
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null) {
                    Integer baseValue = pieceValues.get(piece.type);
                    if (baseValue != null) {
                        int pieceValue = baseValue;
                        String posKey = r + "," + c;
                        Double posFactor = positionFactors.get(posKey);
                        if (posFactor != null) {
                            pieceValue = (int)(pieceValue * posFactor);
                        }
                        // Pawn bonuses for crossing river and approaching palace
                        if (piece.type == PieceType.PAWN) {
                            if (piece.side == Side.RED && r >= 5) {
                                pieceValue += 50;
                            } else if (piece.side == Side.BLACK && r <= 4) {
                                pieceValue += 50;
                            }
                            if ((piece.side == Side.RED && r >= 7) ||
                                    (piece.side == Side.BLACK && r <= 2)) {
                                pieceValue += 30;
                            }
                        } else if (piece.type == PieceType.HORSE) {
                            if ((c >= 3 && c <= 5) && (r >= 3 && r <= 6)) {
                                pieceValue += 30;   // central horse bonus
                            }
                        } else if (piece.type == PieceType.ROOK) {
                            boolean openFile = true;
                            for (int checkR = 0; checkR < 10; checkR++) {
                                if (checkR != r && board.at(checkR, c) != null) {
                                    openFile = false;
                                    break;
                                }
                            }
                            if (openFile) pieceValue += 50;   // rook on open file
                        }
                        if (piece.side == Side.RED) {
                            redScore += pieceValue;
                        } else {
                            blackScore += pieceValue;
                        }
                    }
                }
            }
        }

        // Add control and threat scores
        int redControl = calculateControlScore(board, Side.RED);
        int blackControl = calculateControlScore(board, Side.BLACK);
        redScore += redControl;
        blackScore += blackControl;

        int redAttack = calculateAttackThreat(board, Side.RED);
        int blackAttack = calculateAttackThreat(board, Side.BLACK);
        redScore += redAttack;
        blackScore += blackAttack;

        int scoreDiff = redScore - blackScore;
        int totalScore = redScore + blackScore;
        double advantageThreshold = totalScore * 0.05;
        double greatAdvantageThreshold = totalScore * 0.15;

        if (scoreDiff > greatAdvantageThreshold) {
            return AdvantageResult.RED_GREAT_ADVANTAGE;
        } else if (scoreDiff > advantageThreshold) {
            return AdvantageResult.RED_ADVANTAGE;
        } else if (scoreDiff < -greatAdvantageThreshold) {
            return AdvantageResult.BLACK_GREAT_ADVANTAGE;
        } else if (scoreDiff < -advantageThreshold) {
            return AdvantageResult.BLACK_ADVANTAGE;
        } else {
            return AdvantageResult.EVEN;
        }
    }

    /** Calculates a score based on how many squares the side controls. */
    private static int calculateControlScore(Board board, Side side) {
        int controlScore = 0;
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null && piece.side == side) {
                    List<Move> moves = board.legalMovesAt(new Pos(r, c));
                    for (Move move : moves) {
                        if (move.to.r >= 3 && move.to.r <= 6 &&
                                move.to.c >= 3 && move.to.c <= 5) {
                            controlScore += 2;      // central control
                        } else {
                            controlScore += 1;
                        }
                        if ((side == Side.RED && move.to.r >= 5) ||
                                (side == Side.BLACK && move.to.r <= 4)) {
                            controlScore += 1;      // crossing river
                        }
                    }
                }
            }
        }
        return controlScore;
    }

    /** Calculates a score based on threats to the opponent's general. */
    private static int calculateAttackThreat(Board board, Side side) {
        int attackScore = 0;
        Side opponent = (side == Side.RED) ? Side.BLACK : Side.RED;
        Pos opponentGeneralPos = findGeneralPosition(board, opponent);
        if (opponentGeneralPos == null) {
            return 0;
        }
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null && piece.side == side) {
                    List<Move> moves = board.legalMovesAt(new Pos(r, c));
                    for (Move move : moves) {
                        if (move.to.equals(opponentGeneralPos)) {
                            attackScore += 50;               // direct check
                        }
                        int dr = Math.abs(move.to.r - opponentGeneralPos.r);
                        int dc = Math.abs(move.to.c - opponentGeneralPos.c);
                        if (dr <= 1 && dc <= 1) {
                            attackScore += 10;                // adjacent to general
                        } else if (dr <= 2 && dc <= 2) {
                            attackScore += 5;                 // within two steps
                        }
                    }
                }
            }
        }
        return attackScore;
    }

    /** Returns the position of the general of the given side, or null. */
    private static Pos findGeneralPosition(Board board, Side side) {
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null && piece.side == side &&
                        piece.type == PieceType.GENERAL) {
                    return new Pos(r, c);
                }
            }
        }
        return null;
    }

    /** Returns true if the side has no legal moves at all. */
    private static boolean hasNoLegalMoves(Board board, Side side) {
        return !hasLegalMoves(board, side);
    }

    /** Returns true if the side is checkmated. */
    private static boolean isCheckmated(Board board, Side side) {
        if (!board.inCheck(side)) {
            return false;
        }
        return !hasLegalMoves(board, side);
    }

    /** Returns true if the two generals face each other with no pieces in between. */
    private static boolean isFaceToFaceGenerals(Board board) {
        Pos redGeneralPos = null;
        Pos blackGeneralPos = null;
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null) {
                    String typeStr = piece.type.toString();
                    if ("GENERAL".equals(typeStr) || "KING".equals(typeStr) || "G".equals(typeStr) || "K".equals(typeStr)) {
                        if (piece.side == Side.RED) {
                            redGeneralPos = new Pos(r, c);
                        } else {
                            blackGeneralPos = new Pos(r, c);
                        }
                    }
                }
            }
        }
        if (redGeneralPos == null || blackGeneralPos == null) {
            return false;
        }
        if (redGeneralPos.c != blackGeneralPos.c) {
            return false;
        }
        int startRow = Math.min(redGeneralPos.r, blackGeneralPos.r) + 1;
        int endRow = Math.max(redGeneralPos.r, blackGeneralPos.r);
        for (int r = startRow; r < endRow; r++) {
            if (board.at(r, redGeneralPos.c) != null) {
                return false;
            }
        }
        return true;
    }

    /** Returns true if insufficient material (only two generals left). */
    private static boolean isInsufficientMaterial(Board board) {
        int redPieceCount = 0;
        int blackPieceCount = 0;
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null) {
                    String typeStr = piece.type.toString();
                    boolean isGeneral = "GENERAL".equals(typeStr) || "KING".equals(typeStr) || "G".equals(typeStr) || "K".equals(typeStr);
                    if (!isGeneral) {
                        if (piece.side == Side.RED) {
                            redPieceCount++;
                        } else {
                            blackPieceCount++;
                        }
                    }
                }
            }
        }
        return redPieceCount == 0 && blackPieceCount == 0;
    }

    /** Returns true if the side has at least one legal move. */
    private static boolean hasLegalMoves(Board board, Side side) {
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null && piece.side == side) {
                    List<Move> moves = board.legalMovesAt(new Pos(r, c));
                    if (!moves.isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // Deprecated CSV export methods – kept for compatibility
    public static String exportFoulsToCsv(List<String[]> foulRecords, String player, LocalDateTime endTime) {
        return null;
    }

    public static String exportAiAnalysisToCsv(List<Map<String, String>> analysisRecords, String player, LocalDateTime endTime) {
        return null;
    }

    /** Returns a human‑readable description of the game result. */
    public static String getResultDescription(GameResult result, Side playerSide) {
        switch (result) {
            case RED_WIN:
                return "Red wins!";
            case BLACK_WIN:
                return "Black wins!";
            case DRAW:
                return "Draw!";
            case MAX_ROUNDS_REACHED:
                return "Max rounds reached!";
            default:
                return "Game in progress";
        }
    }

    /** Checks if the given side is in check. */
    public static boolean isInCheck(Board board, Side side) {
        return board.inCheck(side);
    }

    /** Formats a board for display (used in UI, not critical). */
    private String getFormattedBoard(String[][] board) {
        StringBuilder sb = new StringBuilder();
        sb.append("  a b c d e f g h i\n");

        for (int uciRow = 9; uciRow >= 0; uciRow--) {
            sb.append(uciRow).append(" ");

            int internalRow = 9 - uciRow;

            for (int c = 0; c < 9; c++) {
                String piece = board[internalRow][c];
                if (piece == null || piece.isEmpty()) {
                    sb.append(" .");
                } else {
                    sb.append(" ").append(piece);
                }
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    // The following nested classes are identical to those in XqRules and XqEndgameRule
    // They are repeated here for standalone use.

    public static enum Side {
        RED(-1),
        BLACK(+1);
        public final int dir;

        Side(int d) {
            this.dir = d;
        }

        public Side opponent() {
            return this == RED ? BLACK : RED;
        }
    }

    public static enum PieceType {
        GENERAL, ADVISOR, ELEPHANT, HORSE, ROOK, CANNON, PAWN
    }

    public static final class Pos {
        public final int r, c;

        public Pos(int r, int c) {
            this.r = r;
            this.c = c;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Pos p)) return false;
            return p.r == r && p.c == c;
        }

        @Override
        public int hashCode() {
            return Objects.hash(r, c);
        }

        @Override
        public String toString() {
            return "(" + r + "," + c + ")";
        }
    }

    public static final class Move {
        public final Pos from, to;

        public Move(Pos from, Pos to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public String toString() {
            return from + "->" + to;
        }
    }

    public static abstract class Piece {
        public final PieceType type;
        public final Side side;
        public Pos pos;

        protected Piece(PieceType type, Side side, Pos pos) {
            this.type = type;
            this.side = side;
            this.pos = pos;
        }

        public abstract List<Pos> pseudoLegalMoves(Board b);

        protected static final int[][] ORTHO = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        protected static final int[][] DIAG1 = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

        protected boolean isOwn(Board b, int r, int c) {
            Piece q = b.at(r, c);
            return q != null && q.side == this.side;
        }

        protected boolean isEnemy(Board b, int r, int c) {
            Piece q = b.at(r, c);
            return q != null && q.side != this.side;
        }
    }

    public static final class Rook extends Piece {
        public Rook(Side s, Pos p) {
            super(PieceType.ROOK, s, p);
        }

        @Override
        public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            for (int[] d : ORTHO) {
                int r = pos.r + d[0], c = pos.c + d[1];
                while (b.in(r, c)) {
                    if (b.at(r, c) == null) out.add(new Pos(r, c));
                    else {
                        if (isEnemy(b, r, c)) out.add(new Pos(r, c));
                        break;
                    }
                    r += d[0];
                    c += d[1];
                }
            }
            return out;
        }
    }

    public static final class Cannon extends Piece {
        public Cannon(Side s, Pos p) {
            super(PieceType.CANNON, s, p);
        }

        @Override
        public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            for (int[] d : ORTHO) {
                int r = pos.r + d[0], c = pos.c + d[1];
                boolean jumped = false;
                while (b.in(r, c)) {
                    Piece q = b.at(r, c);
                    if (!jumped) {
                        if (q == null) out.add(new Pos(r, c));
                        else jumped = true;
                    } else {
                        if (q != null) {
                            if (q.side != this.side) out.add(new Pos(r, c));
                            break;
                        }
                    }
                    r += d[0];
                    c += d[1];
                }
            }
            return out;
        }
    }

    public static final class Horse extends Piece {
        public Horse(Side s, Pos p) {
            super(PieceType.HORSE, s, p);
        }

        @Override
        public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            int[][] legs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
            int[][][] dsts = {
                    {{-2, -1}, {-2, 1}}, {{2, -1}, {2, 1}}, {{-1, -2}, {1, -2}}, {{-1, 2}, {1, 2}}
            };
            for (int i = 0; i < 4; i++) {
                int lr = pos.r + legs[i][0], lc = pos.c + legs[i][1];
                if (!b.in(lr, lc) || b.at(lr, lc) != null) continue;
                for (int[] d : dsts[i]) {
                    int r = pos.r + d[0], c = pos.c + d[1];
                    if (!b.in(r, c) || isOwn(b, r, c)) continue;
                    out.add(new Pos(r, c));
                }
            }
            return out;
        }
    }

    public static final class Elephant extends Piece {
        public Elephant(Side s, Pos p) {
            super(PieceType.ELEPHANT, s, p);
        }

        @Override
        public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            for (int[] d : DIAG1) {
                int r = pos.r + 2 * d[0], c = pos.c + 2 * d[1];
                int mr = pos.r + d[0], mc = pos.c + d[1];
                if (!b.in(r, c) || b.at(mr, mc) != null || isOwn(b, r, c)) continue;
                if (side == Side.RED && r <= 4) continue;
                if (side == Side.BLACK && r >= 5) continue;
                out.add(new Pos(r, c));
            }
            return out;
        }
    }

    public static final class Advisor extends Piece {
        public Advisor(Side s, Pos p) {
            super(PieceType.ADVISOR, s, p);
        }

        @Override
        public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            for (int[] d : DIAG1) {
                int r = pos.r + d[0], c = pos.c + d[1];
                if (!b.in(r, c) || isOwn(b, r, c)) continue;
                if (b.inPalace(side, r, c)) out.add(new Pos(r, c));
            }
            return out;
        }
    }

    public static final class General extends Piece {
        public General(Side s, Pos p) {
            super(PieceType.GENERAL, s, p);
        }

        @Override
        public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            for (int[] d : ORTHO) {
                int r = pos.r + d[0], c = pos.c + d[1];
                if (!b.in(r, c) || isOwn(b, r, c)) continue;
                if (b.inPalace(side, r, c)) out.add(new Pos(r, c));
            }
            Pos opp = b.findGeneral(side.opponent());
            if (opp != null && opp.c == pos.c) {
                if (b.clearBetweenSameCol(pos.r, opp.r, pos.c)) {
                    out.add(new Pos(opp.r, opp.c));
                }
            }
            return out;
        }
    }

    public static final class Pawn extends Piece {
        public Pawn(Side s, Pos p) {
            super(PieceType.PAWN, s, p);
        }

        @Override
        public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            int fr = pos.r + side.dir, fc = pos.c;
            if (b.in(fr, fc) && !isOwn(b, fr, fc)) out.add(new Pos(fr, fc));
            boolean crossed = (side == Side.RED ? pos.r <= 4 : pos.r >= 5);
            if (crossed) {
                int[][] lr = {{0, -1}, {0, 1}};
                for (int[] d : lr) {
                    int r = pos.r, c = pos.c + d[1];
                    if (b.in(r, c) && !isOwn(b, r, c)) out.add(new Pos(r, c));
                }
            }
            return out;
        }
    }

    public static final class Board {
        private final Piece[][] grid = new Piece[10][9];

        public boolean in(int r, int c) {
            return r >= 0 && r < 10 && c >= 0 && c < 9;
        }

        public Piece at(int r, int c) {
            return in(r, c) ? grid[r][c] : null;
        }

        public void set(int r, int c, Piece p) {
            if (in(r, c)) {
                grid[r][c] = p;
                if (p != null) p.pos = new Pos(r, c);
            }
        }

        public static Board initial() {
            Board b = new Board();
            // Black side (top)
            b.set(0, 0, new Rook(Side.BLACK, new Pos(0, 0)));
            b.set(0, 1, new Horse(Side.BLACK, new Pos(0, 1)));
            b.set(0, 2, new Elephant(Side.BLACK, new Pos(0, 2)));
            b.set(0, 3, new Advisor(Side.BLACK, new Pos(0, 3)));
            b.set(0, 4, new General(Side.BLACK, new Pos(0, 4)));
            b.set(0, 5, new Advisor(Side.BLACK, new Pos(0, 5)));
            b.set(0, 6, new Elephant(Side.BLACK, new Pos(0, 6)));
            b.set(0, 7, new Horse(Side.BLACK, new Pos(0, 7)));
            b.set(0, 8, new Rook(Side.BLACK, new Pos(0, 8)));
            b.set(2, 1, new Cannon(Side.BLACK, new Pos(2, 1)));
            b.set(2, 7, new Cannon(Side.BLACK, new Pos(2, 7)));
            for (int c = 0; c < 9; c += 2) b.set(3, c, new Pawn(Side.BLACK, new Pos(3, c)));

            // Red side (bottom)
            b.set(9, 0, new Rook(Side.RED, new Pos(9, 0)));
            b.set(9, 1, new Horse(Side.RED, new Pos(9, 1)));
            b.set(9, 2, new Elephant(Side.RED, new Pos(9, 2)));
            b.set(9, 3, new Advisor(Side.RED, new Pos(9, 3)));
            b.set(9, 4, new General(Side.RED, new Pos(9, 4)));
            b.set(9, 5, new Advisor(Side.RED, new Pos(9, 5)));
            b.set(9, 6, new Elephant(Side.RED, new Pos(9, 6)));
            b.set(9, 7, new Horse(Side.RED, new Pos(9, 7)));
            b.set(9, 8, new Rook(Side.RED, new Pos(9, 8)));
            b.set(7, 1, new Cannon(Side.RED, new Pos(7, 1)));
            b.set(7, 7, new Cannon(Side.RED, new Pos(7, 7)));
            for (int c = 0; c < 9; c += 2) b.set(6, c, new Pawn(Side.RED, new Pos(6, c)));
            return b;
        }

        public boolean inPalace(Side s, int r, int c) {
            if (!in(r, c)) return false;
            if (s == Side.RED) return r >= 7 && r <= 9 && c >= 3 && c <= 5;
            else return r >= 0 && r <= 2 && c >= 3 && c <= 5;
        }

        public Pos findGeneral(Side s) {
            for (int r = 0; r < 10; r++)
                for (int c = 0; c < 9; c++) {
                    Piece p = grid[r][c];
                    if (p != null && p.type == PieceType.GENERAL && p.side == s) return new Pos(r, c);
                }
            return null;
        }

        public boolean clearBetweenSameCol(int r1, int r2, int c) {
            int a = Math.min(r1, r2) + 1, b = Math.max(r1, r2) - 1;
            for (int r = a; r <= b; r++) if (grid[r][c] != null) return false;
            return true;
        }

        public boolean generalsFacing() {
            Pos r = findGeneral(Side.RED), k = findGeneral(Side.BLACK);
            if (r == null || k == null) return false;
            if (r.c != k.c) return false;
            return clearBetweenSameCol(r.r, k.r, r.c);
        }

        public Board makeMove(Move m) {
            Board nb = this.cloneBoard();
            Piece p = nb.at(m.from.r, m.from.c);
            nb.set(m.from.r, m.from.c, null);
            nb.set(m.to.r, m.to.c, p);
            return nb;
        }

        public boolean inCheck(Side side) {
            Pos g = findGeneral(side);
            if (g == null) return false;
            for (int r = 0; r < 10; r++)
                for (int c = 0; c < 9; c++) {
                    Piece q = grid[r][c];
                    if (q == null || q.side == side) continue;
                    for (Pos dst : q.pseudoLegalMoves(this)) {
                        if (dst.r == g.r && dst.c == g.c) return true;
                    }
                }
            return generalsFacing();
        }

        public List<Move> legalMovesAt(Pos from) {
            Piece p = at(from.r, from.c);
            if (p == null) return Collections.emptyList();
            List<Move> out = new ArrayList<>();
            for (Pos to : p.pseudoLegalMoves(this)) {
                Board nb = makeMove(new Move(from, to));
                if (nb.generalsFacing()) continue;
                if (nb.inCheck(p.side)) continue;
                out.add(new Move(from, to));
            }
            return out;
        }

        public Board cloneBoard() {
            Board b = new Board();
            for (int r = 0; r < 10; r++)
                for (int c = 0; c < 9; c++) {
                    Piece p = grid[r][c];
                    if (p == null) {
                        b.grid[r][c] = null;
                        continue;
                    }
                    Piece np;
                    switch (p.type) {
                        case ROOK -> np = new Rook(p.side, new Pos(r, c));
                        case CANNON -> np = new Cannon(p.side, new Pos(r, c));
                        case HORSE -> np = new Horse(p.side, new Pos(r, c));
                        case ELEPHANT -> np = new Elephant(p.side, new Pos(r, c));
                        case ADVISOR -> np = new Advisor(p.side, new Pos(r, c));
                        case GENERAL -> np = new General(p.side, new Pos(r, c));
                        case PAWN -> np = new Pawn(p.side, new Pos(r, c));
                        default -> throw new IllegalStateException();
                    }
                    b.grid[r][c] = np;
                }
            return b;
        }
    }
}