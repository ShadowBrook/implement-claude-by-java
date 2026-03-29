package com.example.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Tool {
    private String name;
    private String description;
    private ToolInputSchema input_schema;

    public Tool() {
    }

    public static Tool createBashTool() {
        Tool tool = new Tool();
        tool.name = "bash";
        tool.description = "Run a shell command.";
        tool.input_schema = ToolInputSchema.createBashSchema();
        return tool;
    }

    public static Tool createReadTool() {
        Tool tool = new Tool();
        tool.name = "read_file";
        tool.description = "Read file contents.";
        tool.input_schema = ToolInputSchema.createReadSchema();
        return tool;
    }

    public static Tool createWriteTool() {
        Tool tool = new Tool();
        tool.name = "write_file";
        tool.description = "Write content to file.";
        tool.input_schema = ToolInputSchema.createWriteSchema();
        return tool;
    }

    public static Tool createEditTool() {
        Tool tool = new Tool();
        tool.name = "edit_file";
        tool.description = "Replace exact text in file.";
        tool.input_schema = ToolInputSchema.createEditSchema();
        return tool;
    }

    public static Tool createTodoTool() {
        Tool tool = new Tool();
        tool.name = "todo";
        tool.description = "Update task list. Track progress on multi-step tasks.";
        tool.input_schema = ToolInputSchema.createTodoSchema();
        return tool;
    }

    // Getters and Setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ToolInputSchema getInput_schema() {
        return input_schema;
    }

    public void setInput_schema(ToolInputSchema input_schema) {
        this.input_schema = input_schema;
    }
}
