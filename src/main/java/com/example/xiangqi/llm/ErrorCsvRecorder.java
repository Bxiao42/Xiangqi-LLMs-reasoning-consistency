// ErrorCsvRecorder.java
package com.example.xiangqi.llm;

import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Component
public class ErrorCsvRecorder {

    // Record type enum - supports DeepSeek, OpenAI, and Gemini
    public enum RecordType {
        // DeepSeek types
        DEEPSEEK_ZERO_SHOT("deepseek"),
        DEEPSEEK_COT("deepseek_Cot"),
        DEEPSEEK_ZERO_SHOT_ENDGAME("deepseek_endgame"),
        DEEPSEEK_COT_ENDGAME("deepseek_cot_endgame"),

        // OpenAI types
        OPENAI_ZERO_SHOT("openai"),
        OPENAI_COT("openai_Cot"),
        OPENAI_ZERO_SHOT_ENDGAME("openai_endgame"),
        OPENAI_COT_ENDGAME("openai_cot_endgame"),

        // Gemini types
        GEMINI_ZERO_SHOT("gemini"),
        GEMINI_COT("gemini_Cot"),
        GEMINI_ZERO_SHOT_ENDGAME("gemini_endgame"),
        GEMINI_COT_ENDGAME("gemini_cot_endgame");

        private final String prefix;

        RecordType(String prefix) {
            this.prefix = prefix;
        }

        public String getPrefix() {
            return prefix;
        }

        public String getCounterFileName() {
            return prefix + "_counter.txt";
        }

        public String getCsvFileName(int count) {
            return String.format("%s%02d.csv", prefix, count);
        }

        // Returns true if this is an endgame type
        public boolean isEndgame() {
            return this == DEEPSEEK_ZERO_SHOT_ENDGAME || this == DEEPSEEK_COT_ENDGAME ||
                    this == OPENAI_ZERO_SHOT_ENDGAME || this == OPENAI_COT_ENDGAME ||
                    this == GEMINI_ZERO_SHOT_ENDGAME || this == GEMINI_COT_ENDGAME;
        }
    }

    // Error record structure (original)
    public static class ErrorRecord {
        private final int round;
        private final String errorType;

        public ErrorRecord(int round, String errorType) {
            this.round = round;
            this.errorType = errorType;
        }

        public int getRound() {
            return round;
        }

        public String getErrorType() {
            return errorType;
        }

        @Override
        public String toString() {
            return String.format("%d,%s", round, errorType);
        }
    }

    // Endgame error record structure (new)
    public static class EndgameErrorRecord {
        private final int round;
        private final String errorType;
        private final int totalSteps;
        private final int level;

        public EndgameErrorRecord(int round, String errorType, int totalSteps, int level) {
            // Validate level must be between 1 and 10
            if (level < 1 || level > 10) {
                System.err.println("[CSV] Error: EndgameErrorRecord constructor received invalid level=" + level + ", must be 1-10");
                throw new IllegalArgumentException("Endgame level must be between 1 and 10, current: " + level);
            }

            this.round = round;
            this.errorType = errorType;
            this.totalSteps = totalSteps;
            this.level = level;
        }

        public int getRound() {
            return round;
        }

        public String getErrorType() {
            return errorType;
        }

        public int getTotalSteps() {
            return totalSteps;
        }

        public int getLevel() {
            return level;
        }

        @Override
        public String toString() {
            return String.format("%d,%s,%d,%d", round, errorType, totalSteps, level);
        }
    }

    // Record error (without specific move information) - original method
    public static ErrorRecord recordError(int round, String errorType) {
        String formattedError = formatErrorType(errorType);
        return new ErrorRecord(round, formattedError);
    }

    // Record game result - original method
    public static ErrorRecord recordGameResult(int round, String gameResult) {
        return new ErrorRecord(round, formatErrorType(gameResult));
    }

