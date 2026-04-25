package com.example;

import com.example.model.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Hook System
 * Hooks are extension points around the main loop.
 * They let readers add behavior without rewriting the loop itself.
 * Teaching version:
 *   - SessionStart
 *   - PreToolUse
 *   - PostToolUse
 * Teaching exit-code contract:
 *   - 0 -> continue
 *   - 1 -> block
 *   - 2 -> inject a message
 * This is intentionally simpler than a production system. The goal here is to
 * teach the extension pattern clearly before introducing event-specific edge
 * cases.
 * Key insight: "Extend the agent without touching the loop."
 */
public class S08_hook_system {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    // Hook events supported by the teaching version
    private static final List<String> HOOK_EVENTS = List.of("PreToolUse", "PostToolUse", "SessionStart");
    private static final int HOOK_TIMEOUT_SECONDS = 30;

    private String model;
    private String baseUrl;
    private String apiKey;
    private String systemPrompt;
    private Path workDir;

    private List<Tool> tools;
    private Map<String, Function<JsonInput, String>> toolHandlers;
    private HookManager hookManager;

    private final String promptName = "s08";

    public static void main(String[] args) throws Exception {
        new S08_hook_system().runInteractive();
    }

    private void runInteractive() throws Exception {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        String token = dotenv.get("ANTHROPIC_AUTH_TOKEN");
        if (token == null) {
            System.err.println("Error: ANTHROPIC_AUTH_TOKEN not found in .env file");
            return;
        }
        apiKey = token;

        model = dotenv.get("MODEL_ID", "claude-sonnet-4-20250514");
        baseUrl = dotenv.get("ANTHROPIC_BASE_URL", "https://api.anthropic.com");
        workDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();

        systemPrompt = "You are a coding agent at " + workDir + ". Use tools to solve tasks.";
        tools = setupTools();
        toolHandlers = setupToolHandlers();
        hookManager = new HookManager(workDir);

        // Fire SessionStart hooks
        hookManager.runHooks("SessionStart", Map.of("tool_name", "", "tool_input", Map.of()));

        List<Message> history = new ArrayList<>();

        System.out.print("[36m" + promptName + " >> [0m");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty() || line.trim().equalsIgnoreCase("q") ||
                line.trim().equalsIgnoreCase("exit")) {
                break;
            }

            history.add(createUserMessage(line));
            agentLoop(history);

