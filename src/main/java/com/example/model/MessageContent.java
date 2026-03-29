package com.example.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.ArrayList;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageContent {
    private String type;
    private String text;
    private String id;
    private String name;
    private JsonInput input;
    private String tool_use_id;
    private List<TextContent> content;
    private TodoItem[] items;

    public MessageContent() {
    }

    public static MessageContent createText(String text) {
        MessageContent mc = new MessageContent();
        mc.type = "text";
        mc.text = text;
        return mc;
    }

    public static MessageContent createToolUse(String id, String name, JsonInput input) {
        MessageContent mc = new MessageContent();
        mc.type = "tool_use";
        mc.id = id;
        mc.name = name;
        mc.input = input;
        return mc;
    }

    public static MessageContent createToolResult(String toolUseId, String content) {
        MessageContent mc = new MessageContent();
        mc.type = "tool_result";
        mc.tool_use_id = toolUseId;
        List<TextContent> contentList = new ArrayList<>();
        contentList.add(new TextContent(content));
        mc.content = contentList;
        return mc;
    }

    // Getters and Setters
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public JsonInput getInput() {
        return input;
    }

    public void setInput(JsonInput input) {
        this.input = input;
    }

    public String getTool_use_id() {
        return tool_use_id;
    }

    public void setTool_use_id(String tool_use_id) {
        this.tool_use_id = tool_use_id;
    }

    public List<TextContent> getContent() {
        return content;
    }

    public void setContent(List<TextContent> content) {
        this.content = content;
    }

    public TodoItem[] getItemsArray() {
        return items;
    }

    public void setItemsArray(TodoItem[] items) {
        this.items = items;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TextContent {
        private String type;
        private String text;

        public TextContent() {
        }

        public TextContent(String text) {
            this.type = "text";
            this.text = text;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }
}
