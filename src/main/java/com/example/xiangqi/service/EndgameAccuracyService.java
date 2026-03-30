// EndgameAccuracyService.java
package com.example.xiangqi.service;

import org.springframework.stereotype.Service;
import java.util.*;
import java.io.*;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Service for calculating endgame accuracy of LLMs.
 * Supports different move counts per level (level5: 6 moves, level10: 9 moves, others: 7 moves).
 */
@Service
public class EndgameAccuracyService {

    // Store comparison results per mode and level
    private final Map<String, Map<Integer, MoveComparison.ComparisonStatistics>> allResults = new HashMap<>();

    // CSV directory for accuracy files
    private static final String ACCURACY_CSV_DIR = "CSV_EndgameAccuracy";

    /**
     * Record a comparison result for an AI battle (and save to CSV).
     */
    public void recordComparisonResult(int level, String mode, List<String> llmMoves) {
        MoveComparison.ComparisonStatistics stats =
                MoveComparison.compareMoves(level, mode, llmMoves);

        allResults.computeIfAbsent(mode, k -> new HashMap<>())
                .put(level, stats);

        saveAccuracyToCSV(level, mode, stats);

        System.out.println("\n" + MoveComparison.generateDetailedReport(stats));

        System.out.println("Recorded accuracy for level " + level + " (" + mode + "): " +
                stats.getAccuracySummary());
    }

