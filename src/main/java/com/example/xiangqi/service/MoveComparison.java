// MoveComparison.java
package com.example.xiangqi.service;

import java.util.*;

/**
 * Record of move comparison between LLM move and standard solution.
 * Supports different move counts per level (level5: 6 moves, level10: 9 moves, others: 7 moves).
 */
public class MoveComparison {

    public static class ComparisonResult {
        private int round;           // round number (starting from 1)
        private String llmMove;      // actual move by LLM
        private String correctMove;  // standard solution move
        private boolean isCorrect;   // whether the move is correct
        private int moveNumber;      // move number within the game (starting from 1)

        public ComparisonResult(int round, String llmMove, String correctMove, int moveNumber) {
            this.round = round;
            this.llmMove = llmMove;
            this.correctMove = correctMove;
            this.moveNumber = moveNumber;
            this.isCorrect = llmMove != null && correctMove != null && llmMove.equals(correctMove);
        }

        public int getRound() { return round; }
        public String getLlmMove() { return llmMove; }
        public String getCorrectMove() { return correctMove; }
        public boolean isCorrect() { return isCorrect; }
        public int getMoveNumber() { return moveNumber; }

        @Override
        public String toString() {
            String matchText = isCorrect ? "correct" : "incorrect";
            return String.format("Round %d (Move %d): LLM=%s, Standard=%s, %s",
                    round, moveNumber,
                    llmMove != null ? llmMove : "none",
                    correctMove != null ? correctMove : "none",
                    matchText);
        }
    }

    /**
     * Statistics of the comparison, supporting different move counts per level.
     */
    public static class ComparisonStatistics {
        private int level;
        private String mode;
        private int totalMoves;           // total standard moves for this level
        private int correctMoves;         // number of correct moves
        private double accuracyPercentage; // accuracy percentage
        private List<ComparisonResult> comparisonResults;
        private Map<Integer, Boolean> moveAccuracyMap; // accuracy per move

        public ComparisonStatistics(int level, String mode, List<ComparisonResult> results) {
            this.level = level;
            this.mode = mode;
            this.comparisonResults = results;

            this.totalMoves = EndgameStandardSolutions.getStandardMoveCount(level);

            List<ComparisonResult> validResults = results.stream()
                    .filter(r -> r.getMoveNumber() <= totalMoves)
                    .toList();

            this.correctMoves = (int) validResults.stream()
                    .filter(ComparisonResult::isCorrect)
                    .count();

            this.accuracyPercentage = totalMoves > 0 ?
                    (double) correctMoves / totalMoves * 100 : 0.0;

            this.moveAccuracyMap = new HashMap<>();
            for (int i = 1; i <= totalMoves; i++) {
                final int moveNum = i;
                boolean isCorrect = validResults.stream()
                        .filter(r -> r.getMoveNumber() == moveNum)
                        .findFirst()
                        .map(ComparisonResult::isCorrect)
                        .orElse(false);
                moveAccuracyMap.put(moveNum, isCorrect);
            }
        }

        public int getLevel() { return level; }
        public String getMode() { return mode; }
        public int getTotalMoves() { return totalMoves; }
        public int getCorrectMoves() { return correctMoves; }
        public double getAccuracyPercentage() { return accuracyPercentage; }
        public List<ComparisonResult> getComparisonResults() { return comparisonResults; }
        public Map<Integer, Boolean> getMoveAccuracyMap() { return moveAccuracyMap; }

        public String getAccuracyFormatted() {
            return String.format("%.0f%%", accuracyPercentage);
        }

        public String getAccuracySummary() {
            return String.format("%d/%d (%.0f%%)", correctMoves, totalMoves, accuracyPercentage);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Level %d (%s): %d/%d correct (%.0f%%)\n",
                    level, mode, correctMoves, totalMoves, accuracyPercentage));

            sb.append("Move accuracy:\n");
            for (int i = 1; i <= totalMoves; i++) {
                boolean isCorrect = moveAccuracyMap.getOrDefault(i, false);
                String status = isCorrect ? "correct" : "incorrect";
                sb.append(String.format("  Move %d: %s\n", i, status));
            }
            return sb.toString();
        }
    }

    /**
     * Compare LLM moves with standard solution.
     * @param level level number (1-10)
     * @param mode AI mode name
     * @param llmMoves sequence of LLM moves
     * @return comparison statistics
     */
    public static ComparisonStatistics compareMoves(int level, String mode, List<String> llmMoves) {
        List<String> standardMoves = EndgameStandardSolutions.getStandardSolution(level);
        int standardMoveCount = EndgameStandardSolutions.getStandardMoveCount(level);
        List<ComparisonResult> results = new ArrayList<>();

        if (standardMoves.size() != standardMoveCount) {
            System.err.println("Warning: Level " + level + " standard solution should have " + standardMoveCount +
                    " moves, but configured with " + standardMoves.size() + " moves");
        }

        int maxRounds = Math.max(standardMoveCount, llmMoves.size());

        for (int round = 1; round <= maxRounds; round++) {
            String llmMove = round <= llmMoves.size() ? llmMoves.get(round - 1) : null;
            String correctMove = round <= standardMoves.size() ? standardMoves.get(round - 1) : null;
            results.add(new ComparisonResult(round, llmMove, correctMove, round));
        }

        return new ComparisonStatistics(level, mode, results);
    }

    /**
     * Batch compare multiple AI modes for a given level.
     */
    public static Map<String, ComparisonStatistics> batchCompare(
            int level, Map<String, List<String>> modeMovesMap) {

        Map<String, ComparisonStatistics> results = new HashMap<>();

        for (Map.Entry<String, List<String>> entry : modeMovesMap.entrySet()) {
            String mode = entry.getKey();
            List<String> moves = entry.getValue();
            ComparisonStatistics stats = compareMoves(level, mode, moves);
            results.put(mode, stats);
        }

        return results;
    }

    /**
     * Generate a detailed comparison report.
     */
    public static String generateDetailedReport(ComparisonStatistics stats) {
        StringBuilder report = new StringBuilder();

        report.append("Endgame Accuracy Analysis Report\n");
        report.append("================================\n");
        report.append(String.format("Level: %d\n", stats.getLevel()));
        report.append(String.format("Mode: %s\n", stats.getMode()));
        report.append(String.format("Accuracy: %s\n", stats.getAccuracySummary()));
        report.append(String.format("Total moves: %d\n", stats.getTotalMoves()));
        report.append(String.format("Correct moves: %d\n", stats.getCorrectMoves()));
        report.append("\n");
        report.append("Move-by-move comparison:\n");
        report.append("------------------------\n");

        List<ComparisonResult> results = stats.getComparisonResults();
        int standardMoveCount = stats.getTotalMoves();

        for (int i = 0; i < Math.min(results.size(), standardMoveCount); i++) {
            ComparisonResult result = results.get(i);
            String status = result.isCorrect() ? "[OK]" : "[FAIL]";
            report.append(String.format("%s Move %d: LLM=%s | Standard=%s\n",
                    status,
                    result.getMoveNumber(),
                    result.getLlmMove() != null ? result.getLlmMove() : "none",
                    result.getCorrectMove() != null ? result.getCorrectMove() : "none"));
        }

        if (results.size() > standardMoveCount) {
            report.append("\nNote: LLM made " + (results.size() - standardMoveCount) + " extra moves\n");
            for (int i = standardMoveCount; i < results.size(); i++) {
                ComparisonResult result = results.get(i);
                report.append(String.format("   Extra move %d: %s\n",
                        result.getMoveNumber(),
                        result.getLlmMove()));
            }
        }

        return report.toString();
    }
}