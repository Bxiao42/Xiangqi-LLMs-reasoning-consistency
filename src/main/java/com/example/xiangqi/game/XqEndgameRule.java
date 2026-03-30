// XqEndgameRule.java
package com.example.xiangqi.game;

import java.util.*;

/**
 * Chinese chess rules for endgame handling.
 * Coordinate system:
 *   - columns a(0) to i(8) left to right
 *   - rows 0 (red baseline) to 9 (black baseline) bottom to top
 *   - red pieces start on rows 0-4, black on rows 5-9
 *   - red moves upward (+1 row), black moves downward (-1 row)
 */
public class XqEndgameRule {

    public enum Side {
        RED, BLACK;

        public Side opponent() {
            return this == RED ? BLACK : RED;
        }
    }

    public enum PieceType { GENERAL, ADVISOR, ELEPHANT, HORSE, ROOK, CANNON, PAWN }

    /** Position on board. */
    public static final class Pos {
        public final int r, c;
        public Pos(int r, int c) { this.r = r; this.c = c; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Pos p)) return false;
            return p.r == r && p.c == c;
        }
        @Override public int hashCode() { return Objects.hash(r, c); }
        @Override public String toString() { return "(" + r + "," + c + ")"; }
    }

    /** Move from one position to another. */
    public static final class Move {
        public final Pos from, to;
        public Move(Pos from, Pos to) { this.from = from; this.to = to; }
        @Override public String toString() { return from + "->" + to; }
    }

    /** Board representation. */
    public static final class Board {
        private final Piece[][] grid = new Piece[10][9];

        public boolean in(int r, int c) { return r >= 0 && r < 10 && c >= 0 && c < 9; }
        public Piece at(int r, int c) { return in(r, c) ? grid[r][c] : null; }
        public void set(int r, int c, Piece p) { if (in(r, c)) grid[r][c] = p; }

        /** Checks if (r,c) is inside the palace of the given side. */
        public boolean inPalace(Side s, int r, int c) {
            if (!in(r, c)) return false;
            if (s == Side.RED) return r >= 0 && r <= 2 && c >= 3 && c <= 5;
            else return r >= 7 && r <= 9 && c >= 3 && c <= 5;
        }

        /** Returns the position of the general of the given side, or null if not found. */
        public Pos findGeneral(Side s) {
            for (int r = 0; r < 10; r++)
                for (int c = 0; c < 9; c++) {
                    Piece p = grid[r][c];
                    if (p != null && p.type == PieceType.GENERAL && p.side == s)
                        return new Pos(r, c);
                }
            return null;
        }

        /** Checks whether the column between two rows (exclusive) is empty. */
        public boolean clearBetweenSameCol(int r1, int r2, int c) {
            int a = Math.min(r1, r2) + 1, b = Math.max(r1, r2) - 1;
            for (int r = a; r <= b; r++) if (grid[r][c] != null) return false;
            return true;
        }

        /** Checks if the two generals are facing each other (illegal position). */
        public boolean generalsFacing() {
            Pos r = findGeneral(Side.RED), k = findGeneral(Side.BLACK);
            if (r == null || k == null) return false;
            return r.c == k.c && clearBetweenSameCol(r.r, k.r, r.c);
        }

        /** Creates a new board by applying the given move (no legality check). */
        public Board makeMove(Move m) {
            Board nb = this.clone();
            Piece p = nb.at(m.from.r, m.from.c);
            nb.set(m.from.r, m.from.c, null);
            nb.set(m.to.r, m.to.c, p);
            if (p != null) {
                p.pos = new Pos(m.to.r, m.to.c);   // update piece position
            }
            return nb;
        }

        /** Checks whether the given side is in check. */
        public boolean inCheck(Side side) {
            Pos g = findGeneral(side);
            if (g == null) return false;
            for (int r = 0; r < 10; r++) {
                for (int c = 0; c < 9; c++) {
                    Piece q = grid[r][c];
                    if (q == null || q.side == side) continue;
                    for (Pos dst : q.pseudoLegalMoves(this)) {
                        if (dst.r == g.r && dst.c == g.c) return true;
                    }
                }
            }
            return generalsFacing();
        }

        /** Returns all legal moves (filtered for self‑check and face‑to‑face generals) from the given position. */
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

        /** Creates a deep copy of the board. */
        public Board clone() {
            Board b = new Board();
            for (int r = 0; r < 10; r++)
                for (int c = 0; c < 9; c++) {
                    Piece p = grid[r][c];
                    if (p == null) continue;
                    b.grid[r][c] = p.copy();
                }
            return b;
        }
    }

    /** Abstract base class for all pieces. */
    public static abstract class Piece {
        public final PieceType type;
        public final Side side;
        public Pos pos;                // current position (must be updated on move)

        protected Piece(PieceType type, Side side, Pos pos) {
            this.type = type;
            this.side = side;
            this.pos = pos;
        }

        /** Returns all pseudo‑legal destinations (without considering check or face‑to‑face). */
        public abstract List<Pos> pseudoLegalMoves(Board b);

        protected static final int[][] ORTHO = {{1,0},{-1,0},{0,1},{0,-1}};
        protected static final int[][] DIAG1 = {{1,1},{1,-1},{-1,1},{-1,-1}};

        protected boolean isOwn(Board b, int r, int c) {
            Piece q = b.at(r, c);
            return q != null && q.side == this.side;
        }
        protected boolean isEnemy(Board b, int r, int c) {
            Piece q = b.at(r, c);
            return q != null && q.side != this.side;
        }

        /** Creates a copy of the piece (used for board cloning). */
        public abstract Piece copy();
    }

    /** Rook (车). */
    public static final class Rook extends Piece {
        public Rook(Side s, Pos p) { super(PieceType.ROOK, s, p); }
        @Override public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            for (int[] d : ORTHO) {
                int r = pos.r + d[0], c = pos.c + d[1];
                while (b.in(r, c)) {
                    if (b.at(r, c) == null) out.add(new Pos(r, c));
                    else {
                        if (isEnemy(b, r, c)) out.add(new Pos(r, c));
                        break;
                    }
                    r += d[0]; c += d[1];
                }
            }
            return out;
        }
        @Override public Piece copy() { return new Rook(side, new Pos(pos.r, pos.c)); }
    }

    /** Horse (马). */
    public static final class Horse extends Piece {
        public Horse(Side s, Pos p) { super(PieceType.HORSE, s, p); }
        @Override public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            int[][] legs = {{-1,0},{1,0},{0,-1},{0,1}};
            int[][][] dsts = {
                    {{-2,-1},{-2,1}}, {{2,-1},{2,1}},
                    {{-1,-2},{1,-2}}, {{-1,2},{1,2}}
            };
            for (int i = 0; i < 4; i++) {
                int lr = pos.r + legs[i][0], lc = pos.c + legs[i][1];
                if (!b.in(lr, lc) || b.at(lr, lc) != null) continue;
                for (int[] d : dsts[i]) {
                    int r = pos.r + d[0], c = pos.c + d[1];
                    if (b.in(r, c) && !isOwn(b, r, c)) out.add(new Pos(r, c));
                }
            }
            return out;
        }
        @Override public Piece copy() { return new Horse(side, new Pos(pos.r, pos.c)); }
    }

    /** Elephant (相/象). */
    public static final class Elephant extends Piece {
        public Elephant(Side s, Pos p) { super(PieceType.ELEPHANT, s, p); }
        @Override public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            for (int[] d : DIAG1) {
                int r = pos.r + 2 * d[0], c = pos.c + 2 * d[1];
                int mr = pos.r + d[0], mc = pos.c + d[1];
                if (!b.in(r, c) || b.at(mr, mc) != null || isOwn(b, r, c)) continue;
                // cannot cross river
                if (side == Side.RED && r >= 5) continue;
                if (side == Side.BLACK && r <= 4) continue;
                out.add(new Pos(r, c));
            }
            return out;
        }
        @Override public Piece copy() { return new Elephant(side, new Pos(pos.r, pos.c)); }
    }

    /** Advisor (士). */
    public static final class Advisor extends Piece {
        public Advisor(Side s, Pos p) { super(PieceType.ADVISOR, s, p); }
        @Override public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            for (int[] d : DIAG1) {
                int r = pos.r + d[0], c = pos.c + d[1];
                if (!b.in(r, c) || isOwn(b, r, c)) continue;
                if (b.inPalace(side, r, c)) out.add(new Pos(r, c));
            }
            return out;
        }
        @Override public Piece copy() { return new Advisor(side, new Pos(pos.r, pos.c)); }
    }

    /** General (将/帅). */
    public static final class General extends Piece {
        public General(Side s, Pos p) { super(PieceType.GENERAL, s, p); }
        @Override public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            for (int[] d : ORTHO) {
                int r = pos.r + d[0], c = pos.c + d[1];
                if (!b.in(r, c) || isOwn(b, r, c)) continue;
                if (b.inPalace(side, r, c)) out.add(new Pos(r, c));
            }
            // The "facing generals" rule is handled in inCheck, not here.
            return out;
        }
        @Override public Piece copy() { return new General(side, new Pos(pos.r, pos.c)); }
    }

    /** Cannon (炮). */
    public static final class Cannon extends Piece {
        public Cannon(Side s, Pos p) { super(PieceType.CANNON, s, p); }
        @Override public List<Pos> pseudoLegalMoves(Board b) {
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
                    r += d[0]; c += d[1];
                }
            }
            return out;
        }
        @Override public Piece copy() { return new Cannon(side, new Pos(pos.r, pos.c)); }
    }

    /** Pawn (兵/卒). */
    public static final class Pawn extends Piece {
        public Pawn(Side s, Pos p) { super(PieceType.PAWN, s, p); }
        @Override public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            int dir = (side == Side.RED) ? 1 : -1;   // red moves up, black moves down
            int fr = pos.r + dir, fc = pos.c;
            if (b.in(fr, fc) && !isOwn(b, fr, fc)) out.add(new Pos(fr, fc));

            // can move sideways after crossing river
            boolean crossed = (side == Side.RED) ? pos.r >= 5 : pos.r <= 4;
            if (crossed) {
                int[][] lr = {{0, -1}, {0, 1}};
                for (int[] d : lr) {
                    int r = pos.r + d[0], c = pos.c + d[1];
                    if (b.in(r, c) && !isOwn(b, r, c)) out.add(new Pos(r, c));
                }
            }
            return out;
        }
        @Override public Piece copy() { return new Pawn(side, new Pos(pos.r, pos.c)); }
    }

    /**
     * Factory method: creates a piece from a type character and side/position.
     * @param typeChar one of K,A,B,N,R,C,P (N or H for horse)
     */
    public static Piece createPiece(String typeChar, Side side, int r, int c) {
        Pos pos = new Pos(r, c);
        switch (typeChar) {
            case "K": return new General(side, pos);
            case "A": return new Advisor(side, pos);
            case "B": return new Elephant(side, pos);
            case "N": case "H": return new Horse(side, pos);
            case "R": return new Rook(side, pos);
            case "C": return new Cannon(side, pos);
            case "P": return new Pawn(side, pos);
            default: return null;
        }
    }
}