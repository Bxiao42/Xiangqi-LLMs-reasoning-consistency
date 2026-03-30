// EndgameStandardSolutions.java
package com.example.xiangqi.service;

import java.util.*;

/**
 * Configuration of standard solutions for endgame levels.
 * Stores the correct move sequence for each of the 10 levels.
 * Note: Level 5 is a 6-move checkmate, level 10 is a 9-move checkmate,
 * all others are 7-move checkmates.
 */
public class EndgameStandardSolutions {

    private static final Map<Integer, List<String>> standardSolutions = new HashMap<>();
    private static final Map<Integer, Integer> levelMoveCounts = new HashMap<>();
    private static final Map<Integer, String> levelDescriptions = new HashMap<>();

    static {
        // Initialize level descriptions and move counts
        for (int i = 1; i <= 10; i++) {
            levelDescriptions.put(i, "Level " + i);
        }

        // Set move counts per level
        for (int i = 1; i <= 10; i++) {
            if (i == 5) {
                levelMoveCounts.put(i, 6);
            } else if (i == 10) {
                levelMoveCounts.put(i, 9);
            } else {
                levelMoveCounts.put(i, 7);
            }
        }

        // Level 1: 7-move checkmate
        standardSolutions.put(1, Arrays.asList(
                "f2f9",
                "h6h9",
                "h9g9",
                "h4g6",
                "g9g7",
                "g7i7",
                "i7i9"
        ));

        // Level 2: 7-move checkmate
        standardSolutions.put(2, Arrays.asList(
                "g7h9",
                "g4g9",
                "g9d9",
                "d9d7",
                "b2b7",
                "c6c7",
                "c7d7"
        ));

        // Level 3: 7-move checkmate
        standardSolutions.put(3, Arrays.asList(
                "h6g8",
                "h4h9",
                "h9f9",
                "f9f8",
                "f8d8",
                "d8d9",
                "g8f6"
        ));

        // Level 4: 7-move checkmate
        standardSolutions.put(4, Arrays.asList(
                "h6h9",
                "h3h8",
                "i9i7",
                "h8h7",
                "h7h3",
                "h3h8",
                "i7i9"
        ));

        // Level 5: 6-move checkmate
        standardSolutions.put(5, Arrays.asList(
                "h9h8",
                "g6g9",
                "g9g8",
                "h8h9",
                "g8e8",
                "h9h8"
        ));

        // Level 6: 7-move checkmate
        standardSolutions.put(6, Arrays.asList(
                "e4f4",
                "f4f7",
                "c4c8",
                "c8c9",
                "f7e7",
                "e7d7",
                "c9d9"
        ));

        // Level 7: 7-move checkmate
        standardSolutions.put(7, Arrays.asList(
                "h8f8",
                "h7f7",
                "e6f6",
                "e4f4",
                "f6f7",
                "f7f8",
                "f8f9"
        ));

        // Level 8: 7-move checkmate
        standardSolutions.put(8, Arrays.asList(
                "e7f7",
                "f5f6",
                "f6f7",
                "f7f8",
                "f8f9",
                "f9e9",
                "f2f9"
        ));

        // Level 9: 7-move checkmate
        standardSolutions.put(9, Arrays.asList(
                "h6f7",
                "e2f2",
                "i2i9",
                "f7h8",
                "h8g6",
                "g5f5",
                "f5f6"
        ));

        // Level 10: 9-move checkmate
        standardSolutions.put(10, Arrays.asList(
                "c4c9",
                "a5b7",
                "c9c7",
                "c7c3",
                "c3d3",
                "d3e3",
                "b7c9",
                "e3d3",
                "d3d7"
        ));
    }

    /**
     * Get the standard solution for the given level.
     */
    public static List<String> getStandardSolution(int level) {
        if (level < 1 || level > 10) {
            throw new IllegalArgumentException("Level must be between 1 and 10, got: " + level);
        }

        List<String> solution = standardSolutions.get(level);
        if (solution == null) {
            System.err.println("Warning: Level " + level + " solution is not configured");
            // Return default 7-move solution as fallback
            return Arrays.asList("a2a3", "b2b3", "c2c3", "d2d3", "e2e3", "f2f3", "g2g3");
        }

        return new ArrayList<>(solution);
    }

    /**
     * Get the number of standard moves for the given level.
     * Level 5: 6 moves, Level 10: 9 moves, others: 7 moves.
     */
    public static int getStandardMoveCount(int level) {
        return levelMoveCounts.getOrDefault(level, 7);
    }

    /**
     * Get the standard move at a specific step number for a given level.
     * @param level level number (1-10)
     * @param moveNumber step number (starting from 1)
     */
    public static String getStandardMove(int level, int moveNumber) {
        if (moveNumber < 1) {
            throw new IllegalArgumentException("Move number must be >= 1, got: " + moveNumber);
        }

        List<String> solution = getStandardSolution(level);
        if (moveNumber <= solution.size()) {
            return solution.get(moveNumber - 1);
        }
        return null;
    }

    /**
     * Get description of a level.
     */
    public static String getLevelDescription(int level) {
        String description = levelDescriptions.getOrDefault(level, "Level " + level);
        int moveCount = getStandardMoveCount(level);
        return description + " - " + moveCount + "-move checkmate";
    }

    /**
     * Validate that all levels are correctly configured.
     */
    public static void validateAllLevels() {
        System.out.println("Validating endgame standard solutions...");
        for (int level = 1; level <= 10; level++) {
            List<String> solution = standardSolutions.get(level);
            int expectedMoves = getStandardMoveCount(level);

            if (solution == null) {
                System.err.println("Level " + level + ": standard solution not configured");
            } else if (solution.size() != expectedMoves) {
                System.err.println("Level " + level + ": expected " + expectedMoves + " moves, but configured with " + solution.size() + " moves");
                System.err.println("    Solution: " + solution);
            } else {
                System.out.println("Level " + level + ": " + solution.size() + " moves, configuration OK");
            }
        }
    }

    /**
     * Print the standard solution for a given level.
     */
    public static void printSolution(int level) {
        List<String> solution = getStandardSolution(level);
        int moveCount = getStandardMoveCount(level);

        System.out.println("\n" + getLevelDescription(level) + ":");
        for (int i = 0; i < solution.size(); i++) {
            System.out.printf("  Move %d: %s%n", i + 1, solution.get(i));
        }
    }

    /**
     * Get all standard solutions.
     */
    public static Map<Integer, List<String>> getAllSolutions() {
        Map<Integer, List<String>> all = new HashMap<>();
        for (int level = 1; level <= 10; level++) {
            all.put(level, getStandardSolution(level));
        }
        return all;
    }

    /**
     * Print all standard solutions.
     */
    public static void printAllSolutions() {
        System.out.println("\nAll endgame level standard solutions:");
        for (int level = 1; level <= 10; level++) {
            printSolution(level);
        }
    }
}