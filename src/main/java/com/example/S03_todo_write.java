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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * s03_todo_write.py - TodoWrite
 * <p>
 * The model tracks its own progress via a TodoManager. A nag reminder
 * forces it to keep updating when it forgets.
 * <p>
 * +----------+      +-------+      +---------+
 * |   User   | ---> |  LLM  | ---> | Tools   |
 * |  prompt  |      |       |      | + todo  |
 * +----------+      +---+---+      +----+----+
 *                       ^               |
 *                       |   tool_result |
 *                       +---------------+
 *                             |
 *                 +-----------+-----------+
 *                 | TodoManager state     |
 *                 | [ ] task A            |
 *                 | [>] task B <- doing   |
 *                 | [x] task C            |
 *                 +-----------------------+
 *                             |
 *                 if rounds_since_todo >= 3:
 *                   inject <reminder>
 * <p>
 * Key insight: "The agent can track its own progress -- and I can see it."
 */
public class S03_todo_write {
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

    private TodoManager todoManager;
    private int roundsSinceTodo;

    private final String promptName = "s03";

    public static void main(String[] args) throws Exception {
        new S03_todo_write().runInteractive();
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

        systemPrompt = "You are a coding agent at " + workDir + ". " +
                "Use the todo tool to plan multi-step tasks. Mark in_progress before starting, completed when done. " +
                "Prefer tools over prose.";
        tools = setupTools();
        toolHandlers = setupToolHandlers();
        todoManager = new TodoManager();
        roundsSinceTodo = 0;

        List<Message> history = new ArrayList<>();

        System.out.print("\u001B[36m" + promptName + " >> \u001B[0m");
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
            System.out.print("\u001B[36m" + promptName + " >> \u001B[0m");
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
        roundsSinceTodo = 0;
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
            boolean usedTodo = false;

            for (MessageContent block : content) {
                if ("tool_use".equals(block.getType())) {
                    String toolId = block.getId();
                    String toolName = block.getName();
                    String output;

                    if ("todo".equals(toolName)) {
                        // Handle todo tool separately since it uses TodoItem[]
                        output = todoManager.update(block.getItemsArray());
                        usedTodo = true;
                    } else {
                        JsonInput input = block.getInput();
                        Function<JsonInput, String> handler = toolHandlers.get(toolName);
                        if (handler != null) {
                            output = handler.apply(input);
                        } else {
                            output = "Unknown tool: " + toolName;
                        }
                    }

                    System.out.println("\u001B[33m> " + toolName + ": " + truncate(output, 200) + "\u001B[0m");

                    MessageContent result = MessageContent.createToolResult(toolId, output);
                    results.add(result);
                }
            }

            roundsSinceTodo = usedTodo ? 0 : roundsSinceTodo + 1;

            // Nag reminder if no todo used for 3 rounds
            if (roundsSinceTodo >= 3) {
                results.add(0, MessageContent.createText("<reminder>Update your todos.</reminder>"));
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
        toolsList.add(Tool.createTodoTool());
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

    /**
     * TodoManager - structured state the LLM writes to
     * <p>
     * Manages todo items with validation:
     * - Max 20 items
     * - Each item must have id, text, status
     * - Status must be pending, in_progress, or completed
     * - Only one item can be in_progress at a time
     */
    private static class TodoManager {
        private static final String STATUS_PENDING = "pending";
        private static final String STATUS_IN_PROGRESS = "in_progress";
        private static final String STATUS_COMPLETED = "completed";

        private List<TodoItem> items;

        public TodoManager() {
            this.items = new ArrayList<>();
        }

        public String update(TodoItem[] newItems) {
            if (newItems == null) {
                throw new IllegalArgumentException("Items array is required");
            }
            if (newItems.length > 20) {
                throw new IllegalArgumentException("Max 20 todos allowed");
            }

            List<TodoItem> validated = new ArrayList<>();
            int inProgressCount = 0;

            for (int i = 0; i < newItems.length; i++) {
                TodoItem item = newItems[i];
                String text = item.getText() != null ? item.getText().trim() : "";
                String status = item.getStatus() != null ? item.getStatus().toLowerCase() : STATUS_PENDING;
                String id = item.getId() != null ? item.getId() : String.valueOf(i + 1);

                if (text.isEmpty()) {
                    throw new IllegalArgumentException("Item " + id + ": text required");
                }
                if (!List.of(STATUS_PENDING, STATUS_IN_PROGRESS, STATUS_COMPLETED).contains(status)) {
                    throw new IllegalArgumentException("Item " + id + ": invalid status '" + status + "'");
                }
                if (STATUS_IN_PROGRESS.equals(status)) {
                    inProgressCount++;
                }

                validated.add(new TodoItem(id, text, status));
            }

            if (inProgressCount > 1) {
                throw new IllegalArgumentException("Only one task can be in_progress at a time");
            }

            this.items = validated;
            return render();
        }

        public String render() {
            if (items.isEmpty()) {
                return "No todos.";
            }

            StringBuilder sb = new StringBuilder();
            for (TodoItem item : items) {
                String marker = switch (item.getStatus()) {
                    case STATUS_PENDING -> "[ ]";
                    case STATUS_IN_PROGRESS -> "[>]";
                    case STATUS_COMPLETED -> "[x]";
                    default -> "[ ]";
                };
                sb.append(marker)
                        .append(" #").append(item.getId())
                        .append(": ").append(item.getText())
                        .append("\n");
            }

            long done = items.stream().filter(i -> STATUS_COMPLETED.equals(i.getStatus())).count();
            sb.append("\n(").append(done).append("/").append(items.size()).append(" completed)");

            return sb.toString().trim();
        }
    }
}