    // Record endgame error (new)
    public static EndgameErrorRecord recordEndgameError(int round, String errorType, int totalSteps, int level) {
        String formattedError = formatEndgameErrorType(errorType);
        return new EndgameErrorRecord(round, formattedError, totalSteps, level);
    }

    // Format error type - handles win/loss cases (now supports Gemini)
    private static String formatErrorType(String errorType) {
        if (errorType == null) {
            return "Unknown error";
        }

        String error = errorType.trim();

        // Handle game result prefix
        if (error.startsWith("Game Result:")) {
            error = error.replace("Game Result:", "").trim();
        }

        // 1. First handle clear loss cases (checkmated, stalemated)
        if (error.contains("checkmated") || error.contains("被将死")) {
            if (error.contains("DeepSeek") || error.toLowerCase().contains("deepseek")) {
                return "pikafish win";
            } else if (error.contains("OpenAI") || error.toLowerCase().contains("openai")) {
                return "pikafish win";
            } else if (error.contains("Gemini") || error.toLowerCase().contains("gemini")) {
                return "pikafish win";
            } else if (error.contains("Pikafish") || error.contains("皮卡鱼")) {
                // Pikafish is checkmated, determine which AI wins from context
                if (error.contains("OpenAI") || error.toLowerCase().contains("openai")) {
                    return "openai win";
                } else if (error.contains("Gemini") || error.toLowerCase().contains("gemini")) {
                    return "gemini win";
                } else {
                    return "deepseek win";
                }
            }
        }

        if (error.contains("stalemated") || error.contains("困毙")) {
            if (error.contains("DeepSeek") || error.toLowerCase().contains("deepseek")) {
                return "pikafish win";
            } else if (error.contains("OpenAI") || error.toLowerCase().contains("openai")) {
                return "pikafish win";
            } else if (error.contains("Gemini") || error.toLowerCase().contains("gemini")) {
                return "pikafish win";
            } else if (error.contains("Pikafish") || error.contains("皮卡鱼")) {
                if (error.contains("OpenAI") || error.toLowerCase().contains("openai")) {
                    return "openai win";
                } else if (error.contains("Gemini") || error.toLowerCase().contains("gemini")) {
                    return "gemini win";
                } else {
                    return "deepseek win";
                }
            }
        }

        // 2. Handle "cannot continue" cases
        if (error.contains("cannot continue")) {
            if (error.contains("DeepSeek") || error.toLowerCase().contains("deepseek")) {
                return "pikafish win";
            } else if (error.contains("OpenAI") || error.toLowerCase().contains("openai")) {
                return "pikafish win";
            } else if (error.contains("Gemini") || error.toLowerCase().contains("gemini")) {
                return "pikafish win";
            } else if (error.contains("Pikafish") || error.toLowerCase().contains("pikafish")) {
                if (error.contains("OpenAI") || error.toLowerCase().contains("openai")) {
                    return "openai win";
                } else if (error.contains("Gemini") || error.toLowerCase().contains("gemini")) {
                    return "gemini win";
                } else {
                    return "deepseek win";
                }
            }
        }

        // 3. Handle illegal move etc.
        if (error.contains("Illegal move")) {
            return "Illegal move";
        } else if (error.contains("Invalid move format")) {
            return "Invalid move format";
        } else if (error.contains("DeepSeek error")) {
            return "DeepSeek error";
        } else if (error.contains("OpenAI error")) {
            return "OpenAI error";
        } else if (error.contains("Gemini error")) {
            return "Gemini error";
        }

        // 4. Finally handle explicit win messages (must come after loss checks)
        if (error.contains("皮卡鱼获胜") || error.contains("Pikafish wins") ||
                error.contains("pikafish win") || error.contains("black win")) {
            return "pikafish win";
        } else if (error.contains("DeepSeek获胜") || error.contains("DeepSeek wins") ||
                error.contains("deepseek win") || error.contains("red win")) {
            // Need to distinguish which AI wins
            if (error.contains("OpenAI") || error.toLowerCase().contains("openai")) {
                return "openai win";
            } else if (error.contains("Gemini") || error.toLowerCase().contains("gemini")) {
                return "gemini win";
            } else {
                return "deepseek win";
            }
        } else if (error.contains("OpenAI获胜") || error.contains("OpenAI wins") ||
                error.contains("openai win")) {
            return "openai win";
        } else if (error.contains("Gemini获胜") || error.contains("Gemini wins") ||
                error.contains("gemini win")) {
            return "gemini win";
        }

        // Default fallback
        return "pikafish win";
    }

