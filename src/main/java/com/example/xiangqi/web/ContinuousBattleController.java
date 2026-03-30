// ContinuousBattleController.java
package com.example.xiangqi.web;

import com.example.xiangqi.engine.EngineService;
import com.example.xiangqi.game.XqRules.*;
import com.example.xiangqi.game.GameExecutor;
import com.example.xiangqi.llm.ErrorCsvRecorder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/continuous-battle")
@CrossOrigin(origins = "*")
public class ContinuousBattleController {

    @Autowired
    private EngineService engineService;

    @Autowired
    private ErrorCsvRecorder errorCsvRecorder;

    @Autowired
    private GameExecutor gameExecutor;

    private final Map<String, ContinuousBattleTask> tasks = new ConcurrentHashMap<>();

    /**
     * Start a continuous battle.
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startContinuousBattle(
            @RequestParam("aiType") String aiType,
            @RequestParam(value = "maxGames", defaultValue = "3") int maxGames,
            @RequestParam(value = "mode", defaultValue = "zero-shot") String mode) {

        if (maxGames < 1 || maxGames > 100) {
            maxGames = 3;
        }

        if (!Arrays.asList("zero-shot", "cot").contains(mode.toLowerCase())) {
            mode = "zero-shot";
        }

        String[] validAITypes;
        if ("cot".equals(mode)) {
            validAITypes = new String[]{"DeepSeek CoT", "OpenAI CoT", "Gemini CoT"};
        } else {
            validAITypes = new String[]{"DeepSeek", "OpenAI", "Gemini"};
        }

        boolean validAIType = Arrays.stream(validAITypes)
                .anyMatch(type -> type.equalsIgnoreCase(aiType));

        if (!validAIType) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "Unsupported AI type. Please choose: " + String.join(", ", validAITypes));
            return ResponseEntity.badRequest().body(response);
        }

        String taskId = UUID.randomUUID().toString().substring(0, 8);

        ContinuousBattleTask task = new ContinuousBattleTask(taskId, aiType, mode, maxGames);
        tasks.put(taskId, task);

        executeContinuousBattle(task);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Continuous battle started");
        response.put("taskId", taskId);
        response.put("aiType", aiType);
        response.put("mode", mode);
        response.put("maxGames", maxGames);
        response.put("startTime", new Date());

        System.out.println("[Continuous Battle] Task started: taskId=" + taskId +
                ", AI type=" + aiType +
                ", mode=" + mode +
                ", max games=" + maxGames);

        return ResponseEntity.ok(response);
    }

    /**
     * Execute continuous battle asynchronously.
     */
    @Async
    public void executeContinuousBattle(ContinuousBattleTask task) {
        try {
            System.out.println("[Continuous Battle] Executing task: " + task.getTaskId());

            while (task.getCurrentGame() < task.getMaxGames()) {
                if (task.isCancelled()) {
                    System.out.println("[Continuous Battle] Task cancelled: " + task.getTaskId());
                    break;
                }

                int gameNumber = task.getCurrentGame() + 1;
                System.out.println("[Continuous Battle] Preparing game " + gameNumber + " (" + task.getAIType() + ")");

                Map<String, Object> gameResult = playSingleGame(task.getAIType(), task.getMode(), gameNumber);

                task.incrementCurrentGame();
                task.addGameResult(gameResult);

                String result = (String) gameResult.get("result");
                int rounds = (int) gameResult.get("rounds");
                String winner = (String) gameResult.get("winner");
                String aiType = (String) gameResult.get("aiType");

                System.out.println("[Continuous Battle] Game " + gameNumber + " ended: " + result +
                        ", rounds: " + rounds +
                        ", winner: " + winner +
                        ", AI type: " + aiType);

                if ("error".equals(result) || result == null) {
                    System.err.println("[Continuous Battle] Game " + gameNumber + " had error, continuing next");
                }

                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    System.err.println("[Continuous Battle] Sleep interrupted");
                    break;
                }
            }

            task.setCompleted(true);
            task.setEndTime(new Date());

            System.out.println("[Continuous Battle] Task completed: " + task.getTaskId() +
                    ", total games: " + task.getGameResults().size() +
                    ", completed: " + task.isCompleted());

        } catch (Exception e) {
            System.err.println("[Continuous Battle] Task execution error: " + e.getMessage());
            e.printStackTrace();
            task.setError(e.getMessage());
            task.setCompleted(true);
            task.setEndTime(new Date());
        }
    }

    /**
     * Play a single game.
     */
    private Map<String, Object> playSingleGame(String aiType, String mode, int gameNumber) {
        Map<String, Object> gameResult;

        try {
            String aiTypeForExecutor;

            if (aiType.contains("CoT")) {
                if (aiType.equalsIgnoreCase("DeepSeek CoT")) {
                    aiTypeForExecutor = "DeepSeek";
                } else if (aiType.equalsIgnoreCase("OpenAI CoT")) {
                    aiTypeForExecutor = "OpenAI";
                } else if (aiType.equalsIgnoreCase("Gemini CoT")) {
                    aiTypeForExecutor = "Gemini";
                } else {
                    aiTypeForExecutor = aiType.split(" ")[0];
                }
            } else {
                aiTypeForExecutor = aiType;
            }

            System.out.println("[Single Game] Calling GameExecutor: AI type=" + aiTypeForExecutor +
                    ", mode=" + mode + ", game=" + gameNumber);

            gameResult = gameExecutor.executeSingleGame(aiTypeForExecutor, mode, gameNumber);

            if (gameResult == null) {
                gameResult = new HashMap<>();
            }

            gameResult.put("gameNumber", gameNumber);
            gameResult.put("aiType", aiType);
            gameResult.put("mode", mode);

            if (!gameResult.containsKey("startTime")) {
                gameResult.put("startTime", new Date());
            }
            if (!gameResult.containsKey("endTime")) {
                gameResult.put("endTime", new Date());
            }

            Object winnerObj = gameResult.get("winner");
            if (winnerObj != null) {
                String winner = winnerObj.toString();
                if (winner.equals("RED")) {
                    gameResult.put("winner", "AI");
                    gameResult.put("result", aiType + " win");
                } else if (winner.equals("BLACK")) {
                    gameResult.put("winner", "pikafish");
                    gameResult.put("result", "pikafish win");
                } else if (winner.equals("draw")) {
                    gameResult.put("winner", "draw");
                    gameResult.put("result", "draw");
                } else if (winner.equals("error")) {
                    gameResult.put("winner", "error");
                    gameResult.put("result", "error");
                }
            }

            if (!gameResult.containsKey("rounds")) {
                gameResult.put("rounds", 0);
            }

        } catch (Exception e) {
            System.err.println("[Single Game] Execution error: " + e.getMessage());
            e.printStackTrace();
            gameResult = new HashMap<>();
            gameResult.put("gameNumber", gameNumber);
            gameResult.put("startTime", new Date());
            gameResult.put("endTime", new Date());
            gameResult.put("rounds", 0);
            gameResult.put("winner", "error");
            gameResult.put("result", "error - " + e.getMessage());
            gameResult.put("aiType", aiType);
            gameResult.put("mode", mode);
            gameResult.put("error", e.getMessage());
        }

        return gameResult;
    }

    /**
     * Get task status.
     */
    @GetMapping("/status/{taskId}")
    public ResponseEntity<Map<String, Object>> getTaskStatus(@PathVariable String taskId) {
        ContinuousBattleTask task = tasks.get(taskId);

        if (task == null) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "Task not found");
            return ResponseEntity.badRequest().body(response);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("taskId", taskId);
        response.put("aiType", task.getAIType());
        response.put("mode", task.getMode());
        response.put("maxGames", task.getMaxGames());
        response.put("currentGame", task.getCurrentGame());
        response.put("completedGames", task.getGameResults().size());
        response.put("completed", task.isCompleted());
        response.put("cancelled", task.isCancelled());
        response.put("startTime", task.getStartTime());
        response.put("endTime", task.getEndTime());
        response.put("error", task.getError());

        if (!task.getGameResults().isEmpty()) {
            long aiWins = task.getGameResults().stream()
                    .filter(r -> {
                        Object result = r.get("result");
                        if (result == null) return false;
                        String resultStr = result.toString().toLowerCase();
                        return resultStr.contains("ai win") ||
                                (resultStr.contains("win") && resultStr.contains("ai"));
                    })
                    .count();

            long pikafishWins = task.getGameResults().stream()
                    .filter(r -> {
                        Object result = r.get("result");
                        if (result == null) return false;
                        String resultStr = result.toString().toLowerCase();
                        return resultStr.contains("pikafish win");
                    })
                    .count();

            long draws = task.getGameResults().stream()
                    .filter(r -> {
                        Object result = r.get("result");
                        if (result == null) return false;
                        String resultStr = result.toString().toLowerCase();
                        return resultStr.contains("draw");
                    })
                    .count();

            long errors = task.getGameResults().stream()
                    .filter(r -> {
                        Object result = r.get("result");
                        if (result == null) return false;
                        String resultStr = result.toString().toLowerCase();
                        return resultStr.contains("error");
                    })
                    .count();

            Map<String, Object> stats = new HashMap<>();
            stats.put("totalGames", task.getGameResults().size());
            stats.put("aiWins", aiWins);
            stats.put("pikafishWins", pikafishWins);
            stats.put("draws", draws);
            stats.put("errors", errors);
            stats.put("aiWinRate", task.getGameResults().size() > 0 ?
                    (double) aiWins / task.getGameResults().size() : 0);
            stats.put("aiWinPercentage", task.getGameResults().size() > 0 ?
                    String.format("%.1f%%", (double) aiWins / task.getGameResults().size() * 100) : "0%");

            response.put("stats", stats);
            response.put("gameResults", task.getGameResults());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Get all tasks.
     */
    @GetMapping("/all-tasks")
    public ResponseEntity<Map<String, Object>> getAllTasks() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("totalTasks", tasks.size());

        List<Map<String, Object>> taskList = new ArrayList<>();
        for (Map.Entry<String, ContinuousBattleTask> entry : tasks.entrySet()) {
            ContinuousBattleTask task = entry.getValue();
            Map<String, Object> taskInfo = new HashMap<>();
            taskInfo.put("taskId", entry.getKey());
            taskInfo.put("aiType", task.getAIType());
            taskInfo.put("mode", task.getMode());
            taskInfo.put("maxGames", task.getMaxGames());
            taskInfo.put("currentGame", task.getCurrentGame());
            taskInfo.put("completed", task.isCompleted());
            taskInfo.put("cancelled", task.isCancelled());
            taskInfo.put("startTime", task.getStartTime());
            taskInfo.put("endTime", task.getEndTime());
            taskList.add(taskInfo);
        }

        response.put("tasks", taskList);
        return ResponseEntity.ok(response);
    }

    /**
     * Cancel a task.
     */
    @PostMapping("/cancel/{taskId}")
    public ResponseEntity<Map<String, Object>> cancelTask(@PathVariable String taskId) {
        ContinuousBattleTask task = tasks.get(taskId);

        if (task == null) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "Task not found");
            return ResponseEntity.badRequest().body(response);
        }

        task.setCancelled(true);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Task cancelled");
        response.put("taskId", taskId);
        response.put("completedGames", task.getGameResults().size());

        System.out.println("[Continuous Battle] Task cancelled: " + taskId);

        return ResponseEntity.ok(response);
    }

    /**
     * Clean up completed tasks.
     */
    @PostMapping("/cleanup")
    public ResponseEntity<Map<String, Object>> cleanupTasks() {
        int beforeSize = tasks.size();

        long oneHourAgo = System.currentTimeMillis() - 3600000;
        tasks.entrySet().removeIf(entry -> {
            ContinuousBattleTask task = entry.getValue();
            return (task.isCompleted() || task.isCancelled()) &&
                    task.getEndTime() != null &&
                    task.getEndTime().getTime() < oneHourAgo;
        });

        int removed = beforeSize - tasks.size();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Cleaned up " + removed + " old tasks");
        response.put("remainingTasks", tasks.size());

        return ResponseEntity.ok(response);
    }

    /**
     * Continuous battle task class.
     */
    private static class ContinuousBattleTask {
        private final String taskId;
        private final String aiType;
        private final String mode;
        private final int maxGames;
        private int currentGame = 0;
        private boolean completed = false;
        private boolean cancelled = false;
        private String error = null;
        private final Date startTime;
        private Date endTime = null;
        private final List<Map<String, Object>> gameResults = new ArrayList<>();

        public ContinuousBattleTask(String taskId, String aiType, String mode, int maxGames) {
            this.taskId = taskId;
            this.aiType = aiType;
            this.mode = mode;
            this.maxGames = maxGames;
            this.startTime = new Date();
        }

        public String getTaskId() { return taskId; }
        public String getAIType() { return aiType; }
        public String getMode() { return mode; }
        public int getMaxGames() { return maxGames; }
        public int getCurrentGame() { return currentGame; }
        public boolean isCompleted() { return completed; }
        public boolean isCancelled() { return cancelled; }
        public String getError() { return error; }
        public Date getStartTime() { return startTime; }
        public Date getEndTime() { return endTime; }
        public List<Map<String, Object>> getGameResults() { return gameResults; }

        public void incrementCurrentGame() { this.currentGame++; }
        public void setCompleted(boolean completed) { this.completed = completed; }
        public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
        public void setError(String error) { this.error = error; }
        public void setEndTime(Date endTime) { this.endTime = endTime; }

        public void addGameResult(Map<String, Object> gameResult) {
            this.gameResults.add(gameResult);
        }
    }
}