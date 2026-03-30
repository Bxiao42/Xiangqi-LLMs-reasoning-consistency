// EndgameController.java
package com.example.xiangqi.web;

import com.example.xiangqi.engine.EngineService;
import com.example.xiangqi.game.XqEndgameJudge;
import com.example.xiangqi.game.XqEndgameRule.*;
import com.example.xiangqi.service.DeepSeekEndgameService;
import com.example.xiangqi.service.GeminiEndgameService;
import com.example.xiangqi.service.OpenAIEndgameService;
import com.example.xiangqi.service.EndgameAccuracyService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/endgame")
@CrossOrigin(origins = "*")
public class EndgameController {

    private final EngineService engine;
    private final DeepSeekEndgameService deepSeekEndgameService;
    private final OpenAIEndgameService openAIEndgameService;
    private final GeminiEndgameService geminiEndgameService;
    private final EndgameAccuracyService endgameAccuracyService;

    private final Map<Integer, EndgameState> endgameStates = new HashMap<>();
    private final Map<Integer, Integer> levelTotalSteps = new HashMap<>();

    public EndgameController(EngineService engine,
                             DeepSeekEndgameService deepSeekEndgameService,
                             OpenAIEndgameService openAIEndgameService,
                             GeminiEndgameService geminiEndgameService,
                             EndgameAccuracyService endgameAccuracyService) {
        this.engine = engine;
        this.deepSeekEndgameService = deepSeekEndgameService;
        this.openAIEndgameService = openAIEndgameService;
        this.geminiEndgameService = geminiEndgameService;
        this.endgameAccuracyService = endgameAccuracyService;
    }

    private static class EndgameState {
        Board board;
        Side turn;
        int foulCount = 0;
        List<String> moveHistory = new ArrayList<>();
        List<String[]> foulRecords = new ArrayList<>();
        int level;
        boolean completed = false;
        Side playerSide;
        XqEndgameJudge.GameResult finalResult;

        Map<String, Integer> positionCounts = new HashMap<>();
        String lastRepeatedPosition = null;
        boolean isRepeatedMove = false;

        EndgameState(Board board, Side turn, int level, Side playerSide) {
            this.board = board;
            this.turn = turn;
            this.level = level;
            this.playerSide = playerSide;
        }
    }

