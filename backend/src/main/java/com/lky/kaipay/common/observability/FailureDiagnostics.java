package com.lky.kaipay.common.observability;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Log categories only: exception messages/stack traces can contain credentials, SQL or payloads. */
public final class FailureDiagnostics {
    private FailureDiagnostics() {}

    public static String summary(Throwable failure) {
        if (failure == null) return "UnknownFailure";
        String type = failure.getClass().getSimpleName();
        if (!type.matches("[A-Za-z0-9_$]{1,100}")) type = "UnknownFailure";
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 32 && visited.add(cause); depth++, cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().matches("[A-Z0-9]{5}")) {
                return type + " sqlState=" + sql.getSQLState();
            }
        }
        return type;
    }
}
