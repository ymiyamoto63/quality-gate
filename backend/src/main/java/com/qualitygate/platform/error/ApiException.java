package com.qualitygate.platform.error;

import java.util.Map;

/** API のエラー応答に変換される業務例外。 */
public class ApiException extends RuntimeException {

    private final transient ErrorCode errorCode;
    private final transient Map<String, Object> properties;

    public ApiException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, Map.of());
    }

    public ApiException(ErrorCode errorCode, String detail, Map<String, Object> properties) {
        super(detail);
        this.errorCode = errorCode;
        this.properties = properties;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    public static ApiException notFound(String what, Object id) {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "%s が見つかりません: %s".formatted(what, id));
    }
}
