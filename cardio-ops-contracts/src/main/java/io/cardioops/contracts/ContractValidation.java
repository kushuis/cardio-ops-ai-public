package io.cardioops.contracts;

import java.util.Objects;
import java.util.regex.Pattern;
/**
 * 跨系统引用只允许稳定的 ID 字符，长度也有上限。姓名、自由文本和控制字符进了业务键，日志和幂等键都会被带脏。
 */
final class ContractValidation {

    // 跨系统引用只能使用稳定、安全的 ID 字符，避免把姓名、自由文本或控制字符混入日志和业务键。
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private ContractValidation() {
    }

    static String requireReference(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (!REFERENCE.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return value;
    }

    static String requireText(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain 1-" + maxLength + " characters");
        }
        return value;
    }
}
