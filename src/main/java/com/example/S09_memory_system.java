package com.example;

import com.example.model.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Memory System
 * This teaching version focuses on one core idea:
 * some information should survive the current conversation, but not everything
 * belongs in memory.
 * Use memory for:
 *   - user preferences
 *   - repeated user feedback
 *   - project facts that are NOT obvious from the current code
 *   - pointers to external resources
 * Do NOT use memory for:
 *   - code structure that can be re-read from the repo
 *   - temporary task state
 *   - secrets
 * Storage layout:
 *   .memory/
 *     MEMORY.md
 *     prefer_tabs.md
 *     review_style.md
 *     incident_board.md
 * Each memory is a small Markdown file with frontmatter.
 * The agent can save a memory through save_memory(), and the memory index
 * is rebuilt after each write.
 * An optional "Dream" pass can later consolidate, deduplicate, and prune
 * stored memories. It is useful, but it is not the first thing readers need
 * to understand.
 * Key insight: "Memory only stores cross-session information that is still
 * worth recalling later and is not easy to re-derive from the current repo."
 */
public class S09_memory_system {

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
    private MemoryManager memoryManager;
    private DreamConsolidator dreamConsolidator;

    private final String promptName = "s09";

    private static final String MEMORY_GUIDANCE =
        "When to save memories:\n" +
        "- User states a preference (\"I like tabs\", \"always use pytest\") -> type: user\n" +
        "- User corrects you (\"don't do X\", \"that was wrong because...\") -> type: feedback\n" +
        "- You learn a project fact that is not easy to infer from current code alone\n" +
        "  (for example: a rule exists because of compliance, or a legacy module must\n" +
        "  stay untouched for business reasons) -> type: project\n" +
        "- You learn where an external resource lives (ticket board, dashboard, docs URL)\n" +
        "  -> type: reference\n" +
        "When NOT to save:\n" +
        "- Anything easily derivable from code (function signatures, file structure, directory layout)\n" +
        "- Temporary task state (current branch, open PR numbers, current TODOs)\n" +
        "- Secrets or credentials (API keys, passwords)\n";

    public static void main(String[] args) throws Exception {
        new S09_memory_system().runInteractive();
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

        memoryDir = workDir.resolve(".memory");
        memoryManager = new MemoryManager(workDir);
        memoryManager.loadAll();
        dreamConsolidator = new DreamConsolidator();

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
        if (map.containsKey("name")) input.setName((String) map.get("name"));
        if (map.containsKey("description")) input.setDescription((String) map.get("description"));
        if (map.containsKey("type")) input.setType((String) map.get("type"));
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
        toolsList.add(createSaveMemoryTool());
        return toolsList;
    }

