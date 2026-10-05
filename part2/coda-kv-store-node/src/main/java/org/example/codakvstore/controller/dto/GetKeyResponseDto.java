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
public class GetKeyResponseDto {
    private String key;
    private JsonNode value;
    private Integer version;
}
