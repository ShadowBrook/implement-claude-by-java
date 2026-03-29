package com.example.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TodoInput {
    private TodoItem[] items;

    public TodoInput() {}

    public TodoItem[] getItems() { return items; }
    public void setItems(TodoItem[] items) { this.items = items; }
}
