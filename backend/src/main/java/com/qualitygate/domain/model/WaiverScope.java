package com.qualitygate.domain.model;

/** 免除の単位（FR-10-1 / FR-10-6）。 */
public enum WaiverScope {
    /** 個別の違反（fingerprint で指す）。 */
    FINDING,
    /** 指標そのもの。設定中はダッシュボードに常時警告を出す。 */
    METRIC
}