    private Tool createSaveMemoryTool() {
        Tool tool = new Tool();
        tool.setName("save_memory");
        tool.setDescription("Save a persistent memory that survives across sessions.");

        ToolInputSchema.SchemaProperties schemaProps = new ToolInputSchema.SchemaProperties();
        schemaProps.setName(new ToolInputSchema.Property("string"));
        schemaProps.setDescription(new ToolInputSchema.Property("string"));
        schemaProps.setType(new ToolInputSchema.Property("string"));
        schemaProps.setContent(new ToolInputSchema.Property("string"));

        ToolInputSchema schema = new ToolInputSchema();
        schema.setType("object");
        schema.setProperties(schemaProps);
        schema.setRequired(List.of("name", "description", "type", "content"));
        tool.setInput_schema(schema);
        return tool;
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
        handlers.put("save_memory", input -> memoryManager.saveMemory(
            input.getName(),
            input.getDescription(),
            input.getType(),
            input.getContent()
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

    // ============================================================
    // MemoryManager - Persistent memory across sessions
    // ============================================================

    private static final String[] MEMORY_TYPES = {"user", "feedback", "project", "reference"};
    private static final int MAX_INDEX_LINES = 200;
    private static Path memoryDir;

    static class MemoryManager {
        private final Map<String, MemoryEntry> memories = new LinkedHashMap<>();
        private final Path workDir;

        MemoryManager(Path workDir) {
            this.workDir = workDir;
        }

        void loadAll() {
            memories.clear();
            File dir = memoryDir.toFile();
            if (!dir.exists() || !dir.isDirectory()) {
                return;
            }

            File[] files = dir.listFiles(new FilenameFilter() {
                public boolean accept(File d, String name) {
                    return name.endsWith(".md") && !name.equals("MEMORY.md");
                }
            });
            if (files == null) return;

            Arrays.sort(files);
            for (File mdFile : files) {
                try {
                    MemoryEntry entry = parseFrontmatter(mdFile);
                    if (entry != null) {
                        memories.put(entry.name, entry);
                    }
                } catch (Exception e) {
                    System.err.println("Warning: failed to parse " + mdFile.getName() + ": " + e.getMessage());
                }
            }

            int count = memories.size();
            if (count > 0) {
                System.out.println("[Memory loaded: " + count + " memories from " + memoryDir + "]");
            }
        }

        MemoryEntry parseFrontmatter(File mdFile) throws IOException {
            String content = new String(Files.readAllBytes(mdFile.toPath()));
            Pattern pattern = Pattern.compile("^---\\s*\n(.*?)\\n---\\s*\n(.*)", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(content);
            if (!matcher.find()) {
                return null;
            }

            String header = matcher.group(1);
            String body = matcher.group(2).trim();

            MemoryEntry entry = new MemoryEntry();
            entry.content = body;
            entry.file = mdFile.getName();

            for (String line : header.split("\n")) {
                int colonIdx = line.indexOf(':');
                if (colonIdx > 0) {
                    String key = line.substring(0, colonIdx).trim();
                    String value = line.substring(colonIdx + 1).trim();
                    if ("name".equals(key)) {
                        entry.name = value;
                    } else if ("description".equals(key)) {
                        entry.description = value;
                    } else if ("type".equals(key)) {
                        entry.type = value;
                    }
                }
            }

            if (entry.name == null || entry.name.isEmpty()) {
                entry.name = mdFile.getName().replace(".md", "");
            }
            if (entry.type == null || entry.type.isEmpty()) {
                entry.type = "project";
            }

            return entry;
        }

        String loadMemoryPrompt() {
            if (memories.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("# Memories (persistent across sessions)\n\n");

            for (String memType : MEMORY_TYPES) {
                Map<String, MemoryEntry> typed = new LinkedHashMap<>();
                for (Map.Entry<String, MemoryEntry> e : memories.entrySet()) {
                    if (memType.equals(e.getValue().type)) {
                        typed.put(e.getKey(), e.getValue());
                    }
                }
                if (typed.isEmpty()) continue;

                sb.append("## [").append(memType).append("]\n");
                for (Map.Entry<String, MemoryEntry> e : typed.entrySet()) {
                    MemoryEntry mem = e.getValue();
                    sb.append("### ").append(e.getKey()).append(": ").append(mem.description).append("\n");
                    if (mem.content != null && !mem.content.isEmpty()) {
                        sb.append(mem.content).append("\n");
                    }
                    sb.append("\n");
                }
            }

            return sb.toString();
        }

        String saveMemory(String name, String description, String memType, String content) {
            boolean validType = false;
            for (String t : MEMORY_TYPES) {
                if (t.equals(memType)) {
                    validType = true;
                    break;
                }
            }
            if (!validType) {
                return "Error: type must be one of " + Arrays.toString(MEMORY_TYPES);
            }

            String safeName = name.toLowerCase().replaceAll("[^a-zA-Z0-9_-]", "_");
            if (safeName.isEmpty()) {
                return "Error: invalid memory name";
            }

            try {
                Files.createDirectories(memoryDir);
            } catch (IOException e) {
                return "Error: cannot create memory directory: " + e.getMessage();
            }

            String frontmatter = "---\n" +
                    "name: " + name + "\n" +
                    "description: " + description + "\n" +
                    "type: " + memType + "\n" +
                    "---\n" +
                    content + "\n";

            String fileName = safeName + ".md";
            Path filePath = memoryDir.resolve(fileName);
            try {
                Files.writeString(filePath, frontmatter);
            } catch (IOException e) {
                return "Error: cannot write memory file: " + e.getMessage();
            }

            MemoryEntry entry = new MemoryEntry();
            entry.name = name;
            entry.description = description;
            entry.type = memType;
            entry.content = content;
            entry.file = fileName;
            memories.put(name, entry);

            rebuildIndex();

            return "Saved memory '" + name + "' [" + memType + "] to " + workDir.relativize(filePath);
        }

        void rebuildIndex() {
            List<String> lines = new ArrayList<>();
            lines.add("# Memory Index");
            lines.add("");

            for (Map.Entry<String, MemoryEntry> e : memories.entrySet()) {
                lines.add("- " + e.getKey() + ": " + e.getValue().description + " [" + e.getValue().type + "]");
                if (lines.size() >= MAX_INDEX_LINES) {
                    lines.add("... (truncated at " + MAX_INDEX_LINES + " lines)");
                    break;
                }
            }

            try {
                Files.createDirectories(memoryDir);
                Path indexPath = memoryDir.resolve("MEMORY.md");
                Files.writeString(indexPath, String.join("\n", lines) + "\n");
            } catch (IOException e) {
                System.err.println("Warning: failed to rebuild index: " + e.getMessage());
            }
        }

        Map<String, MemoryEntry> getMemories() {
            return memories;
        }
    }

    // ============================================================
    // DreamConsolidator - Auto-consolidation of memories
    // ============================================================

    static class DreamConsolidator {
        private static final long COOLDOWN_SECONDS = 86400;
        private static final long SCAN_THROTTLE_SECONDS = 600;
        private static final int MIN_SESSION_COUNT = 5;
        private static final long LOCK_STALE_SECONDS = 3600;

        private static final String[] PHASES = {
            "Orient: scan MEMORY.md index for structure and categories",
            "Gather: read individual memory files for full content",
            "Consolidate: merge related memories, remove stale entries",
            "Prune: enforce 200-line limit on MEMORY.md index"
        };

        private boolean enabled = true;
        private String mode = "default";
        private long lastConsolidationTime = 0;
        private long lastScanTime = 0;
        private int sessionCount = 0;

        DreamConsolidator() {
        }

        DreamResult shouldConsolidate() {
            long now = System.currentTimeMillis() / 1000;

            if (!enabled) {
                return new DreamResult(false, "Gate 1: consolidation is disabled");
            }

            if (!memoryDir.toFile().exists()) {
                return new DreamResult(false, "Gate 2: memory directory does not exist");
            }
            File[] memoryFiles = memoryDir.toFile().listFiles(new FilenameFilter() {
                public boolean accept(File d, String name) {
                    return name.endsWith(".md") && !name.equals("MEMORY.md");
                }
            });
            if (memoryFiles == null || memoryFiles.length == 0) {
                return new DreamResult(false, "Gate 2: no memory files found");
            }

            if ("plan".equals(mode)) {
                return new DreamResult(false, "Gate 3: plan mode does not allow consolidation");
            }

            long timeSinceLast = now - lastConsolidationTime;
            if (timeSinceLast < COOLDOWN_SECONDS) {
                long remaining = COOLDOWN_SECONDS - timeSinceLast;
                return new DreamResult(false, "Gate 4: cooldown active, " + remaining + "s remaining");
            }

            long timeSinceScan = now - lastScanTime;
            if (timeSinceScan < SCAN_THROTTLE_SECONDS) {
                long remaining = SCAN_THROTTLE_SECONDS - timeSinceScan;
                return new DreamResult(false, "Gate 5: scan throttle active, " + remaining + "s remaining");
            }

            if (sessionCount < MIN_SESSION_COUNT) {
                return new DreamResult(false, "Gate 6: only " + sessionCount + " sessions, need " + MIN_SESSION_COUNT);
            }

            Path lockFile = memoryDir.resolve(".dream_lock");
            if (lockFile.toFile().exists()) {
                try {
                    String lockData = new String(Files.readAllBytes(lockFile)).trim();
                    String[] parts = lockData.split(":", 2);
                    int pid = Integer.parseInt(parts[0]);
                    long lockTime = (long) Double.parseDouble(parts[1]);

                    if ((now - lockTime) > LOCK_STALE_SECONDS) {
                        System.out.println("[Dream] Removing stale lock from PID " + pid);
                        Files.deleteIfExists(lockFile);
                    } else {
                        Optional<ProcessHandle> optHandle = ProcessHandle.of(pid);
                        if (optHandle.isPresent() && optHandle.get().isAlive()) {
                            return new DreamResult(false, "Gate 7: lock held by another process");
                        }
                        System.out.println("[Dream] Removing lock from dead PID " + pid);
                        Files.deleteIfExists(lockFile);
                    }
                } catch (Exception e) {
                    try { Files.deleteIfExists(lockFile); } catch (IOException ignored) {}
                }
            }

            return new DreamResult(true, "All 7 gates passed");
        }

        List<String> consolidate() {
            DreamResult result = shouldConsolidate();
            if (!result.canRun) {
                System.out.println("[Dream] Cannot consolidate: " + result.reason);
                return Collections.emptyList();
            }

            System.out.println("[Dream] Starting consolidation...");
            lastScanTime = System.currentTimeMillis() / 1000;

            Path lockFile = memoryDir.resolve(".dream_lock");
            try {
                Files.writeString(lockFile, ProcessHandle.current().pid() + ":" + (System.currentTimeMillis() / 1000));
            } catch (IOException e) {
                System.err.println("[Dream] Failed to acquire lock: " + e.getMessage());
                return Collections.emptyList();
            }

            List<String> completedPhases = new ArrayList<>();
            for (int i = 0; i < PHASES.length; i++) {
                System.out.println("[Dream] Phase " + (i + 1) + "/" + PHASES.length + ": " + PHASES[i]);
                completedPhases.add(PHASES[i]);
            }

            lastConsolidationTime = System.currentTimeMillis() / 1000;

            try { Files.deleteIfExists(lockFile); } catch (IOException ignored) {}

            System.out.println("[Dream] Consolidation complete: " + completedPhases.size() + " phases executed");
            return completedPhases;
        }

        void incrementSessionCount() { sessionCount++; }

        static class DreamResult {
            boolean canRun;
            String reason;
            DreamResult(boolean canRun, String reason) { this.canRun = canRun; this.reason = reason; }
        }
    }

    // ============================================================
    // Data Classes
    // ============================================================

    static class MemoryEntry {
        String name;
        String description;
        String type;
        String content;
        String file;
    }
}
