// AIBoardDescriber.java
package com.example.xiangqi.llm;

import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;

@Component
public class AIBoardDescriber {

    // Mapping from piece code to Chinese name
    private static final Map<String, String> PIECE_NAMES = new HashMap<>();

    static {
        // Red pieces (uppercase)
        PIECE_NAMES.put("R", "车");
        PIECE_NAMES.put("H", "马");
        PIECE_NAMES.put("E", "相");
        PIECE_NAMES.put("A", "士");
        PIECE_NAMES.put("K", "帅");
        PIECE_NAMES.put("C", "炮");
        PIECE_NAMES.put("P", "兵");

        // Black pieces (lowercase)
        PIECE_NAMES.put("r", "车");
        PIECE_NAMES.put("h", "马");
        PIECE_NAMES.put("e", "象");
        PIECE_NAMES.put("a", "士");
        PIECE_NAMES.put("k", "将");
        PIECE_NAMES.put("c", "炮");
        PIECE_NAMES.put("p", "卒");
    }

    /**
     * AI-specific board description – simplified version.
     * Tells current side, piece representation rules, board coordinates,
     * lists all pieces, and finally prints a visual board.
     */
    public String describeBoardForAI(String[][] board, String currentPlayer, String aiModelName) {
        StringBuilder sb = new StringBuilder();

        if ("RED".equalsIgnoreCase(currentPlayer)) {
            sb.append("你正在为红方下棋。\n");
            sb.append("红方棋子为大写字母：R(车)、H(马)、E(相)、A(士)、K(帅)、C(炮)、P(兵)\n");
            sb.append("当前棋盘上的棋子位置：\n");
            sb.append("红方：\n");
            for (int r = 0; r < 10; r++) {
                for (int c = 0; c < 9; c++) {
                    String piece = board[r][c];
                    if (piece != null && !piece.isEmpty()) {
                        char firstChar = piece.charAt(0);
                        if (Character.isUpperCase(firstChar)) { // Red piece
                            String uci = toUci(r, c);
                            sb.append(piece).append(": ").append(uci).append("\n");
                        }
                    }
                }
            }
        } else if ("BLACK".equalsIgnoreCase(currentPlayer)) {
            sb.append("你正在为黑方下棋。\n");
            sb.append("黑方棋子为小写字母：r(车)、h(马)、e(象)、a(士)、k(将)、c(炮)、p(卒)\n");
            sb.append("当前棋盘上的棋子位置：\n");
            sb.append("黑方：\n");
            for (int r = 0; r < 10; r++) {
                for (int c = 0; c < 9; c++) {
                    String piece = board[r][c];
                    if (piece != null && !piece.isEmpty()) {
                        char firstChar = piece.charAt(0);
                        if (Character.isLowerCase(firstChar)) { // Black piece
                            String uci = toUci(r, c);
                            sb.append(piece).append(": ").append(uci).append("\n");
                        }
                    }
                }
            }
        }
        sb.append("棋盘共10行（行号0-9），9列（a-i）。\n\n");

        sb.append(getFormattedBoard(board));

        return sb.toString();
    }

    /**
     * Returns a formatted board representation with coordinates.
     * Output follows UCI convention: row 0 at bottom (red baseline), row 9 at top (black baseline).
     */
    public String getFormattedBoard(String[][] board) {
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

    /**
     * Converts internal coordinates (r from 0 top to 9 bottom) to UCI string (e.g. a0, h5).
     */
    private String toUci(int r, int c) {
        char col = (char) ('a' + c);
        int row = 9 - r; // UCI row number
        return "" + col + row;
    }

    /**
     * Returns the Chinese name of a piece given its code.
     */
    public String getPieceName(String pieceCode) {
        return PIECE_NAMES.getOrDefault(pieceCode, "未知");
    }

    /**
     * Determines which side a piece belongs to.
     */
    public String getPieceSide(String pieceCode) {
        if (pieceCode == null || pieceCode.isEmpty()) {
            return "空";
        }

        char firstChar = pieceCode.charAt(0);
        if (Character.isUpperCase(firstChar)) {
            return "红方";
        } else if (Character.isLowerCase(firstChar)) {
            return "黑方";
        } else {
            return "未知";
        }
    }
}