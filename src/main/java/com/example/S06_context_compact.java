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
 * S06_context_compact.java - Compact
 *
 * Three-layer compression pipeline so the agent can work forever:
 *
 *     Every turn:
 *     +------------------+
 *     | Tool call result |
 *     +------------------+
 *             |
 *             v
 *     [Layer 1: micro_compact]        (silent, every turn)
 *       Replace tool_result content older than last 3
 *       with "[Previous: used {tool_name}]"
 *             |
 *             v
 *     [Check: tokens > 50000?]
 *        |               |
 *        no              yes
 *        |               |
 *        v               v
 *     continue    [Layer 2: auto_compact]
 *                   Save full transcript to .transcripts/
 *                   Ask LLM to summarize conversation.
 *                   Replace all messages with [summary].
 *                         |
 *                         v
 *                 [Layer 3: compact tool]
 *                   Model calls compact -> immediate summarization.
 *                   Same as auto, triggered manually.
 *
 * Key insight: "The agent can forget strategically and keep working forever."
 */
public class S06_context_compact {

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

    private final String promptName = "s06";

    // Compression settings
    private static final int TOKEN_THRESHOLD = 50000;
    private static final int KEEP_RECENT_TOOL_RESULTS = 3;
    private static final Path TRANSCRIPT_DIR = Paths.get(".transcripts");

    private boolean autoCompactInProgress = false;

