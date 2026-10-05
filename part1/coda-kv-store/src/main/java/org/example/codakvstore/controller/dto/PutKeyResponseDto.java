package org.example.codakvstore.controller.dto;

import lombok.AllArgsConstructor;
import tools.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PutKeyResponseDto {
    private String key;
    private JsonNode value;
    private Integer version;
}
