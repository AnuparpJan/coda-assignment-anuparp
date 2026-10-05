package org.example.codakvstore.model;

import tools.jackson.databind.JsonNode;

public record KvEntry(JsonNode value, int version) {}