            printLastResponse(history);
            System.out.print("[36m" + promptName + " >> [0m");
        }
    }

    private Message createUserMessage(String content) {
        List<MessageContent> contents = new ArrayList<>();
        MessageContent textBlock = MessageContent.createText(content);
        contents.add(textBlock);
        return Message.createUserMessage(contents);
    }

    private void printLastResponse(List<Message> history) {
        Message lastMsg = history.get(history.size() - 1);
        if ("assistant".equals(lastMsg.getRole())) {
            for (MessageContent block : lastMsg.getContent()) {
                if ("text".equals(block.getType())) {
                    System.out.println(block.getText());
                } else if ("tool_use".equals(block.getType())) {
                    // tool_use blocks don't have direct text
                }
            }
        }
        System.out.println();
    }

    private void agentLoop(List<Message> messages) {
        while (true) {
            ApiResponse response = callAnthropic(messages);

            String stopReason = response.getStop_reason();
            List<MessageContent> content = response.getContent();

            // Filter out thinking blocks - only keep text and tool_use
            List<MessageContent> filteredContent = new ArrayList<>();
            for (MessageContent block : content) {
                String type = block.getType();
                if ("text".equals(type) || "tool_use".equals(type)) {
                    filteredContent.add(block);
                }
            }

            Message assistantMsg = Message.createAssistantMessage(filteredContent);
            messages.add(assistantMsg);

            if (!"tool_use".equals(stopReason)) {
                return;
            }

            List<MessageContent> results = new ArrayList<>();

            for (MessageContent block : content) {
                if ("tool_use".equals(block.getType())) {
                    String toolId = block.getId();
                    String toolName = block.getName();
                    JsonInput input = block.getInput();

                    // Build hook context
                    Map<String, Object> ctx = new HashMap<>();
                    ctx.put("tool_name", toolName);
                    ctx.put("tool_input", inputToMap(input));

                    // -- PreToolUse hooks --
                    HookResult preResult = hookManager.runHooks("PreToolUse", ctx);

                    // Inject hook messages into results
                    for (String msg : preResult.getMessages()) {
                        MessageContent hookMsg = MessageContent.createToolResult(toolId, "[Hook message]: " + msg);
                        results.add(hookMsg);
                    }

                    if (preResult.isBlocked()) {
                        String reason = preResult.getBlockReason() != null ?
                            preResult.getBlockReason() : "Blocked by hook";
                        MessageContent blockedResult = MessageContent.createToolResult(
                            toolId, "Tool blocked by PreToolUse hook: " + reason);
                        results.add(blockedResult);
                        continue;
                    }

                    // Apply updatedInput if modified by hook
                    JsonInput toolInput = input;
                    if (preResult.getUpdatedInput() != null) {
                        toolInput = mapToJsonInput(preResult.getUpdatedInput());
                    }

                    // -- Execute tool --
                    Function<JsonInput, String> handler = toolHandlers.get(toolName);
                    String output;
                    if (handler != null) {
                        output = handler.apply(toolInput);
                    } else {
                        output = "Unknown tool: " + toolName;
                    }

                    System.out.println("[33m> " + toolName + ": " + truncate(output, 200) + "[0m");

                    // -- PostToolUse hooks --
                    Map<String, Object> postCtx = new HashMap<>(ctx);
                    postCtx.put("tool_output", output);
                    HookResult postResult = hookManager.runHooks("PostToolUse", postCtx);

                    // Inject post-hook messages
                    for (String msg : postResult.getMessages()) {
                        output += "\n[Hook note]: " + msg;
                    }

                    MessageContent result = MessageContent.createToolResult(toolId, output);
                    results.add(result);
                }
            }

            Message toolResultMsg = Message.createUserMessage(results);
            messages.add(toolResultMsg);
        }
    }

    private Map<String, Object> inputToMap(JsonInput input) {
        Map<String, Object> map = new HashMap<>();
        if (input.getCommand() != null) map.put("command", input.getCommand());
        if (input.getPath() != null) map.put("path", input.getPath());
        if (input.getLimit() != null) map.put("limit", input.getLimit());
        if (input.getContent() != null) map.put("content", input.getContent());
        if (input.getOld_text() != null) map.put("old_text", input.getOld_text());
        if (input.getNew_text() != null) map.put("new_text", input.getNew_text());
        return map;
    }

    private JsonInput mapToJsonInput(Map<String, Object> map) {
        JsonInput input = new JsonInput();
        if (map.containsKey("command")) input.setCommand((String) map.get("command"));
        if (map.containsKey("path")) input.setPath((String) map.get("path"));
        if (map.containsKey("limit")) {
            Object limit = map.get("limit");
            if (limit instanceof Number) {
                input.setLimit(((Number) limit).intValue());
            }
        }
        if (map.containsKey("content")) input.setContent((String) map.get("content"));
        if (map.containsKey("old_text")) input.setOld_text((String) map.get("old_text"));
        if (map.containsKey("new_text")) input.setNew_text((String) map.get("new_text"));
        return input;
    }

    private ApiResponse callAnthropic(List<Message> messages) {
        try {
            ApiRequest requestBody = new ApiRequest(model, systemPrompt, messages, tools, 8000);

            String jsonBody = MAPPER.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/messages"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("API Error: " + response.statusCode());
                System.err.println(response.body());
                throw new RuntimeException("API call failed: " + response.statusCode());
            }

            return MAPPER.readValue(response.body(), ApiResponse.class);

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to call Anthropic API", e);
        }
    }

    private List<Tool> setupTools() {
        List<Tool> toolsList = new ArrayList<>();
        toolsList.add(Tool.createBashTool());
        toolsList.add(Tool.createReadTool());
        toolsList.add(Tool.createWriteTool());
        toolsList.add(Tool.createEditTool());
        return toolsList;
    }

    private Map<String, Function<JsonInput, String>> setupToolHandlers() {
        Map<String, Function<JsonInput, String>> handlers = new HashMap<>();
        handlers.put("bash", input -> runBash(input.getCommand()));
        handlers.put("read_file", input -> runRead(
            input.getPath(),
            input.getLimit()
        ));
        handlers.put("write_file", input -> runWrite(
            input.getPath(),
            input.getContent()
        ));
        handlers.put("edit_file", input -> runEdit(
            input.getPath(),
            input.getOld_text(),
            input.getNew_text()
        ));
        return handlers;
    }

    private Path safePath(String pathStr) {
        Path path = workDir.resolve(pathStr).normalize();
        if (!path.startsWith(workDir)) {
            throw new RuntimeException("Path escapes workspace: " + pathStr);
        }
        return path;
    }

    private String runBash(String command) {
        List<String> dangerous = List.of("rm -rf /", "sudo", "shutdown", "reboot", "> /dev/");
        for (String d : dangerous) {
            if (command.contains(d)) {
                return "Error: Dangerous command blocked";
            }
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.directory(workDir.toFile());
            Process process = pb.start();

            boolean completed = process.waitFor(120, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return "Error: Timeout (120s)";
            }

            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());
            String output = (stdout + stderr).trim();

            return output.isEmpty() ? "(no output)" : truncate(output, 50000);

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: " + e.getMessage();
        }
    }

    private String runRead(String pathStr, Integer limit) {
        try {
            Path path = safePath(pathStr);
            String content = Files.readString(path);
            String[] lines = content.split("\n");
            if (limit != null && limit < lines.length) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < limit; i++) {
                    sb.append(lines[i]).append("\n");
                }
                sb.append("... (").append(lines.length - limit).append(" more lines)");
                return sb.toString().trim();
            }
            return content.isEmpty() ? "(empty file)" : truncate(content, 50000);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String runWrite(String pathStr, String content) {
        try {
            Path path = safePath(pathStr);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
            return "Wrote " + content.length() + " bytes to " + pathStr;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String runEdit(String pathStr, String oldText, String newText) {
        try {
            Path path = safePath(pathStr);
            String content = Files.readString(path);
            if (!content.contains(oldText)) {
                return "Error: Text not found in " + pathStr;
            }
            String newContent = content.replace(oldText, newText);
            Files.writeString(path, newContent);
            return "Edited " + pathStr;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String readStream(InputStream stream) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen);
    }

    // ============================================================
    // Hook System Classes
    // ============================================================

    /**
     * HookManager - Load and execute hooks from .hooks.json configuration.
     * The hook manager does three simple jobs:
     * - load hook definitions
     * - run matching commands for an event
     * - aggregate block / message results for the caller
     */
    static class HookManager {
        private final Map<String, List<HookDefinition>> hooks = new HashMap<>();
        private final Path workDir;
        private final Path trustMarker;
        private final Path configPath;
        private final boolean sdkMode;

        HookManager(Path workDir, boolean sdkMode) {
            this.workDir = workDir;
            this.sdkMode = sdkMode;
            this.trustMarker = workDir.resolve(".claude").resolve(".claude_trusted");
            this.configPath = workDir.resolve(".hooks.json");
            this.hooks.put("PreToolUse", new ArrayList<>());
            this.hooks.put("PostToolUse", new ArrayList<>());
            this.hooks.put("SessionStart", new ArrayList<>());
            loadHooks();
        }

        HookManager(Path workDir) {
            this(workDir, false);
        }

        private void loadHooks() {
            if (!Files.exists(configPath)) {
                return;
            }
            try {
                String content = Files.readString(configPath);
                HookConfig config = MAPPER.readValue(content, HookConfig.class);
                Map<String, List<HookDefinition>> configHooks = config.getHooks();
                if (configHooks != null) {
                    for (String event : HOOK_EVENTS) {
                        List<HookDefinition> eventHooks = configHooks.get(event);
                        if (eventHooks != null) {
                            hooks.put(event, eventHooks);
                        }
                    }
                }
                System.out.println("[Hooks loaded from " + configPath + "]");
            } catch (Exception e) {
                System.out.println("[Hook config error: " + e.getMessage() + "]");
            }
        }

        private boolean checkWorkspaceTrust() {
            if (sdkMode) {
                return true;
            }
            return Files.exists(trustMarker);
        }

        /**
         * Execute all hooks for an event.
         * Returns HookResult containing:
         * - blocked: true if any hook returned exit code 1
         * - messages: stderr content from exit-code-2 hooks (to inject)
         * - updatedInput: modified tool input from JSON output
         */
        HookResult runHooks(String event, Map<String, Object> context) {
            HookResult result = new HookResult();

            // Trust gate: refuse to run hooks in untrusted workspaces
            if (!checkWorkspaceTrust()) {
                return result;
            }

            List<HookDefinition> eventHooks = hooks.getOrDefault(event, List.of());
            for (HookDefinition hookDef : eventHooks) {
                // Check matcher (tool name filter for PreToolUse/PostToolUse)
                String matcher = hookDef.getMatcher();
                if (matcher != null && !matcher.isEmpty() && context != null) {
                    String toolName = (String) context.getOrDefault("tool_name", "");
                    if (!"*".equals(matcher) && !matcher.equals(toolName)) {
                        continue;
                    }
                }

                String command = hookDef.getCommand();
                if (command == null || command.isEmpty()) {
                    continue;
                }

                // Build environment with hook context
                Map<String, String> env = new HashMap<>(System.getenv());
                if (context != null) {
                    env.put("HOOK_EVENT", event);
                    env.put("HOOK_TOOL_NAME", (String) context.getOrDefault("tool_name", ""));
                    Object toolInput = context.get("tool_input");
                    if (toolInput != null) {
                        try {
                            String toolInputJson = MAPPER.writeValueAsString(toolInput);
                            env.put("HOOK_TOOL_INPUT", truncate(toolInputJson, 10000));
                        } catch (Exception ignored) {}
                    }
                    if (context.containsKey("tool_output")) {
                        env.put("HOOK_TOOL_OUTPUT", truncate(String.valueOf(context.get("tool_output")), 10000));
                    }
                }

                try {
                    ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
                    pb.directory(workDir.toFile());
                    pb.environment().putAll(env);
                    pb.redirectErrorStream(false);

                    Process process = pb.start();
                    boolean completed = process.waitFor(HOOK_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                    if (!completed) {
                        process.destroyForcibly();
                        System.out.println("  [hook:" + event + "] Timeout (" + HOOK_TIMEOUT_SECONDS + "s)");
                        continue;
                    }

                    String stdout = readStream(process.getInputStream());
                    String stderr = readStream(process.getErrorStream());
                    int exitCode = process.exitValue();

                    if (exitCode == 0) {
                        // Continue silently
                        if (!stdout.trim().isEmpty()) {
                            System.out.println("  [hook:" + event + "] " + truncate(stdout.trim(), 100));
                        }
                        // Try to parse structured stdout
                        try {
                            if (!stdout.trim().isEmpty()) {
                                HookOutput hookOutput = MAPPER.readValue(stdout.trim(), HookOutput.class);
                                if (hookOutput.getUpdatedInput() != null) {
                                    result.setUpdatedInput(hookOutput.getUpdatedInput());
                                }
                                if (hookOutput.getAdditionalContext() != null) {
                                    result.addMessage(hookOutput.getAdditionalContext());
                                }
                                if (hookOutput.getPermissionDecision() != null) {
                                    result.setPermissionOverride(hookOutput.getPermissionDecision());
                                }
                            }
                        } catch (Exception ignored) {
                            // stdout was not JSON -- normal for simple hooks
                        }
                    } else if (exitCode == 1) {
                        // Block execution
                        result.setBlocked(true);
                        String reason = stderr.trim().isEmpty() ? "Blocked by hook" : truncate(stderr.trim(), 200);
                        result.setBlockReason(reason);
                        System.out.println("  [hook:" + event + "] BLOCKED: " + reason);
                    } else if (exitCode == 2) {
                        // Inject message
                        if (!stderr.trim().isEmpty()) {
                            result.addMessage(stderr.trim());
                            System.out.println("  [hook:" + event + "] INJECT: " + truncate(stderr.trim(), 200));
                        }
                    }
                } catch (Exception e) {
                    System.out.println("  [hook:" + event + "] Error: " + e.getMessage());
                }
            }
            return result;
        }

        private String readStream(InputStream stream) throws IOException {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            return sb.toString();
        }

        private String truncate(String s, int maxLen) {
            if (s.length() <= maxLen) return s;
            return s.substring(0, maxLen);
        }
    }

    /**
     * HookResult - Return value from running hooks
     */
    static class HookResult {
        private boolean blocked = false;
        private String blockReason;
        private final List<String> messages = new ArrayList<>();
        private Map<String, Object> updatedInput;
        private String permissionOverride;

        boolean isBlocked() { return blocked; }
        void setBlocked(boolean blocked) { this.blocked = blocked; }

        String getBlockReason() { return blockReason; }
        void setBlockReason(String reason) { this.blockReason = reason; }

        List<String> getMessages() { return messages; }
        void addMessage(String msg) { this.messages.add(msg); }

        Map<String, Object> getUpdatedInput() { return updatedInput; }
        void setUpdatedInput(Map<String, Object> updatedInput) { this.updatedInput = updatedInput; }

        String getPermissionOverride() { return permissionOverride; }
        void setPermissionOverride(String permissionOverride) { this.permissionOverride = permissionOverride; }
    }

    /**
     * HookDefinition - A single hook definition from .hooks.json
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class HookDefinition {
        private String matcher;
        private String command;

        String getMatcher() { return matcher; }
        void setMatcher(String matcher) { this.matcher = matcher; }

        String getCommand() { return command; }
        void setCommand(String command) { this.command = command; }
    }

    /**
     * HookConfig - Root structure of .hooks.json
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class HookConfig {
        private Map<String, List<HookDefinition>> hooks;

        Map<String, List<HookDefinition>> getHooks() { return hooks; }
        void setHooks(Map<String, List<HookDefinition>> hooks) { this.hooks = hooks; }
    }

    /**
     * HookOutput - Structured stdout from a hook (exit code 0)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class HookOutput {
        private Map<String, Object> updatedInput;
        private String additionalContext;
        private String permissionDecision;

        Map<String, Object> getUpdatedInput() { return updatedInput; }
        void setUpdatedInput(Map<String, Object> updatedInput) { this.updatedInput = updatedInput; }

        String getAdditionalContext() { return additionalContext; }
        void setAdditionalContext(String additionalContext) { this.additionalContext = additionalContext; }

        String getPermissionDecision() { return permissionDecision; }
        void setPermissionDecision(String permissionDecision) { this.permissionDecision = permissionDecision; }
    }
}
