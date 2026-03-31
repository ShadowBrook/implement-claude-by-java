package com.example.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonInput {
    private String command;
    private String path;
    private Integer limit;
    private String content;
    private String old_text;
    private String new_text;
    private String prompt;
    private String description;
    private String skill_name;

    public JsonInput() {
    }

    public static JsonInput forBash(String command) {
        JsonInput input = new JsonInput();
        input.command = command;
        return input;
    }

    public static JsonInput forRead(String path, Integer limit) {
        JsonInput input = new JsonInput();
        input.path = path;
        input.limit = limit;
        return input;
    }

    public static JsonInput forWrite(String path, String content) {
        JsonInput input = new JsonInput();
        input.path = path;
        input.content = content;
        return input;
    }

    public static JsonInput forEdit(String path, String oldText, String newText) {
        JsonInput input = new JsonInput();
        input.path = path;
        input.old_text = oldText;
        input.new_text = newText;
        return input;
    }

    // Getters and Setters
    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getOld_text() {
        return old_text;
    }

    public void setOld_text(String old_text) {
        this.old_text = old_text;
    }

    public String getNew_text() {
        return new_text;
    }

    public void setNew_text(String new_text) {
        this.new_text = new_text;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @JsonProperty("name")
    public String getSkill_name() {
        return skill_name;
    }

    public void setSkill_name(String skill_name) {
        this.skill_name = skill_name;
    }
}