    @PostMapping("/load/{level}")
    public Map<String, Object> loadLevel(@PathVariable int level) {
        try {
            EndgameConfig config = loadEndgameConfig(level);
            if (config == null) {
                throw new IllegalArgumentException("Level " + level + " does not exist");
            }

            Board board = XqEndgameJudge.createBoardFromConfig(level, config.startingSide, config.pieces);
            Side playerSide = config.startingSide;

            EndgameState state = new EndgameState(board, config.startingSide, level, playerSide);
            endgameStates.put(level, state);

            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "loaded");
            resp.put("level", level);
            resp.put("turn", state.turn.toString());
            resp.put("playerSide", playerSide.toString());
            resp.put("description", config.description != null ? config.description : "Endgame Level " + level);
            resp.put("board", serializeBoard(board));

            int totalSteps = levelTotalSteps.getOrDefault(level, 0);
            resp.put("totalSteps", totalSteps);

            return resp;
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to load level: " + e.getMessage());
        }
    }

    @PostMapping("/move/{level}")
    public Map<String, Object> playerMove(
            @PathVariable int level,
            @RequestBody Map<String, Integer> move) throws Exception {

        EndgameState state = endgameStates.get(level);
        if (state == null) {
            throw new IllegalArgumentException("Please load level " + level + " first");
        }

        if (state.completed) {
            throw new IllegalStateException("This level has been completed");
        }

        int fromR = move.get("fromR");
        int fromC = move.get("fromC");
        int toR = move.get("toR");
        int toC = move.get("toC");

        Pos fromPos = new Pos(fromR, fromC);
        Pos toPos = new Pos(toR, toC);
        Move m = new Move(fromPos, toPos);

        List<Move> legal = state.board.legalMovesAt(fromPos);

        Map<String, Object> resp = new HashMap<>();

        boolean isLegal = legal.stream().anyMatch(x -> x.to.equals(toPos));
        if (!isLegal) {
            state.foulCount++;
            String foulTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            state.foulRecords.add(new String[]{
                    String.valueOf(state.foulCount),
                    "Illegal move",
                    foulTime
            });

            resp.put("result", "foul");
            resp.put("foulCount", state.foulCount);
            resp.put("message", "Illegal move! Please follow Xiangqi rules");
            resp.put("board", serializeBoard(state.board));
            return resp;
        }

        String currentPosition = getBoardPosition(state.board, state.turn);
        if (state.isRepeatedMove && state.lastRepeatedPosition != null &&
                currentPosition.equals(state.lastRepeatedPosition)) {
            resp.put("result", "repeated_move");
            resp.put("message", "Perpetual check or chase prohibited! Please choose another move");
            resp.put("repeatedPosition", currentPosition);
            resp.put("board", serializeBoard(state.board));
            return resp;
        }

        Board newBoard = state.board.makeMove(m);
        String playerMoveUci = coordToUci(m);

        String newPosition = getBoardPosition(newBoard, state.turn.opponent());
        int repeatCount = state.positionCounts.getOrDefault(newPosition, 0) + 1;
        state.positionCounts.put(newPosition, repeatCount);

        if (repeatCount >= 5) {
            state.isRepeatedMove = true;
            state.lastRepeatedPosition = newPosition;
            resp.put("result", "repeated_move");
            resp.put("message", "Perpetual check or chase prohibited! Please choose another move");
            resp.put("repeatedPosition", newPosition);
            resp.put("repeatCount", repeatCount);
            resp.put("board", serializeBoard(state.board));
            return resp;
        } else {
            state.isRepeatedMove = false;
            state.lastRepeatedPosition = null;
        }

        state.board = newBoard;
        state.moveHistory.add(playerMoveUci);

        if (XqEndgameJudge.isStalemate(state.board, state.turn.opponent())) {
            XqEndgameJudge.GameResult result = state.turn == Side.RED ? XqEndgameJudge.GameResult.RED_WIN : XqEndgameJudge.GameResult.BLACK_WIN;
            return handleGameEnd(state, result, resp, "Stalemate", m);
        }

        XqEndgameJudge.GameResult result = XqEndgameJudge.checkGameState(state.board, state.turn.opponent());
        if (result != XqEndgameJudge.GameResult.IN_PROGRESS) {
            return handleGameEnd(state, result, resp, "After player move", m);
        }

        state.turn = state.turn.opponent();

        String currentFen = boardToEngineFen(state.board, state.turn);
        String aiMoveUci = engine.bestMove(currentFen, state.moveHistory, true);

        Move aiMove = null;
        if (aiMoveUci != null && !aiMoveUci.isEmpty()) {
            aiMove = parseUci(aiMoveUci);
            if (aiMove != null) {
                Board aiBoard = state.board.makeMove(aiMove);

                String aiPosition = getBoardPosition(aiBoard, state.turn);
                int aiRepeatCount = state.positionCounts.getOrDefault(aiPosition, 0) + 1;
                state.positionCounts.put(aiPosition, aiRepeatCount);

                if (aiRepeatCount >= 5) {
                    XqEndgameJudge.GameResult winResult = state.playerSide == Side.RED ? XqEndgameJudge.GameResult.RED_WIN : XqEndgameJudge.GameResult.BLACK_WIN;
                    return handleGameEnd(state, winResult, resp, "AI perpetual check foul", m);
                }

                state.board = aiBoard;
                state.moveHistory.add(aiMoveUci);

                result = XqEndgameJudge.checkGameState(state.board, state.turn);
                if (result != XqEndgameJudge.GameResult.IN_PROGRESS) {
                    return handleGameEnd(state, result, resp, "After AI move", m);
                }

                state.turn = state.turn.opponent();
                resp.put("aiMove", aiMoveUci);
            }
        }

        resp.put("result", "ok");
        resp.put("playerMove", playerMoveUci);
        resp.put("foulCount", state.foulCount);
        resp.put("turn", state.turn.toString());
        resp.put("board", serializeBoard(state.board));
        resp.put("isRepeatedMove", state.isRepeatedMove);

        if (state.board.inCheck(state.turn)) {
            resp.put("inCheck", true);
            resp.put("message", "Check! Please escape check");
        }

        return resp;
    }

    private Map<String, Object> handleGameEnd(EndgameState state, XqEndgameJudge.GameResult result,
                                              Map<String, Object> resp, String trigger, Move lastMove) {
        state.completed = true;
        state.finalResult = result;

        int aiMoveCount = (state.moveHistory.size() + 1) / 2;
        updateLevelTotalSteps(state.level, aiMoveCount);

        resp.put("result", "gameOver");
        resp.put("gameResult", result.toString());
        resp.put("resultDescription", XqEndgameJudge.getResultDescription(result, state.playerSide));
        resp.put("completed", true);
        resp.put("trigger", trigger);
        resp.put("foulCount", state.foulCount);
        resp.put("board", serializeBoard(state.board));
        resp.put("playerMove", coordToUci(lastMove));

        System.out.println("Game ended: " + result.getDescription() + " (" + trigger + ")");
        System.out.println("Level " + state.level + " current AI steps: " + aiMoveCount +
                ", cumulative total AI steps: " + levelTotalSteps.get(state.level));

        return resp;
    }

    @PostMapping("/reset/{level}")
    public Map<String, Object> resetLevel(@PathVariable int level) {
        return loadLevel(level);
    }

    @GetMapping("/progress/{level}")
    public Map<String, Object> getProgress(@PathVariable int level) {
        EndgameState state = endgameStates.get(level);
        Map<String, Object> resp = new HashMap<>();
        if (state != null) {
            resp.put("level", level);
            resp.put("completed", state.completed);
            resp.put("foulCount", state.foulCount);
            resp.put("moveCount", state.moveHistory.size());
            if (state.completed) {
                resp.put("finalResult", state.finalResult != null ? state.finalResult.toString() : "UNKNOWN");
                resp.put("resultDescription",
                        XqEndgameJudge.getResultDescription(state.finalResult, state.playerSide));
            }
        } else {
            resp.put("level", level);
            resp.put("completed", false);
        }

        int totalSteps = levelTotalSteps.getOrDefault(level, 0);
        resp.put("totalSteps", totalSteps);

        return resp;
    }

    @GetMapping("/total-steps")
    public Map<String, Object> getTotalSteps() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("levelTotalSteps", levelTotalSteps);
        resp.put("note", "Statistics are stored in memory only and will reset on server restart");
        return resp;
    }

    private void updateLevelTotalSteps(int level, int currentSteps) {
        int totalSteps = levelTotalSteps.getOrDefault(level, 0) + currentSteps;
        levelTotalSteps.put(level, totalSteps);
        System.out.println("Updated level " + level + " total AI steps in memory: " + totalSteps);
    }

    private String getBoardPosition(Board board, Side turn) {
        StringBuilder sb = new StringBuilder();
        sb.append(turn.toString()).append("|");
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null) {
                    sb.append(piece.type.toString()).append(piece.side.toString())
                            .append(r).append(c).append(";");
                }
            }
        }
        return sb.toString();
    }

    /* ========== AI battle endpoints ========== */

    @PostMapping("/ai-battle/new/{level}")
    public ResponseEntity<Map<String, Object>> startEndgameAIBattle(
            @PathVariable int level,
            @RequestParam String mode) {

        try {
            EndgameConfig config = loadEndgameConfig(level);
            if (config == null) {
                throw new IllegalArgumentException("Level " + level + " does not exist");
            }

            Board board = XqEndgameJudge.createBoardFromConfig(level, config.startingSide, config.pieces);
            Side startingSide = Side.RED;
            Map<String, Object> result;

            String actualMode = extractModeName(mode);
            String modeLower = mode.toLowerCase();

            System.out.println("[Endgame] Starting AI endgame battle, level: " + level +
                    ", raw mode: " + mode + ", actual mode: " + actualMode);

            if (modeLower.contains("openai") || modeLower.startsWith("gpt")) {
                result = openAIEndgameService.startNewEndgameBattle(
                        level, actualMode, board, startingSide);
            } else if (modeLower.contains("gemini") || modeLower.startsWith("gemini")) {
                result = geminiEndgameService.startNewEndgameBattle(
                        level, actualMode, board, startingSide);
            } else {
                result = deepSeekEndgameService.startNewEndgameBattle(
                        level, actualMode, board, startingSide);
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            e.printStackTrace();
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "Failed to start AI battle: " + e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    @PostMapping("/ai-battle/next-round/{level}")
    public ResponseEntity<Map<String, Object>> playNextAIBattleRound(
            @PathVariable int level,
            @RequestParam String mode) {

        try {
            Map<String, Object> result;

            String actualMode = extractModeName(mode);
            String modeLower = mode.toLowerCase();

            System.out.println("[Endgame] AI endgame battle next round, level: " + level +
                    ", raw mode: " + mode + ", actual mode: " + actualMode);

            if (modeLower.contains("openai") || modeLower.startsWith("gpt")) {
                result = openAIEndgameService.playNextRound(level, actualMode);
            } else if (modeLower.contains("gemini") || modeLower.startsWith("gemini")) {
                result = geminiEndgameService.playNextRound(level, actualMode);
            } else {
                result = deepSeekEndgameService.playNextRound(level, actualMode);
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            e.printStackTrace();
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "Failed to play next round: " + e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    private String extractModeName(String mode) {
        if (mode == null || mode.isEmpty()) {
            return "zeroshot";
        }
        if (mode.equals("deepseek-reasoner") || mode.equals("gpt-5.2") || mode.equals("gemini-2.5-pro")) {
            return "cot";
        }
        if (mode.equals("deepseek-chat") || mode.equals("gpt-5-mini") || mode.equals("gemini-2.5-flash")) {
            return "zeroshot";
        }
        String actualMode = mode;
        if (mode.startsWith("openai-")) {
            actualMode = mode.substring(7);
        } else if (mode.startsWith("deepseek-")) {
            actualMode = mode.substring(9);
        } else if (mode.startsWith("gemini-")) {
            actualMode = mode.substring(7);
        }
        if (!"zeroshot".equals(actualMode) && !"cot".equals(actualMode)) {
            if (actualMode.contains("zero") || actualMode.contains("shot")) {
                actualMode = "zeroshot";
            } else if (actualMode.contains("cot")) {
                actualMode = "cot";
            } else {
                actualMode = "zeroshot";
            }
        }
        return actualMode;
    }

    @GetMapping("/ai-battle/fouls/{level}")
    public ResponseEntity<Map<String, Object>> getAIBattleFouls(
            @PathVariable int level,
            @RequestParam String mode) {

        List<Map<String, Object>> fouls;
        String actualMode = extractModeName(mode);
        String modeLower = mode.toLowerCase();

        if (modeLower.contains("openai") || modeLower.startsWith("gpt")) {
            fouls = openAIEndgameService.getFoulRecords(level, actualMode);
        } else if (modeLower.contains("gemini") || modeLower.startsWith("gemini")) {
            fouls = geminiEndgameService.getFoulRecords(level, actualMode);
        } else {
            fouls = deepSeekEndgameService.getFoulRecords(level, actualMode);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("fouls", fouls);
        response.put("level", level);
        response.put("mode", mode);
        response.put("actualMode", actualMode);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/ai-battle/board-state/{level}")
    public ResponseEntity<Map<String, Object>> getAIBattleBoardState(
            @PathVariable int level,
            @RequestParam String mode) {

        Map<String, Object> boardState;
        String actualMode = extractModeName(mode);
        String modeLower = mode.toLowerCase();

        if (modeLower.contains("openai") || modeLower.startsWith("gpt")) {
            boardState = openAIEndgameService.getCurrentBoardState(level, actualMode);
        } else if (modeLower.contains("gemini") || modeLower.startsWith("gemini")) {
            boardState = geminiEndgameService.getCurrentBoardState(level, actualMode);
        } else {
            boardState = deepSeekEndgameService.getCurrentBoardState(level, actualMode);
        }

        if (boardState == null) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "No AI battle in progress for level " + level);
            return ResponseEntity.badRequest().body(errorResponse);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("boardState", boardState);
        response.put("level", level);
        response.put("mode", mode);
        response.put("actualMode", actualMode);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/ai-battle/modes")
    public ResponseEntity<Map<String, Object>> getAvailableAIBattleModes() {
        Map<String, Object> response = new HashMap<>();
        List<String> availableModes = Arrays.asList(
                "deepseek-zero-shot", "deepseek-cot",
                "openai-zero-shot", "openai-cot",
                "gemini-zero-shot", "gemini-cot"
        );
        response.put("success", true);
        response.put("availableModes", availableModes);
        response.put("description", "Available AI battle modes");
        return ResponseEntity.ok(response);
    }

    /* ========== Accuracy endpoints ========== */

    @GetMapping("/accuracy/report")
    public ResponseEntity<Map<String, Object>> getAccuracyReport() {
        try {
            Map<String, Object> report = endgameAccuracyService.getComprehensiveReport();
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "Failed to get accuracy report: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    @GetMapping("/accuracy/csv-files")
    public ResponseEntity<Map<String, Object>> getAccuracyCSVFiles() {
        try {
            Map<String, Object> files = endgameAccuracyService.getAccuracyCSVFiles();
            return ResponseEntity.ok(files);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "Failed to get CSV file list: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    @PostMapping("/accuracy/manual-save/{level}")
    public ResponseEntity<Map<String, Object>> manualSaveAccuracy(
            @PathVariable int level,
            @RequestParam String mode,
            @RequestBody List<String> aiMoves) {

        try {
            endgameAccuracyService.recordComparisonResult(level, mode, aiMoves);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Manually saved accuracy record");
            response.put("level", level);
            response.put("mode", mode);
            response.put("aiMovesCount", aiMoves.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "Manual save failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /* ========== Utility Methods ========== */

    private EndgameConfig loadEndgameConfig(int level) throws IOException {
        String[] possiblePaths = {
                "static/images/" + level + ".js",
                "public/images/" + level + ".js",
                "resources/static/images/" + level + ".js"
        };

        for (String jsPath : possiblePaths) {
            ClassPathResource resource = new ClassPathResource(jsPath);
            if (resource.exists()) {
                try (InputStream inputStream = resource.getInputStream();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

                    StringBuilder content = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line).append("\n");
                    }

                    System.out.println("Loading level " + level + " from path " + jsPath);
                    return parseJsConfig(content.toString(), level);
                }
            }
        }
        throw new FileNotFoundException("JS configuration file for level " + level + " not found");
    }

    private EndgameConfig parseJsConfig(String jsContent, int level) {
        EndgameConfig config = new EndgameConfig();
        config.level = level;

        try {
            String cleanedContent = jsContent.replaceAll("\\s+", " ").trim();
            cleanedContent = cleanedContent.replaceAll("//.*", "");
            cleanedContent = cleanedContent.replaceAll("/\\*.*?\\*/", "");

            System.out.println("Cleaned JS content: " + cleanedContent);

            Pattern sidePattern = Pattern.compile("side:\\s*[\"']?(RED|BLACK)[\"']?");
            Matcher sideMatcher = sidePattern.matcher(cleanedContent);
            if (sideMatcher.find()) {
                String sideStr = sideMatcher.group(1);
                config.startingSide = "RED".equals(sideStr) ? Side.RED : Side.BLACK;
                System.out.println("Found starting side: " + sideStr);
            } else {
                config.startingSide = Side.RED;
                System.out.println("Using default starting side: RED");
            }

            config.pieces = new ArrayList<>();

            int piecesStart = cleanedContent.indexOf("pieces: [");
            if (piecesStart == -1) {
                throw new IllegalArgumentException("Pieces array not found");
            }

            int bracketCount = 1;
            int piecesEnd = piecesStart + "pieces: [".length();

            for (int i = piecesEnd; i < cleanedContent.length(); i++) {
                char c = cleanedContent.charAt(i);
                if (c == '[') bracketCount++;
                else if (c == ']') bracketCount--;

                if (bracketCount == 0) {
                    piecesEnd = i;
                    break;
                }
            }

            String piecesSection = cleanedContent.substring(piecesStart + "pieces: [".length(), piecesEnd);
            System.out.println("Pieces section: " + piecesSection);

            Pattern piecePattern = Pattern.compile(
                    "\\{\\s*type:\\s*[\"']?([KABNRCPH])[\"']?\\s*,\\s*side:\\s*[\"']?(RED|BLACK)[\"']?\\s*,\\s*r:\\s*(\\d+)\\s*,\\s*c:\\s*(\\d+)\\s*\\}"
            );

            Matcher pieceMatcher = piecePattern.matcher(piecesSection);
            while (pieceMatcher.find()) {
                try {
                    String type = pieceMatcher.group(1);
                    String sideStr = pieceMatcher.group(2);
                    int r = Integer.parseInt(pieceMatcher.group(3));
                    int c = Integer.parseInt(pieceMatcher.group(4));

                    Side side = "RED".equals(sideStr) ? Side.RED : Side.BLACK;
                    config.pieces.add(new XqEndgameJudge.PiecePlacement(type, side, r, c));
                    System.out.println("Parsed piece: " + type + " " + sideStr + " position(" + r + "," + c + ")");
                } catch (Exception e) {
                    System.err.println("Failed to parse piece: " + e.getMessage());
                }
            }

            if (config.pieces.isEmpty()) {
                throw new IllegalArgumentException("No piece definitions found in JS configuration for level " + level);
            }

            config.description = "Endgame Level " + level;
            System.out.println("Successfully loaded level " + level + ", found " + config.pieces.size() + " pieces");
            return config;

        } catch (Exception e) {
            System.err.println("Failed to parse JS configuration: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to parse level " + level + " configuration: " + e.getMessage());
        }
    }

    private List<List<Map<String, Object>>> serializeBoard(Board board) {
        List<List<Map<String, Object>>> serialized = new ArrayList<>();
        for (int r = 0; r < 10; r++) {
            List<Map<String, Object>> row = new ArrayList<>();
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                Map<String, Object> cell = new HashMap<>();
                if (piece != null) {
                    cell.put("side", piece.side.toString());
                    cell.put("type", pieceTypeToChar(piece.type));
                } else {
                    cell.put("side", null);
                    cell.put("type", null);
                }
                row.add(cell);
            }
            serialized.add(row);
        }
        return serialized;
    }

    private String pieceTypeToChar(PieceType type) {
        switch (type) {
            case GENERAL: return "K";
            case ADVISOR: return "A";
            case ELEPHANT: return "B";
            case HORSE: return "N";
            case ROOK: return "R";
            case CANNON: return "C";
            case PAWN: return "P";
            default: return "?";
        }
    }

    private String boardToEngineFen(Board board, Side turn) {
        StringBuilder fen = new StringBuilder();
        for (int r = 9; r >= 0; r--) {
            int emptyCount = 0;
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece == null) {
                    emptyCount++;
                } else {
                    if (emptyCount > 0) {
                        fen.append(emptyCount);
                        emptyCount = 0;
                    }
                    fen.append(getPieceChar(piece));
                }
            }
            if (emptyCount > 0) fen.append(emptyCount);
            if (r > 0) fen.append('/');
        }
        fen.append(' ').append(turn == Side.RED ? 'w' : 'b');
        return fen.toString();
    }

    private char getPieceChar(Piece piece) {
        char baseChar;
        switch (piece.type) {
            case ROOK: baseChar = 'r'; break;
            case HORSE: baseChar = 'n'; break;
            case ELEPHANT: baseChar = 'b'; break;
            case ADVISOR: baseChar = 'a'; break;
            case GENERAL: baseChar = 'k'; break;
            case CANNON: baseChar = 'c'; break;
            case PAWN: baseChar = 'p'; break;
            default: baseChar = '?';
        }
        return piece.side == Side.RED ? Character.toUpperCase(baseChar) : baseChar;
    }

    private String coordToUci(Move m) {
        return "" + (char)('a' + m.from.c) + (char)('0' + m.from.r)
                + (char)('a' + m.to.c) + (char)('0' + m.to.r);
    }

    private Move parseUci(String uci) {
        if (uci == null || uci.length() < 4) return null;
        int fromC = uci.charAt(0) - 'a';
        int fromR = uci.charAt(1) - '0';
        int toC = uci.charAt(2) - 'a';
        int toR = uci.charAt(3) - '0';
        return new Move(new Pos(fromR, fromC), new Pos(toR, toC));
    }

    public static String generateBoardVisualization(Board board) {
        StringBuilder sb = new StringBuilder();
        sb.append("  a  b  c  d  e  f  g  h  i\n");
        for (int r = 9; r >= 0; r--) {
            sb.append(r).append(" ");
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece == null) {
                    sb.append(" . ");
                } else {
                    sb.append(" ").append(pieceToChar(piece)).append(" ");
                }
            }
            sb.append(r).append("\n");
        }
        sb.append("  a  b  c  d  e  f  g  h  i\n");
        return sb.toString();
    }

    private static char pieceToChar(Piece piece) {
        if (piece.side == Side.RED) {
            switch (piece.type) {
                case ROOK: return 'R';
                case HORSE: return 'H';
                case ELEPHANT: return 'E';
                case ADVISOR: return 'A';
                case GENERAL: return 'K';
                case CANNON: return 'C';
                case PAWN: return 'P';
                default: return '?';
            }
        } else {
            switch (piece.type) {
                case ROOK: return 'r';
                case HORSE: return 'h';
                case ELEPHANT: return 'e';
                case ADVISOR: return 'a';
                case GENERAL: return 'k';
                case CANNON: return 'c';
                case PAWN: return 'p';
                default: return '?';
            }
        }
    }

    private static class EndgameConfig {
        int level;
        Side startingSide;
        List<XqEndgameJudge.PiecePlacement> pieces;
        String description;
    }
}