    /**
     * Save accuracy to a CSV file.
     * File naming: {ai_type}_{subMode}_accuracy.csv
     * Counter file: {ai_type}_{subMode}_accuracy_counter.txt
     * CSV columns: Model Name, Level, Accuracy %
     */
    private void saveAccuracyToCSV(int level, String mode, MoveComparison.ComparisonStatistics stats) {
        try {
            String aiType = extractAIType(mode);
            String subMode = extractSubMode(mode);

            String prefix = aiType;
            if (subMode != null && !subMode.isEmpty()) {
                prefix += "_" + subMode;
            }
            prefix += "_accuracy";

            int currentCount = loadAccuracyCount(prefix);
            int newCount = currentCount + 1;
            saveAccuracyCount(prefix, newCount);

            String projectRoot = System.getProperty("user.dir");
            String resourcesPath = Paths.get(projectRoot, "src", "main", "resources", ACCURACY_CSV_DIR).toString();

            File folder = new File(resourcesPath);
            if (!folder.exists()) {
                boolean created = folder.mkdirs();
                System.out.println("[Accuracy CSV] Created folder " + resourcesPath + ": " + (created ? "success" : "failed"));
            }

            String fileName = prefix + String.format("%02d", newCount) + ".csv";
            File csvFile = new File(folder, fileName);

            System.out.println("[Accuracy CSV] Attempting to save accuracy to: " + csvFile.getAbsolutePath());

            try (FileWriter writer = new FileWriter(csvFile)) {
                writer.write("Model Name,Level,Accuracy %\n");

                String modelName = mapModeToModelName(mode);
                String accuracyFormatted = String.format("%.2f%%", stats.getAccuracyPercentage());

                String row = String.format("%s,%d,%s\n",
                        modelName,
                        level,
                        accuracyFormatted);

                writer.write(row);
                writer.flush();

                System.out.println("[Accuracy CSV] Accuracy saved to: " + csvFile.getAbsolutePath());
                System.out.println("[Accuracy CSV] Record: " + row.trim());

            } catch (IOException e) {
                System.err.println("[Accuracy CSV] Failed to write file: " + e.getMessage());
                e.printStackTrace();
            }

        } catch (Exception e) {
            System.err.println("[Accuracy CSV] Failed to save accuracy: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Map mode string to specific model name.
     */
    private String mapModeToModelName(String mode) {
        if (mode == null || mode.isEmpty()) {
            return "unknown";
        }

        String lowerMode = mode.toLowerCase();

        if (lowerMode.contains("deepseek")) {
            if (lowerMode.contains("cot")) {
                return "deepseek-reasoner";
            } else {
                return "deepseek-chat";
            }
        } else if (lowerMode.contains("openai")) {
            if (lowerMode.contains("cot")) {
                return "gpt-5.2";
            } else {
                return "gpt-5-mini";
            }
        } else if (lowerMode.contains("gemini")) {
            if (lowerMode.contains("cot")) {
                return "gemini-2.5-pro";
            } else {
                return "gemini-2.5-flash";
            }
        }
        return mode;
    }

    /**
     * Extract AI type from mode string.
     */
    private String extractAIType(String mode) {
        if (mode == null || mode.isEmpty()) {
            return "unknown";
        }

        String lowerMode = mode.toLowerCase();

        if (lowerMode.contains("deepseek")) {
            return "deepseek";
        } else if (lowerMode.contains("openai")) {
            return "openai";
        } else if (lowerMode.contains("gemini")) {
            return "gemini";
        } else {
            return mode;
        }
    }

    /**
     * Extract sub-mode (zeroshot or cot) from mode string.
     */
    private String extractSubMode(String mode) {
        if (mode == null || mode.isEmpty()) {
            return "";
        }

        String lowerMode = mode.toLowerCase();

        if (lowerMode.contains("cot")) {
            return "cot";
        } else if (lowerMode.contains("zero") || lowerMode.contains("shot")) {
            return "zeroshot";
        } else {
            return "";
        }
    }

    /**
     * Load accuracy counter from file.
     */
    private int loadAccuracyCount(String prefix) {
        try {
            String projectRoot = System.getProperty("user.dir");
            String counterPath = Paths.get(projectRoot, "src", "main", "resources",
                    ACCURACY_CSV_DIR, prefix + "_counter.txt").toString();

            File file = new File(counterPath);
            if (file.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String content = reader.readLine();
                    if (content != null && !content.trim().isEmpty()) {
                        return Integer.parseInt(content.trim());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Accuracy CSV] Failed to read counter, using default 0: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Save accuracy counter to file.
     */
    private void saveAccuracyCount(String prefix, int count) {
        try {
            String projectRoot = System.getProperty("user.dir");
            String resourcesPath = Paths.get(projectRoot, "src", "main", "resources", ACCURACY_CSV_DIR).toString();

            File folder = new File(resourcesPath);
            if (!folder.exists()) {
                folder.mkdirs();
            }

            String counterFile = Paths.get(resourcesPath, prefix + "_counter.txt").toString();
            try (FileWriter writer = new FileWriter(counterFile)) {
                writer.write(String.valueOf(count));
            }

            System.out.println("[Accuracy CSV] Updated counter: " + prefix + " -> " + count);

        } catch (Exception e) {
            System.err.println("[Accuracy CSV] Failed to save counter: " + e.getMessage());
        }
    }

    /**
     * Batch save accuracy for multiple levels.
     */
    public void batchSaveAccuracyToCSV(String mode, Map<Integer, List<String>> levelMovesMap) {
        if (levelMovesMap == null || levelMovesMap.isEmpty()) {
            System.out.println("[Accuracy CSV] No accuracy data to batch save");
            return;
        }

        System.out.println("[Accuracy CSV] Starting batch save, mode: " + mode);

        for (Map.Entry<Integer, List<String>> entry : levelMovesMap.entrySet()) {
            int level = entry.getKey();
            List<String> llmMoves = entry.getValue();

            try {
                MoveComparison.ComparisonStatistics stats =
                        MoveComparison.compareMoves(level, mode, llmMoves);

                saveAccuracyToCSV(level, mode, stats);

                allResults.computeIfAbsent(mode, k -> new HashMap<>())
                        .put(level, stats);

            } catch (Exception e) {
                System.err.println("[Accuracy CSV] Failed to batch save level " + level + ": " + e.getMessage());
            }
        }

        System.out.println("[Accuracy CSV] Batch save completed, processed " + levelMovesMap.size() + " levels");
    }

    /**
     * Generate and save a comprehensive report to CSV.
     * Report columns: Model Name, Level, Accuracy %
     * Includes all recorded models and levels.
     */
    public void saveComprehensiveReportToCSV() {
        try {
            String projectRoot = System.getProperty("user.dir");
            String resourcesPath = Paths.get(projectRoot, "src", "main", "resources", ACCURACY_CSV_DIR).toString();

            File folder = new File(resourcesPath);
            if (!folder.exists()) {
                folder.mkdirs();
            }

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String fileName = "accuracy_comprehensive_report_" + timestamp + ".csv";
            File csvFile = new File(folder, fileName);

            try (FileWriter writer = new FileWriter(csvFile)) {
                writer.write("Model Name,Level,Accuracy %\n");

                for (String mode : allResults.keySet()) {
                    String modelName = mapModeToModelName(mode);
                    Map<Integer, MoveComparison.ComparisonStatistics> modeResults = allResults.get(mode);

                    for (int level = 1; level <= 10; level++) {
                        MoveComparison.ComparisonStatistics stats = modeResults.get(level);
                        if (stats != null) {
                            String accuracyFormatted = stats.getAccuracyFormatted();
                            writer.write(String.format("%s,%d,%s\n", modelName, level, accuracyFormatted));
                        }
                    }
                }

                System.out.println("[Accuracy CSV] Comprehensive report saved to: " + csvFile.getAbsolutePath());

            } catch (IOException e) {
                System.err.println("[Accuracy CSV] Failed to write comprehensive report: " + e.getMessage());
            }

        } catch (Exception e) {
            System.err.println("[Accuracy CSV] Failed to generate comprehensive report: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ========== Original methods (unchanged) ==========

    public MoveComparison.ComparisonStatistics getLevelAccuracy(int level, String mode) {
        Map<Integer, MoveComparison.ComparisonStatistics> modeResults = allResults.get(mode);
        if (modeResults != null) {
            return modeResults.get(level);
        }
        return null;
    }

    public Map<Integer, Double> getModeAccuracyByLevel(String mode) {
        Map<Integer, Double> accuracyMap = new HashMap<>();
        Map<Integer, MoveComparison.ComparisonStatistics> modeResults = allResults.get(mode);
        if (modeResults != null) {
            for (Map.Entry<Integer, MoveComparison.ComparisonStatistics> entry : modeResults.entrySet()) {
                accuracyMap.put(entry.getKey(), entry.getValue().getAccuracyPercentage());
            }
        }
        return accuracyMap;
    }

    public Map<String, Object> getComprehensiveReport() {
        Map<String, Object> report = new HashMap<>();
        List<Map<String, Object>> modeReports = new ArrayList<>();

        for (String mode : allResults.keySet()) {
            Map<String, Object> modeReport = new HashMap<>();
            modeReport.put("mode", mode);

            Map<Integer, MoveComparison.ComparisonStatistics> modeResults = allResults.get(mode);
            double totalAccuracy = 0;
            int levelCount = 0;

            List<Map<String, Object>> levelDetails = new ArrayList<>();

            for (int level = 1; level <= 10; level++) {
                MoveComparison.ComparisonStatistics stats = modeResults.get(level);
                if (stats != null) {
                    Map<String, Object> levelDetail = new HashMap<>();
                    levelDetail.put("level", level);
                    levelDetail.put("correct", stats.getCorrectMoves());
                    levelDetail.put("total", stats.getTotalMoves());
                    levelDetail.put("accuracy", stats.getAccuracyPercentage());
                    levelDetail.put("accuracyFormatted", stats.getAccuracyFormatted());
                    levelDetail.put("moveCount", EndgameStandardSolutions.getStandardMoveCount(level));

                    levelDetails.add(levelDetail);

                    totalAccuracy += stats.getAccuracyPercentage();
                    levelCount++;
                }
            }

            modeReport.put("levelDetails", levelDetails);

            if (levelCount > 0) {
                double averageAccuracy = totalAccuracy / levelCount;
                modeReport.put("averageAccuracy", averageAccuracy);
                modeReport.put("averageAccuracyFormatted", String.format("%.0f%%", averageAccuracy));
            } else {
                modeReport.put("averageAccuracy", 0.0);
                modeReport.put("averageAccuracyFormatted", "0%");
            }

            modeReports.add(modeReport);
        }

        report.put("success", true);
        report.put("timestamp", new Date().toString());
        report.put("totalLevels", 10);
        report.put("note", "Level5: 6 moves, Level10: 9 moves, others: 7 moves");
        report.put("modeReports", modeReports);

        List<Map<String, Object>> rankings = calculateRankings(modeReports);
        report.put("rankings", rankings);

        return report;
    }

    private List<Map<String, Object>> calculateRankings(List<Map<String, Object>> modeReports) {
        List<Map<String, Object>> rankings = new ArrayList<>();

        for (Map<String, Object> modeReport : modeReports) {
            Map<String, Object> ranking = new HashMap<>();
            ranking.put("mode", modeReport.get("mode"));
            ranking.put("averageAccuracy", modeReport.get("averageAccuracy"));
            ranking.put("averageAccuracyFormatted", modeReport.get("averageAccuracyFormatted"));
            rankings.add(ranking);
        }

        rankings.sort((a, b) -> {
            double accA = (double) a.get("averageAccuracy");
            double accB = (double) b.get("averageAccuracy");
            return Double.compare(accB, accA);
        });

        for (int i = 0; i < rankings.size(); i++) {
            rankings.get(i).put("rank", i + 1);
        }

        return rankings;
    }

    public Map<String, Object> getSummaryReport() {
        Map<String, Object> summary = new HashMap<>();
        List<Map<String, Object>> modeSummaries = new ArrayList<>();

        for (String mode : allResults.keySet()) {
            Map<String, Object> modeSummary = new HashMap<>();
            modeSummary.put("mode", mode);

            Map<Integer, MoveComparison.ComparisonStatistics> modeResults = allResults.get(mode);
            int totalCorrect = 0;
            int totalPossible = 0;
            int completedLevels = 0;

            List<Map<String, Object>> levelAccuracies = new ArrayList<>();

            for (int level = 1; level <= 10; level++) {
                MoveComparison.ComparisonStatistics stats = modeResults.get(level);
                if (stats != null) {
                    int levelMoveCount = EndgameStandardSolutions.getStandardMoveCount(level);
                    totalCorrect += stats.getCorrectMoves();
                    totalPossible += levelMoveCount;
                    completedLevels++;

                    Map<String, Object> levelAccuracy = new HashMap<>();
                    levelAccuracy.put("level", level);
                    levelAccuracy.put("moveCount", levelMoveCount);
                    levelAccuracy.put("correct", stats.getCorrectMoves());
                    levelAccuracy.put("accuracy", stats.getAccuracyPercentage());
                    levelAccuracy.put("accuracyFormatted", stats.getAccuracyFormatted());
                    levelAccuracies.add(levelAccuracy);
                }
            }

            double overallAccuracy = totalPossible > 0 ?
                    (double) totalCorrect / totalPossible * 100 : 0;

            modeSummary.put("totalCorrect", totalCorrect);
            modeSummary.put("totalPossible", totalPossible);
            modeSummary.put("completedLevels", completedLevels);
            modeSummary.put("overallAccuracy", overallAccuracy);
            modeSummary.put("overallAccuracyFormatted", String.format("%.0f%%", overallAccuracy));
            modeSummary.put("levelAccuracies", levelAccuracies);

            modeSummaries.add(modeSummary);
        }

        summary.put("success", true);
        summary.put("modeSummaries", modeSummaries);

        return summary;
    }

    public Map<String, Object> getModeReport(String mode) {
        Map<String, Object> report = new HashMap<>();
        Map<Integer, MoveComparison.ComparisonStatistics> modeResults = allResults.get(mode);

        if (modeResults == null || modeResults.isEmpty()) {
            report.put("success", false);
            report.put("error", "No test results found for mode " + mode);
            return report;
        }

        List<Map<String, Object>> levelReports = new ArrayList<>();
        int totalCorrect = 0;
        int totalPossible = 0;

        for (int level = 1; level <= 10; level++) {
            MoveComparison.ComparisonStatistics stats = modeResults.get(level);
            if (stats != null) {
                int levelMoveCount = EndgameStandardSolutions.getStandardMoveCount(level);
                totalCorrect += stats.getCorrectMoves();
                totalPossible += levelMoveCount;

                Map<String, Object> levelReport = new HashMap<>();
                levelReport.put("level", level);
                levelReport.put("moveCount", levelMoveCount);
                levelReport.put("correct", stats.getCorrectMoves());
                levelReport.put("accuracy", stats.getAccuracyPercentage());
                levelReport.put("accuracyFormatted", stats.getAccuracyFormatted());

                List<Map<String, Object>> moveDetails = new ArrayList<>();
                List<MoveComparison.ComparisonResult> results = stats.getComparisonResults();
                for (MoveComparison.ComparisonResult result : results) {
                    if (result.getMoveNumber() <= levelMoveCount) {
                        Map<String, Object> moveDetail = new HashMap<>();
                        moveDetail.put("moveNumber", result.getMoveNumber());
                        moveDetail.put("llmMove", result.getLlmMove());
                        moveDetail.put("correctMove", result.getCorrectMove());
                        moveDetail.put("isCorrect", result.isCorrect());
                        moveDetails.add(moveDetail);
                    }
                }
                levelReport.put("moveDetails", moveDetails);

                levelReports.add(levelReport);
            }
        }

        double overallAccuracy = totalPossible > 0 ?
                (double) totalCorrect / totalPossible * 100 : 0;

        report.put("success", true);
        report.put("mode", mode);
        report.put("overallAccuracy", overallAccuracy);
        report.put("overallAccuracyFormatted", String.format("%.0f%%", overallAccuracy));
        report.put("totalCorrect", totalCorrect);
        report.put("totalPossible", totalPossible);
        report.put("levelReports", levelReports);

        return report;
    }

    public void clearAllRecords() {
        allResults.clear();
        System.out.println("Cleared all accuracy records");
    }

    public List<String> getAllTestedModes() {
        return new ArrayList<>(allResults.keySet());
    }

    public Map<String, Object> getLevelStatistics(int level) {
        Map<String, Object> statistics = new HashMap<>();
        List<Map<String, Object>> modeStats = new ArrayList<>();

        int levelMoveCount = EndgameStandardSolutions.getStandardMoveCount(level);

        for (String mode : allResults.keySet()) {
            Map<Integer, MoveComparison.ComparisonStatistics> modeResults = allResults.get(mode);
            MoveComparison.ComparisonStatistics stats = modeResults.get(level);

            if (stats != null) {
                Map<String, Object> modeStat = new HashMap<>();
                modeStat.put("mode", mode);
                modeStat.put("correct", stats.getCorrectMoves());
                modeStat.put("total", levelMoveCount);
                modeStat.put("accuracy", stats.getAccuracyPercentage());
                modeStat.put("accuracyFormatted", stats.getAccuracyFormatted());

                modeStats.add(modeStat);
            }
        }

        statistics.put("success", true);
        statistics.put("level", level);
        statistics.put("moveCount", levelMoveCount);
        statistics.put("description", EndgameStandardSolutions.getLevelDescription(level));
        statistics.put("modeStats", modeStats);

        return statistics;
    }

    public Map<String, Object> getAccuracyCSVFiles() {
        Map<String, Object> response = new HashMap<>();
        List<Map<String, Object>> fileList = new ArrayList<>();

        try {
            String projectRoot = System.getProperty("user.dir");
            String resourcesPath = Paths.get(projectRoot, "src", "main", "resources", ACCURACY_CSV_DIR).toString();

            File folder = new File(resourcesPath);
            if (folder.exists() && folder.isDirectory()) {
                File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".csv"));

                if (files != null) {
                    for (File file : files) {
                        Map<String, Object> fileInfo = new HashMap<>();
                        fileInfo.put("fileName", file.getName());
                        fileInfo.put("fileSize", file.length() + " bytes");
                        fileInfo.put("lastModified", new Date(file.lastModified()).toString());
                        fileInfo.put("filePath", file.getAbsolutePath());

                        fileList.add(fileInfo);
                    }
                }
            }

            response.put("success", true);
            response.put("folderPath", resourcesPath);
            response.put("fileCount", fileList.size());
            response.put("files", fileList);

        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
        }

        return response;
    }

    public Map<String, Object> cleanupOldAccuracyFiles(int keepRecentCount) {
        Map<String, Object> response = new HashMap<>();
        int deletedCount = 0;

        try {
            String projectRoot = System.getProperty("user.dir");
            String resourcesPath = Paths.get(projectRoot, "src", "main", "resources", ACCURACY_CSV_DIR).toString();

            File folder = new File(resourcesPath);
            if (folder.exists() && folder.isDirectory()) {
                File[] csvFiles = folder.listFiles((dir, name) ->
                        name.toLowerCase().endsWith(".csv") && name.startsWith("accuracy_"));

                if (csvFiles != null && csvFiles.length > keepRecentCount) {
                    Arrays.sort(csvFiles, Comparator.comparingLong(File::lastModified));

                    for (int i = 0; i < csvFiles.length - keepRecentCount; i++) {
                        if (csvFiles[i].delete()) {
                            deletedCount++;
                            System.out.println("Deleted old accuracy file: " + csvFiles[i].getName());
                        }
                    }
                }
            }

            response.put("success", true);
            response.put("deletedCount", deletedCount);
            response.put("message", "Deleted " + deletedCount + " old accuracy files");

        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
        }

        return response;
    }
}