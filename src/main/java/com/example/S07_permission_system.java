package com.example;

import com.example.model.*;
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
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S07: Permission system
 * Every tool call passes through a permission pipeline before execution.
 * Teaching pipeline:
 *   1. deny rules
 *   2. mode check
 *   3. allow rules
 *   4. ask user
 * This version intentionally teaches three modes first:
 *   - default
 *   - plan
 *   - auto
 * That is enough to build a real, understandable permission system without
 * burying readers under every advanced policy branch on day one.
 * Key insight: "Safety is a pipeline, not a boolean."
 */
public class S07_permission_system {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private String model;
    private String baseUrl;
    private String apiKey;
    private String systemPrompt;
    private Path workDir;

    private List<Tool> tools;
    private Map<String, Function<JsonInput, String>> toolHandlers;
    private PermissionManager permissionManager;

    private final String promptName = "s07";

    // Permission modes
    private static final List<String> MODES = List.of("default", "plan", "auto");
    private static final Set<String> READ_ONLY_TOOLS = Set.of("read_file", "bash_readonly");
    private static final Set<String> WRITE_TOOLS = Set.of("write_file", "edit_file", "bash");

    public static void main(String[] args) throws Exception {
        new S07_permission_system().runInteractive();
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

        systemPrompt = "You are a coding agent at " + workDir +
                       ". Use tools to solve tasks. The user controls permissions. " +
                       "Some tool calls may be denied.";
        tools = setupTools();
        toolHandlers = setupToolHandlers();

        // Permission mode selection
        System.out.println("Permission modes: default, plan, auto");
        System.out.print("Mode (default): ");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String modeInput = reader.readLine();
        if (modeInput == null || modeInput.trim().isEmpty()) {
            modeInput = "default";
        } else {
            modeInput = modeInput.trim().toLowerCase();
        }
        if (!MODES.contains(modeInput)) {
            modeInput = "default";
        }

        // Setup default rules
        List<PermissionRule> defaultRules = new ArrayList<>();
        // Always deny dangerous patterns
        defaultRules.add(new PermissionRule("bash", null, "rm -rf /", "deny"));
        defaultRules.add(new PermissionRule("bash", null, "sudo *", "deny"));
        // Allow reading anything
        defaultRules.add(new PermissionRule("read_file", "*", null, "allow"));

        permissionManager = new PermissionManager(modeInput, defaultRules);
        System.out.println("[Permission mode: " + modeInput + "]");

        List<Message> history = new ArrayList<>();

        while (true) {
            System.out.print("\033[36m" + promptName + " >> \033[0m");
            String line = reader.readLine();
            if (line == null || line.trim().isEmpty() ||
                line.trim().equalsIgnoreCase("q") ||
                line.trim().equalsIgnoreCase("exit")) {
                break;
            }

            // /mode command to switch modes at runtime
            if (line.startsWith("/mode")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2 && MODES.contains(parts[1])) {
                    permissionManager.setMode(parts[1]);
                    System.out.println("[Switched to " + parts[1] + " mode]");
                } else {
                    System.out.println("Usage: /mode <" + String.join("|", MODES) + ">");
                }
                continue;
            }

            // /rules command to show current rules
            if (line.trim().equals("/rules")) {
                for (int i = 0; i < permissionManager.getRules().size(); i++) {
                    System.out.println("  " + i + ": " + permissionManager.getRules().get(i));
                }
                continue;
            }

            history.add(createUserMessage(line));
            agentLoop(history);

            printLastResponse(history);
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

                    // Permission check
                    PermissionDecision decision = permissionManager.check(toolName, input);
                    String output;

                    if ("deny".equals(decision.getBehavior())) {
                        output = "Permission denied: " + decision.getReason();
                        System.out.println("  [DENIED] " + toolName + ": " + decision.getReason());
                    } else if ("ask".equals(decision.getBehavior())) {
                        if (permissionManager.askUser(toolName, input)) {
                            Function<JsonInput, String> handler = toolHandlers.get(toolName);
                            output = handler != null ? handler.apply(input) : "Unknown: " + toolName;
                            System.out.println("\u001B[33m> " + toolName + ": " + truncate(output, 200) + "\u001B[0m");
                        } else {
                            output = "Permission denied by user for " + toolName;
                            System.out.println("  [USER DENIED] " + toolName);
                        }
                    } else { // allow
                        Function<JsonInput, String> handler = toolHandlers.get(toolName);
                        output = handler != null ? handler.apply(input) : "Unknown: " + toolName;
                        System.out.println("\u001B[33m> " + toolName + ": " + truncate(output, 200) + "\u001B[0m");
                    }

                    MessageContent result = MessageContent.createToolResult(toolId, output);
                    results.add(result);
                }
            }

            Message toolResultMsg = Message.createUserMessage(results);
            messages.add(toolResultMsg);
        }
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

    private static String truncateJson(JsonInput input, int maxLen) {
        try {
            String json = new ObjectMapper().writeValueAsString(input);
            return json.length() <= maxLen ? json : json.substring(0, maxLen);
        } catch (Exception e) {
            return String.valueOf(input);
        }
    }

    // ========================================================================
    // Bash Security Validator
    // ========================================================================

    /**
     * Validate bash commands for obviously dangerous patterns.
     * The teaching version deliberately keeps this small and easy to read.
     * First catch a few high-risk patterns, then let the permission pipeline
     * decide whether to deny or ask the user.
     */
    static class BashSecurityValidator {

        private static final List<ValidatorEntry> VALIDATORS = Arrays.asList(
            new ValidatorEntry("shell_metachar", "[;&|`$]"),
            new ValidatorEntry("sudo", "\\bsudo\\b"),
            new ValidatorEntry("rm_rf", "\\brm\\s+(-[a-zA-Z]*)?r"),
            new ValidatorEntry("cmd_substitution", "\\$\\("),
            new ValidatorEntry("ifs_injection", "\\bIFS\\s*=")
        );

        /**
         * Check a bash command against all validators.
         * Returns list of (validator_name, matched_pattern) tuples for failures.
         * An empty list means the command passed all validators.
         */
        List<ValidatorEntry> validate(String command) {
            List<ValidatorEntry> failures = new ArrayList<>();
            for (ValidatorEntry validator : VALIDATORS) {
                Pattern pattern = Pattern.compile(validator.pattern);
                Matcher matcher = pattern.matcher(command);
                if (matcher.find()) {
                    failures.add(validator);
                }
            }
            return failures;
        }

        /**
         * Convenience: returns true only if no validators triggered.
         */
        boolean isSafe(String command) {
            return validate(command).isEmpty();
        }

        /**
         * Human-readable summary of validation failures.
         */
        String describeFailures(String command) {
            List<ValidatorEntry> failures = validate(command);
            if (failures.isEmpty()) {
                return "No issues detected";
            }
            List<String> parts = new ArrayList<>();
            for (ValidatorEntry f : failures) {
                parts.add(f.name + " (pattern: " + f.pattern + ")");
            }
            return "Security flags: " + String.join(", ", parts);
        }

        static class ValidatorEntry {
            final String name;
            final String pattern;

            ValidatorEntry(String name, String pattern) {
                this.name = name;
                this.pattern = pattern;
            }

            @Override
            public String toString() {
                return name;
            }
        }
    }

    // ========================================================================
    // Permission Rule
    // ========================================================================

    /**
     * Permission rule: {"tool": "<tool_name_or_*>", "path": "<glob_or_*>",
     *                    "content": "<glob_or_*>", "behavior": "allow|deny|ask"}
     * Rules are checked in order: first match wins.
     */
    static class PermissionRule {
        private final String tool;
        private final String path;
        private final String content;
        private final String behavior;

        PermissionRule(String tool, String path, String content, String behavior) {
            this.tool = tool;
            this.path = path;
            this.content = content;
            this.behavior = behavior;
        }

        String getTool() { return tool; }
        String getPath() { return path; }
        String getContent() { return content; }
        String getBehavior() { return behavior; }

        @Override
        public String toString() {
            return "{tool=" + tool + ", path=" + path + ", content=" + content + ", behavior=" + behavior + "}";
        }
    }

    // ========================================================================
    // Permission Decision
    // ========================================================================

    /**
     * Result of a permission check.
     * Returns: {"behavior": "allow"|"deny"|"ask", "reason": str}
     */
    static class PermissionDecision {
        private final String behavior;
        private final String reason;

        PermissionDecision(String behavior, String reason) {
            this.behavior = behavior;
            this.reason = reason;
        }

        String getBehavior() { return behavior; }
        String getReason() { return reason; }

        @Override
        public String toString() {
            return "{behavior=" + behavior + ", reason=" + reason + "}";
        }
    }

    // ========================================================================
    // Permission Manager
    // ========================================================================

    /**
     * Manages permission decisions for tool calls.
     * Pipeline: deny_rules -> mode_check -> allow_rules -> ask_user
     * The teaching version keeps the decision path short on purpose so readers
     * can implement it themselves before adding more advanced policy layers.
     */
    static class PermissionManager {
        private String mode;
        private final List<PermissionRule> rules;
        private int consecutiveDenials = 0;
        private final int maxConsecutiveDenials = 3;

        private final BashSecurityValidator bashValidator = new BashSecurityValidator();
        private final BufferedReader reader;

        PermissionManager(String mode, List<PermissionRule> rules) {
            if (!MODES.contains(mode)) {
                throw new IllegalArgumentException("Unknown mode: " + mode + ". Choose from " + MODES);
            }
            this.mode = mode;
            this.rules = new ArrayList<>(rules);
            this.reader = new BufferedReader(new InputStreamReader(System.in));
        }

        /**
         * Returns: {"behavior": "allow"|"deny"|"ask", "reason": str}
         */
        PermissionDecision check(String toolName, JsonInput toolInput) {
            // Step 0: Bash security validation (before deny rules)
            // Teaching version checks early for clarity.
            if ("bash".equals(toolName)) {
                String command = toolInput.getCommand();
                List<BashSecurityValidator.ValidatorEntry> failures = bashValidator.validate(command);
                if (!failures.isEmpty()) {
                    // Severe patterns (sudo, rm_rf) get immediate deny
                    Set<String> severe = Set.of("sudo", "rm_rf");
                    List<BashSecurityValidator.ValidatorEntry> severeHits = new ArrayList<>();
                    for (BashSecurityValidator.ValidatorEntry f : failures) {
                        if (severe.contains(f.name)) {
                            severeHits.add(f);
                        }
                    }
                    if (!severeHits.isEmpty()) {
                        String desc = bashValidator.describeFailures(command);
                        return new PermissionDecision("deny", "Bash validator: " + desc);
                    }
                    // Other patterns escalate to ask (user can still approve)
                    String desc = bashValidator.describeFailures(command);
                    return new PermissionDecision("ask", "Bash validator flagged: " + desc);
                }
            }

            // Step 1: Deny rules (bypass-immune, checked first always)
            for (PermissionRule rule : rules) {
                if (!"deny".equals(rule.getBehavior())) {
                    continue;
                }
                if (matches(rule, toolName, toolInput)) {
                    return new PermissionDecision("deny", "Blocked by deny rule: " + rule);
                }
            }

            // Step 2: Mode-based decisions
            if ("plan".equals(mode)) {
                // Plan mode: deny all write operations, allow reads
                if (WRITE_TOOLS.contains(toolName)) {
                    return new PermissionDecision("deny", "Plan mode: write operations are blocked");
                }
                return new PermissionDecision("allow", "Plan mode: read-only allowed");
            }

            if ("auto".equals(mode)) {
                // Auto mode: auto-allow read-only tools, ask for writes
                if (READ_ONLY_TOOLS.contains(toolName) || "read_file".equals(toolName)) {
                    return new PermissionDecision("allow", "Auto mode: read-only tool auto-approved");
                }
                // Teaching: fall through to allow rules, then ask
            }

            // Step 3: Allow rules
            for (PermissionRule rule : rules) {
                if (!"allow".equals(rule.getBehavior())) {
                    continue;
                }
                if (matches(rule, toolName, toolInput)) {
                    consecutiveDenials = 0;
                    return new PermissionDecision("allow", "Matched allow rule: " + rule);
                }
            }

            // Step 4: Ask user (default behavior for unmatched tools)
            return new PermissionDecision("ask", "No rule matched for " + toolName + ", asking user");
        }

        /**
         * Interactive approval prompt. Returns true if approved.
         */
        boolean askUser(String toolName, JsonInput toolInput) {
            String preview = truncateJson(toolInput, 200);
            System.out.println("\n  [Permission] " + toolName + ": " + preview);
            try {
                System.out.print("  Allow? (y/n/always): ");
                String answer = reader.readLine();
                if (answer == null) {
                    return false;
                }
                answer = answer.trim().toLowerCase();

                if ("always".equals(answer)) {
                    // Add permanent allow rule for this tool
                    rules.add(new PermissionRule(toolName, "*", null, "allow"));
                    consecutiveDenials = 0;
                    return true;
                }
                if ("y".equals(answer) || "yes".equals(answer)) {
                    consecutiveDenials = 0;
                    return true;
                }
                // Track denials for circuit breaker
                consecutiveDenials++;
                if (consecutiveDenials >= maxConsecutiveDenials) {
                    System.out.println("  [" + consecutiveDenials + " consecutive denials -- " +
                                      "consider switching to plan mode]");
                }
                return false;
            } catch (IOException e) {
                return false;
            }
        }

        /**
         * Check if a rule matches the tool call.
         */
        private boolean matches(PermissionRule rule, String toolName, JsonInput toolInput) {
            // Tool name match
            if (rule.getTool() != null && !"*".equals(rule.getTool())) {
                if (!rule.getTool().equals(toolName)) {
                    return false;
                }
            }

            // Path pattern match
            if (rule.getPath() != null && !"*".equals(rule.getPath())) {
                String path = toolInput.getPath() != null ? toolInput.getPath() : "";
                if (!fnmatch(path, rule.getPath())) {
                    return false;
                }
            }

            // Content pattern match (for bash commands)
            if (rule.getContent() != null) {
                String command = toolInput.getCommand() != null ? toolInput.getCommand() : "";
                if (!fnmatch(command, rule.getContent())) {
                    return false;
                }
            }

            return true;
        }

        /**
         * Simple glob pattern matching (fnmatch style).
         */
        private boolean fnmatch(String name, String pattern) {
            // Convert glob pattern to regex
            String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
            return name.matches(regex);
        }

        String getMode() { return mode; }

        void setMode(String mode) {
            if (!MODES.contains(mode)) {
                throw new IllegalArgumentException("Unknown mode: " + mode);
            }
            this.mode = mode;
        }

        List<PermissionRule> getRules() { return rules; }
    }
}
