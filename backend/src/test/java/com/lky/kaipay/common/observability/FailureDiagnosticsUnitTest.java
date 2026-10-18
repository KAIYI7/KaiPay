package com.lky.kaipay.common.observability;

import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.assertThat;

class FailureDiagnosticsUnitTest {
    @Test void exposesOnlyTypeAndValidatedSqlState() {
        var failure = new IllegalStateException("password-secret", new SQLException("row-secret", "23514"));
        assertThat(FailureDiagnostics.summary(failure)).isEqualTo("IllegalStateException sqlState=23514");
        assertThat(FailureDiagnostics.summary(new SQLException("secret", "injected\nsecret")))
                .isEqualTo("SQLException");
        assertThat(FailureDiagnostics.summary(null)).isEqualTo("UnknownFailure");
    }
    @Test void cyclicCausesTerminateWithoutRenderingThem() {
        var first = new IllegalStateException("first-secret");
        var second = new IllegalStateException("second-secret", first);
        first.initCause(second);
        assertThat(FailureDiagnostics.summary(first)).isEqualTo("IllegalStateException");
    }
}
