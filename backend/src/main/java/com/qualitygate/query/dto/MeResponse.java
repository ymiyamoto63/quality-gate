package com.qualitygate.query.dto;

import jakarta.validation.constraints.NotNull;

public record MeResponse(@NotNull String username) {
}