    // Format endgame error type (new) - handles win/loss cases (now supports Gemini)
    private static String formatEndgameErrorType(String errorType) {
        if (errorType == null) {
            return "Unknown error";
        }

        String error = errorType.trim();

        // 1. Convert "Empty move response" to "Illegal move"
        if (error.contains("Empty move response")) {
            return "Illegal move";
        }

        // 2. Skip "failed 3 times, using random move" records
        if (error.contains("failed 3 times, using random move")) {
            return null; // return null to indicate this record should be omitted
        }

        // 3. Handle OpenAI specific errors
        if (error.contains("OpenAI failed 3 times")) {
            return null; // omit
        }

        if (error.contains("OpenAI cannot continue")) {
            return "OpenAI cannot continue";
        }

        if (error.contains("OpenAI error")) {
            return "OpenAI error";
        }

        // 4. Handle Gemini specific errors
        if (error.contains("Gemini failed 3 times")) {
            return null; // omit
        }

        if (error.contains("Gemini cannot continue")) {
            return "Gemini cannot continue";
        }

        if (error.contains("Gemini error")) {
            return "Gemini error";
        }

        // 5. First handle loss cases
        if (error.contains("checkmated") || error.contains("被将死")) {
            if (error.contains("DeepSeek") || error.toLowerCase().contains("deepseek")) {
                return "pikafish win";
            } else if (error.contains("OpenAI") || error.toLowerCase().contains("openai")) {
                return "pikafish win";
            } else if (error.contains("Gemini") || error.toLowerCase().contains("gemini")) {
                return "pikafish win";
            } else if (error.contains("Pikafish") || error.contains("皮卡鱼")) {
                if (error.contains("OpenAI") || error.toLowerCase().contains("openai")) {
                    return "openai win";
                } else if (error.contains("Gemini") || error.toLowerCase().contains("gemini")) {
                    return "gemini win";
                } else {
                    return "deepseek win";
                }
            }
        }

        if (error.contains("stalemated") || error.contains("困毙")) {
            if (error.contains("DeepSeek") || error.toLowerCase().contains("deepseek")) {
                return "pikafish win";
            } else if (error.contains("OpenAI") || error.toLowerCase().contains("openai")) {
                return "pikafish win";
            } else if (error.contains("Gemini") || error.toLowerCase().contains("gemini")) {
                return "pikafish win";
            } else if (error.contains("Pikafish") || error.contains("皮卡鱼")) {
                if (error.contains("OpenAI") || error.toLowerCase().contains("openai")) {
                    return "openai win";
                } else if (error.contains("Gemini") || error.toLowerCase().contains("gemini")) {
                    return "gemini win";
                } else {
                    return "deepseek win";
                }
            }
        }

        // 6. Handle other error types
        if (error.contains("Illegal move")) {
            return "Illegal move";
        } else if (error.contains("Invalid move format")) {
            return "Illegal move";
        } else if (error.contains("30回合内未分胜负") || error.contains("30回合") ||
                error.contains("30 rounds") || error.contains("timeout")) {
            return "pikafish win";
        } else if (error.contains("双方都无过河棋子")) {
            return "pikafish win";
        } else if (error.contains("OpenAI无棋可走")) {
            return "pikafish win";
        } else if (error.contains("Gemini无棋可走")) {
            return "pikafish win";
        }

        // 7. Finally handle win cases
        if (error.contains("pikafish win") || error.contains("Pikafish win") ||
                error.contains("皮卡鱼获胜") || error.contains("black win")) {
            return "pikafish win";
        } else if (error.contains("deepseek win") || error.contains("DeepSeek win") ||
                error.contains("DeepSeek获胜") || error.contains("red win")) {
            return "deepseek win";
        } else if (error.contains("openai win") || error.contains("OpenAI win") ||
                error.contains("OpenAI获胜")) {
            return "openai win";
        } else if (error.contains("gemini win") || error.contains("Gemini win") ||
                error.contains("Gemini获胜")) {
            return "gemini win";
        }

        // Default: return original error
        return error;
    }

