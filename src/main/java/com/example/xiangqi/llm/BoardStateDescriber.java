// BoardStateDescriber.java
package com.example.xiangqi.llm;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class BoardStateDescriber {

    /**
     * Converts the board state to a textual description for DeepSeek understanding.
     */
    public String describeBoard(String[][] board, String currentPlayer) {
        StringBuilder sb = new StringBuilder();

        sb.append("棋盘坐标系统（列a-i，行0-9）：\n");
        sb.append("  a b c d e f g h i\n");

        // Mapping from piece code to Chinese name
        Map<String, String> pieceNames = new HashMap<>();
        pieceNames.put("R", "车");
        pieceNames.put("H", "马");
        pieceNames.put("E", "相");
        pieceNames.put("A", "士");
        pieceNames.put("K", "帅");
        pieceNames.put("C", "炮");
        pieceNames.put("P", "兵");
        pieceNames.put("r", "车");
        pieceNames.put("h", "马");
        pieceNames.put("e", "象");
        pieceNames.put("a", "士");
        pieceNames.put("k", "将");
        pieceNames.put("c", "炮");
        pieceNames.put("p", "卒");

        // Describe red pieces
        sb.append("\n红方棋子（大写，在下方）：\n");
        describePiecesBySide(sb, board, true, pieceNames);

        // Describe black pieces
        sb.append("\n黑方棋子（小写，在上方）：\n");
        describePiecesBySide(sb, board, false, pieceNames);

        // Add special area descriptions
        sb.append("\n特殊区域：\n");
        sb.append("- 红方九宫：d9-f9, d8-f8, d7-f7\n");
        sb.append("- 黑方九宫：d0-f0, d1-f1, d2-f2\n");
        sb.append("- 楚河汉界：行4和行5之间\n");

        return sb.toString();
    }

    private void describePiecesBySide(StringBuilder sb, String[][] board, boolean isRed, Map<String, String> pieceNames) {
        List<String> pieces = new ArrayList<>();

        for (int r = 0; r < board.length; r++) {
            for (int c = 0; c < board[r].length; c++) {
                String piece = board[r][c];
                if (piece != null) {
                    boolean pieceIsRed = Character.isUpperCase(piece.charAt(0));
                    if (pieceIsRed == isRed) {
                        char colChar = (char) ('a' + c);
                        String pieceName = pieceNames.get(piece);
                        if (pieceName != null) {
                            pieces.add(pieceName + "(" + piece + "): " + colChar + r);
                        }
                    }
                }
            }
        }

        // Group by piece type
        Map<String, List<String>> piecesByType = new HashMap<>();
        for (String pieceDesc : pieces) {
            String type = pieceDesc.substring(0, 1); // first character of piece name
            piecesByType.computeIfAbsent(type, k -> new ArrayList<>()).add(pieceDesc);
        }

        for (Map.Entry<String, List<String>> entry : piecesByType.entrySet()) {
            sb.append("  ").append(entry.getKey()).append(": ");
            sb.append(String.join(", ", entry.getValue()));
            sb.append("\n");
        }

        if (piecesByType.isEmpty()) {
            sb.append("  无棋子\n");
        }
    }

    /**
     * Simplified board state description (for debugging).
     */
    public String getSimpleBoardState(String[][] board) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前棋盘状态：\n");
        sb.append("   a b c d e f g h i\n");

        for (int r = 0; r < board.length; r++) {
            sb.append(r).append("  ");
            for (int c = 0; c < board[r].length; c++) {
                String piece = board[r][c];
                sb.append(piece != null ? piece : ".");
                sb.append(" ");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}