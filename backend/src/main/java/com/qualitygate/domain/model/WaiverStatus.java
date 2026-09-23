package com.qualitygate.domain.model;

/**
 * 免除の状態。
 *
 * <p>Phase 1 は登録即有効（ACTIVE）。将来の承認フロー（PENDING / REJECTED）は
 * 値の追加だけで足せるよう、状態を列挙で持つ（FR-10-7）。
 */
public enum WaiverStatus {
    ACTIVE,
    EXPIRED,
    REVOKED
}
