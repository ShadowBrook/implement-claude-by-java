package com.example.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse {
    private String id;
    private String type;
    private String role;
    private List<MessageContent> content;
    private String model;
    private String stop_reason;
    private String stop_sequence;
    private Usage usage;

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public List<MessageContent> getContent() {
        return content;
    }

    public void setContent(List<MessageContent> content) {
        this.content = content;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getStop_reason() {
        return stop_reason;
    }

    public void setStop_reason(String stop_reason) {
        this.stop_reason = stop_reason;
    }

    public String getStop_sequence() {
        return stop_sequence;
    }

    public void setStop_sequence(String stop_sequence) {
        this.stop_sequence = stop_sequence;
    }

    public Usage getUsage() {
        return usage;
    }

    public void setUsage(Usage usage) {
        this.usage = usage;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Usage {
        private Integer input_tokens;
        private Integer output_tokens;

        public Integer getInput_tokens() {
            return input_tokens;
        }

        public void setInput_tokens(Integer input_tokens) {
            this.input_tokens = input_tokens;
        }

        public Integer getOutput_tokens() {
            return output_tokens;
        }

        public void setOutput_tokens(Integer output_tokens) {
            this.output_tokens = output_tokens;
        }
    }
}
