package com.example.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Message {
    private String role;
    private List<MessageContent> content;

    public Message() {
    }

    public static Message createUserMessage(List<MessageContent> content) {
        Message msg = new Message();
        msg.role = "user";
        msg.content = content;
        return msg;
    }

    public static Message createAssistantMessage(List<MessageContent> content) {
        Message msg = new Message();
        msg.role = "assistant";
        msg.content = content;
        return msg;
    }

    // Getters and Setters
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
}