    // Save error records to CSV - original method (now supports Gemini)
    public void saveErrorsToCsv(List<ErrorRecord> errors, RecordType type) {
        if (errors == null || errors.isEmpty()) {
            System.out.println("[CSV] No error records to save");
            return;
        }

        try {
            // 1. Load and update counter
            int currentCount = loadErrorCount(type);
            int newCount = currentCount + 1;
            saveErrorCount(newCount, type);

            // 2. Prepare file name and path
            String projectRoot = System.getProperty("user.dir");
            String resourcesPath = Paths.get(projectRoot, "src", "main", "resources", "CSV_Xq").toString();

            // Ensure folder exists
            File folder = new File(resourcesPath);
            if (!folder.exists()) {
                folder.mkdirs();
                System.out.println("[CSV] Created folder: " + resourcesPath);
            }

            // 3. Create CSV file
            String fileName = type.getCsvFileName(newCount);
            File csvFile = new File(folder, fileName);

            try (FileWriter writer = new FileWriter(csvFile)) {
                // Write CSV header
                writer.write("Round,ErrorType\n");

                // Write all error records
                for (ErrorRecord error : errors) {
                    writer.write(error.toString() + "\n");
                }

                System.out.println("[CSV] " + type.getPrefix() + " error records saved to: " + csvFile.getAbsolutePath());
                System.out.println("[CSV] Total " + errors.size() + " error records saved");
            }

        } catch (IOException e) {
            System.err.println("[CSV] Failed to save error records: " + e.getMessage());
        }
    }

