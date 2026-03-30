package com.example.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolInputSchema {
    private String type;
    private SchemaProperties properties;
    private List<String> required;

    public ToolInputSchema() {
    }

    public static ToolInputSchema createBashSchema() {
        ToolInputSchema schema = new ToolInputSchema();
        schema.type = "object";
        SchemaProperties props = new SchemaProperties();
        props.command = new Property("string");
        schema.properties = props;
        schema.required = List.of("command");
        return schema;
    }

    public static ToolInputSchema createReadSchema() {
        ToolInputSchema schema = new ToolInputSchema();
        schema.type = "object";
        SchemaProperties props = new SchemaProperties();
        props.path = new Property("string");
        props.limit = new Property("integer");
        schema.properties = props;
        schema.required = List.of("path");
        return schema;
    }

    public static ToolInputSchema createWriteSchema() {
        ToolInputSchema schema = new ToolInputSchema();
        schema.type = "object";
        SchemaProperties props = new SchemaProperties();
        props.path = new Property("string");
        props.content = new Property("string");
        schema.properties = props;
        schema.required = List.of("path", "content");
        return schema;
    }

    public static ToolInputSchema createEditSchema() {
        ToolInputSchema schema = new ToolInputSchema();
        schema.type = "object";
        SchemaProperties props = new SchemaProperties();
        props.path = new Property("string");
        props.old_text = new Property("string");
        props.new_text = new Property("string");
        schema.properties = props;
        schema.required = List.of("path", "old_text", "new_text");
        return schema;
    }

    public static ToolInputSchema createTodoSchema() {
        ToolInputSchema schema = new ToolInputSchema();
        schema.type = "object";
        SchemaProperties props = new SchemaProperties();
        props.items = new ItemsProperty();
        schema.properties = props;
        schema.required = List.of("items");
        return schema;
    }

    public static ToolInputSchema createTaskSchema() {
        ToolInputSchema schema = new ToolInputSchema();
        schema.type = "object";
        SchemaProperties props = new SchemaProperties();
        props.prompt = new Property("string");
        props.description = new Property("string");
        schema.properties = props;
        schema.required = List.of("prompt");
        return schema;
    }

    // Getters and Setters
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public SchemaProperties getProperties() {
        return properties;
    }

    public void setProperties(SchemaProperties properties) {
        this.properties = properties;
    }

    public List<String> getRequired() {
        return required;
    }

    public void setRequired(List<String> required) {
        this.required = required;
    }

    // Inner class for properties
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SchemaProperties {
        private Property command;
        private Property path;
        private Property limit;
        private Property content;
        private Property old_text;
        private Property new_text;
        private ItemsProperty items;
        private Property prompt;
        private Property description;

        public Property getCommand() { return command; }
        public void setCommand(Property command) { this.command = command; }
        public Property getPath() { return path; }
        public void setPath(Property path) { this.path = path; }
        public Property getLimit() { return limit; }
        public void setLimit(Property limit) { this.limit = limit; }
        public Property getContent() { return content; }
        public void setContent(Property content) { this.content = content; }
        public Property getOld_text() { return old_text; }
        public void setOld_text(Property old_text) { this.old_text = old_text; }
        public Property getNew_text() { return new_text; }
        public void setNew_text(Property new_text) { this.new_text = new_text; }
        public ItemsProperty getItems() { return items; }
        public void setItems(ItemsProperty items) { this.items = items; }
        public Property getPrompt() { return prompt; }
        public void setPrompt(Property prompt) { this.prompt = prompt; }
        public Property getDescription() { return description; }
        public void setDescription(Property description) { this.description = description; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Property {
        private String type;
        private List<String> enumValues;

        public Property() {
        }

        public Property(String type) {
            this.type = type;
        }

        public Property(String type, List<String> enumValues) {
            this.type = type;
            this.enumValues = enumValues;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        @com.fasterxml.jackson.annotation.JsonProperty("enum")
        public List<String> getEnumValues() {
            return enumValues;
        }

        public void setEnumValues(List<String> enumValues) {
            this.enumValues = enumValues;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ItemsProperty {
        private String type;
        private ItemsItems items;

        public ItemsProperty() {
            this.type = "array";
            this.items = new ItemsItems();
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public ItemsItems getItems() { return items; }
        public void setItems(ItemsItems items) { this.items = items; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ItemsItems {
        private String type;
        private ItemsProperties properties;
        private List<String> required;

        public ItemsItems() {
            this.type = "object";
            this.properties = new ItemsProperties();
            this.required = List.of("id", "text", "status");
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public ItemsProperties getProperties() { return properties; }
        public void setProperties(ItemsProperties properties) { this.properties = properties; }
        public List<String> getRequired() { return required; }
        public void setRequired(List<String> required) { this.required = required; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ItemsProperties {
        private Property id;
        private Property text;
        private Property status;

        public ItemsProperties() {
            this.id = new Property("string");
            this.text = new Property("string");
            this.status = new Property("string", List.of("pending", "in_progress", "completed"));
        }

        public Property getId() { return id; }
        public void setId(Property id) { this.id = id; }
        public Property getText() { return text; }
        public void setText(Property text) { this.text = text; }
        public Property getStatus() { return status; }
        public void setStatus(Property status) { this.status = status; }
    }
}
