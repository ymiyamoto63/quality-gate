package com.qualitygate.domain.model;

/**
 * Phase 1 のロール。利用者が増えた時点で
 * Viewer / Developer / Maintainer / Admin の 4 種へ拡張する。
 */
public enum UserRole {
    ADMIN,
    VIEWER;

    public String authority() {
        return "ROLE_" + name();
    }
}