    public static void main(String[] args) throws Exception {
        new S06_context_compact().runInteractive();
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
        while (true) {
            // Layer 1: micro_compact before each LLM call
            microCompact(messages);

            // Layer 2: auto_compact if token estimate exceeds threshold
            if (estimateTokens(messages) > TOKEN_THRESHOLD) {
                System.out.println("[auto_compact triggered]");
                messages = autoCompact(messages);
            }

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
            boolean manualCompact = false;

            for (MessageContent block : content) {
                if ("tool_use".equals(block.getType())) {
                    String toolId = block.getId();
                    String toolName = block.getName();
                    JsonInput input = block.getInput();

                    // Check if compact tool was called
                    if ("compact".equals(toolName)) {
                        manualCompact = true;
                        System.out.println("\u001B[33m> compact: Compressing...\u001B[0m");
                        continue; // Don't add tool result for compact
                    }

                    Function<JsonInput, String> handler = toolHandlers.get(toolName);
                    String output;
                    if (handler != null) {
                        output = handler.apply(input);
                    } else {
                        output = "Unknown tool: " + toolName;
                    }

                    System.out.println("\u001B[33m> " + toolName + ": " + truncate(output, 200) + "\u001B[0m");

                    MessageContent result = MessageContent.createToolResult(toolId, output);
                    results.add(result);
                }
            }

            Message toolResultMsg = Message.createUserMessage(results);
            messages.add(toolResultMsg);

            // Layer 3: manual compact triggered by the compact tool
            if (manualCompact) {
                System.out.println("[manual compact]");
                messages = autoCompact(messages);
            }
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
        toolsList.add(Tool.createCompactTool());
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

    // ==================== COMPRESSION METHODS ====================

    /**
     * Rough token count estimation: ~4 chars per token.
     */
    private int estimateTokens(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            sb.append(msg.toString());
        }
        return sb.length() / 4;
    }

    /**
     * Layer 1: micro_compact - replace old tool results with placeholders.
     * Keeps only the last KEEP_RECENT_TOOL_RESULTS tool results uncompressed.
     */
    private void microCompact(List<Message> messages) {
        // Collect all tool_result indices
        List<ToolResultLocation> toolResults = new ArrayList<>();
        for (int msgIdx = 0; msgIdx < messages.size(); msgIdx++) {
            Message msg = messages.get(msgIdx);
            if ("user".equals(msg.getRole())) {
                List<MessageContent> contents = msg.getContent();
                if (contents != null) {
                    for (int partIdx = 0; partIdx < contents.size(); partIdx++) {
                        MessageContent part = contents.get(partIdx);
                        if ("tool_result".equals(part.getType())) {
                            toolResults.add(new ToolResultLocation(msgIdx, partIdx, part));
                        }
                    }
                }
            }
        }

        if (toolResults.size() <= KEEP_RECENT_TOOL_RESULTS) {
            return;
        }

        // Build tool_name map from assistant messages
        Map<String, String> toolNameMap = new HashMap<>();
        for (Message msg : messages) {
            if ("assistant".equals(msg.getRole())) {
                List<MessageContent> contents = msg.getContent();
                if (contents != null) {
                    for (MessageContent block : contents) {
                        if ("tool_use".equals(block.getType())) {
                            toolNameMap.put(block.getId(), block.getName());
                        }
                    }
                }
            }
        }

        // Clear old results (keep last KEEP_RECENT_TOOL_RESULTS)
        int toClearCount = toolResults.size() - KEEP_RECENT_TOOL_RESULTS;
        for (int i = 0; i < toClearCount; i++) {
            ToolResultLocation loc = toolResults.get(i);
            MessageContent result = loc.content;

            // Check if content has TextContent list
            List<MessageContent.TextContent> textContents = result.getContent();
            if (textContents != null && !textContents.isEmpty()) {
                String firstText = textContents.get(0).getText();
                if (firstText != null && firstText.length() > 100) {
                    String toolId = result.getTool_use_id();
                    String toolName = toolNameMap.getOrDefault(toolId, "unknown");
                    textContents.get(0).setText("[Previous: used " + toolName + "]");
                }
            }
        }
    }

    /**
     * Layer 2 & 3: auto_compact - save transcript, summarize, replace messages.
     */
    private List<Message> autoCompact(List<Message> messages) {
        // Save full transcript to disk
        Path transcriptPath = saveTranscript(messages);
        System.out.println("[transcript saved: " + transcriptPath + "]");

        // Ask LLM to summarize
        String conversationText = messages.toString();
        if (conversationText.length() > 80000) {
            conversationText = conversationText.substring(0, 80000);
        }

        String summary = summarizeConversation(conversationText);

        // Replace all messages with compressed summary
        List<Message> compressed = new ArrayList<>();

        List<MessageContent> userContents = new ArrayList<>();
        MessageContent summaryContent = MessageContent.createText(
            "[Conversation compressed. Transcript: " + transcriptPath + "]\n\n" + summary
        );
        userContents.add(summaryContent);
        compressed.add(Message.createUserMessage(userContents));

        List<MessageContent> assistantContents = new ArrayList<>();
        MessageContent ackContent = MessageContent.createText(
            "Understood. I have the context from the summary. Continuing."
        );
        assistantContents.add(ackContent);
        compressed.add(Message.createAssistantMessage(assistantContents));

        return compressed;
    }

    /**
     * Save transcript to .transcripts/ directory.
     */
    private Path saveTranscript(List<Message> messages) {
        try {
            Files.createDirectories(TRANSCRIPT_DIR);
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            Path transcriptPath = TRANSCRIPT_DIR.resolve("transcript_" + timestamp + ".jsonl");

            StringBuilder sb = new StringBuilder();
            for (Message msg : messages) {
                sb.append(MAPPER.writeValueAsString(msg)).append("\n");
            }
            Files.writeString(transcriptPath, sb.toString());

            return transcriptPath.toAbsolutePath();
        } catch (IOException e) {
            System.err.println("Failed to save transcript: " + e.getMessage());
            return Paths.get("unknown");
        }
    }

    /**
     * Call LLM to summarize the conversation.
     */
    private String summarizeConversation(String conversationText) {
        try {
            String summaryRequest = "Summarize this conversation for continuity. Include: " +
                "1) What was accomplished, 2) Current state, 3) Key decisions made. " +
                "Be concise but preserve critical details.\n\n" + conversationText;

            List<MessageContent> content = new ArrayList<>();
            content.add(MessageContent.createText(summaryRequest));
            List<Message> summaryMessages = new ArrayList<>();
            summaryMessages.add(Message.createUserMessage(content));

            ApiRequest requestBody = new ApiRequest(model, null, summaryMessages, null, 2000);
            String jsonBody = MAPPER.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/messages"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("Summary API Error: " + response.statusCode());
                return "[Summary failed to generate]";
            }

            ApiResponse apiResponse = MAPPER.readValue(response.body(), ApiResponse.class);
            List<MessageContent> responseContent = apiResponse.getContent();
            if (responseContent != null && !responseContent.isEmpty()) {
                MessageContent firstBlock = responseContent.get(0);
                if (firstBlock.getText() != null) {
                    return firstBlock.getText();
                }
            }
            return "[Summary failed to generate]";

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return "[Summary error: " + e.getMessage() + "]";
        }
    }

    /**
     * Helper class to track tool result locations in messages.
     */
    private static class ToolResultLocation {
        final int msgIndex;
        final int partIndex;
        final MessageContent content;

        ToolResultLocation(int msgIndex, int partIndex, MessageContent content) {
            this.msgIndex = msgIndex;
            this.partIndex = partIndex;
            this.content = content;
        }
    }
}