    // Save endgame error records to CSV (new) - now supports Gemini
    public void saveEndgameErrorsToCsv(List<EndgameErrorRecord> errors, RecordType type) {
        if (errors == null || errors.isEmpty()) {
            System.out.println("[CSV] No endgame error records to save for " + type.getPrefix());
            return;
        }

        try {
            // 1. Load and update counter
            int currentCount = loadErrorCount(type);
            int newCount = currentCount + 1;
            saveErrorCount(newCount, type);

            // 2. Prepare file name and path - use CSV_Endgame directory
            String projectRoot = System.getProperty("user.dir");
            String resourcesPath = Paths.get(projectRoot, "src", "main", "resources", "CSV_Endgame").toString();

            // Ensure folder exists
            File folder = new File(resourcesPath);
            if (!folder.exists()) {
                boolean created = folder.mkdirs();
                System.out.println("[CSV] Created folder " + resourcesPath + ": " + (created ? "success" : "failed"));
            }

            // 3. Check write permission
            if (!folder.canWrite()) {
                System.err.println("[CSV] Folder not writable: " + resourcesPath);
                return;
            }

            // 4. Create CSV file
            String fileName = type.getCsvFileName(newCount);
            File csvFile = new File(folder, fileName);

            System.out.println("[CSV] Attempting to save " + errors.size() + " error records to: " + csvFile.getAbsolutePath());
            System.out.println("[CSV] Record type: " + type + ", file: " + fileName);

            try (FileWriter writer = new FileWriter(csvFile)) {
                // Write CSV header (endgame mode)
                writer.write("Round,ErrorType,total steps,level\n");

                // Write all error records
                for (EndgameErrorRecord error : errors) {
                    String line = error.toString();
                    writer.write(line + "\n");
                    System.out.println("[CSV] Writing record: " + line);
                }

                System.out.println("[CSV] " + type.getPrefix() + " endgame error records saved to: " + csvFile.getAbsolutePath());
                System.out.println("[CSV] Total " + errors.size() + " endgame error records saved");

                writer.flush();

                if (csvFile.exists()) {
                    System.out.println("[CSV] File verification successful, path: " + csvFile.getAbsolutePath());
                } else {
                    System.err.println("[CSV] File creation failed");
                }
            } catch (IOException e) {
                System.err.println("[CSV] Failed to write file: " + e.getMessage());
                e.printStackTrace();
            }

        } catch (Exception e) {
            System.err.println("[CSV] Failed to save endgame error records: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Load error counter - now supports Gemini
    private int loadErrorCount(RecordType type) {
        try {
            String projectRoot = System.getProperty("user.dir");

            // Choose directory based on record type
            String directory;
            if (type.isEndgame()) {
                directory = "CSV_Endgame";
            } else {
                directory = "CSV_Xq";
            }

            Path counterPath = Paths.get(projectRoot, "src", "main", "resources", directory, type.getCounterFileName());

            File file = counterPath.toFile();
            if (file.exists()) {
                String content = new String(Files.readAllBytes(counterPath));
                return Integer.parseInt(content.trim());
            }
        } catch (Exception e) {
            System.err.println("[CSV] Failed to read " + type.getPrefix() + " counter, using default 0: " + e.getMessage());
        }
        return 0;
    }

    // Save error counter - now supports Gemini
    private void saveErrorCount(int count, RecordType type) {
        try {
            String projectRoot = System.getProperty("user.dir");

            // Choose directory based on record type
            String directory;
            if (type.isEndgame()) {
                directory = "CSV_Endgame";
            } else {
                directory = "CSV_Xq";
            }

            String resourcesPath = Paths.get(projectRoot, "src", "main", "resources", directory).toString();

            File folder = new File(resourcesPath);
            if (!folder.exists()) {
                folder.mkdirs();
            }

            String counterFile = Paths.get(resourcesPath, type.getCounterFileName()).toString();
            try (FileWriter writer = new FileWriter(counterFile)) {
                writer.write(String.valueOf(count));
            }

        } catch (Exception e) {
            System.err.println("[CSV] Failed to save " + type.getPrefix() + " counter: " + e.getMessage());
        }
    }

    // Record final endgame result (new) - fixed parameter order
    public static EndgameErrorRecord recordFinalEndgameResult(int round, String errorType, int totalSteps, int level) {
        // Validate level parameter
        if (level < 1 || level > 10) {
            System.err.println("[CSV] Critical error: recordFinalEndgameResult received invalid level=" + level);
            throw new IllegalArgumentException("recordFinalEndgameResult: level must be between 1 and 10, current: " + level);
        }

        String formattedResult = formatEndgameErrorType(errorType);

        // If formattedResult is null (skip), return null
        if (formattedResult == null) {
            return null;
        }

        return new EndgameErrorRecord(round, formattedResult, totalSteps, level);
    }

    // Record illegal move in endgame (new) - fixed parameter order
    public static EndgameErrorRecord recordEndgameIllegalMove(int round, String errorType, int totalSteps, int level) {
        // Validate level parameter
        if (level < 1 || level > 10) {
            System.err.println("[CSV] Critical error: recordEndgameIllegalMove received invalid level=" + level);
            throw new IllegalArgumentException("recordEndgameIllegalMove: level must be between 1 and 10, current: " + level);
        }

        String formattedError = formatEndgameErrorType(errorType);

        if (formattedError == null) {
            return null;
        }

        return new EndgameErrorRecord(round, formattedError, totalSteps, level);
    }

    // Convenience method to validate level
    public static void validateLevel(int level) {
        if (level < 1 || level > 10) {
            throw new IllegalArgumentException("Level must be between 1 and 10, current: " + level);
        }
    }
}