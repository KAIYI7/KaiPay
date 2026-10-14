package com.lky.kaipay.security;


import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.UUID;

/** Explicit operator-only command, never discovered/run during application startup. */
public final class DemoAccountCli {
    private DemoAccountCli() {}
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !args[1].matches("[A-Za-z0-9._@-]{3,100}")) {
            throw new IllegalArgumentException("Usage: provision <username> <MERCHANT|ADMIN> [merchant UUIDs...] | rotate <username> | disable <username>");
        }
        String action = args[0];
        if (!action.equals("provision") && !action.equals("rotate") && !action.equals("disable")) {
            throw new IllegalArgumentException("Unknown operation");
        }
        String role = args.length > 2 ? args[2] : "";
        if (action.equals("provision") && (!role.equals("MERCHANT") && !role.equals("ADMIN")
                || role.equals("ADMIN") && args.length != 3 || role.equals("MERCHANT") && args.length < 4)) {
            throw new IllegalArgumentException("Merchant users require memberships; administrators must have none");
        }
        if (!action.equals("provision") && args.length != 2) throw new IllegalArgumentException("Unexpected arguments");
        String hash = action.equals("disable") ? null : passwordHash(System.console());
        try (Connection connection = DriverManager.getConnection(required("DB_URL"), required("DB_USERNAME"), required("DB_PASSWORD"))) {
            connection.setAutoCommit(false);
            try {
                if (action.equals("provision")) {
                    UUID id = UUID.randomUUID();
                    try (var statement = connection.prepareStatement("INSERT INTO demo_users(id,username,password_hash,role) VALUES (?,?,?,?)")) {
                        statement.setObject(1, id); statement.setString(2, args[1]); statement.setString(3, hash); statement.setString(4, role);
                        statement.executeUpdate();
                    }
                    for (int i = 3; i < args.length; i++) {
                        try (var statement = connection.prepareStatement("""
                                INSERT INTO demo_merchant_memberships(user_id,merchant_id)
                                SELECT ?,id FROM merchants WHERE id=? AND status='ACTIVE'
                                """)) {
                            statement.setObject(1, id); statement.setObject(2, UUID.fromString(args[i]));
                            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Membership merchant missing or inactive");
                        }
                    }
                } else {
                    String sql = action.equals("disable")
                            ? "UPDATE demo_users SET enabled=false,credential_version=credential_version+1 WHERE username=?"
                            : "UPDATE demo_users SET password_hash=?,credential_version=credential_version+1 WHERE username=?";
                    try (var statement = connection.prepareStatement(sql)) {
                        if (hash != null) statement.setString(1, hash);
                        statement.setString(hash == null ? 1 : 2, args[1]);
                        if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Account not found");
                    }
                }
                connection.commit();
                System.out.println("Account operation completed.");
            } catch (Exception exception) {
                connection.rollback();
                // Do not echo database diagnostics or parameters into operator logs.
                throw new IllegalStateException("Account operation failed and was rolled back");
            }
        }
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing required " + key);
        return value;
    }
    private static String passwordHash(Console console) {
        if (console == null) throw new IllegalStateException("An interactive terminal is required for hidden password entry");
        char[] password = console.readPassword("New individual password: ");
        char[] confirmation = console.readPassword("Confirm password: ");
        try {
            if (password == null || confirmation == null || password.length < 12 || !Arrays.equals(password, confirmation)
                    || new String(password).indexOf('\0') >= 0
                    || new String(password).getBytes(StandardCharsets.UTF_8).length > 72) {
                throw new IllegalArgumentException("Passwords must match, exclude NUL, and contain at least 12 characters and at most 72 UTF-8 bytes");
            }
            return new BoundedBcryptPasswordEncoder().encode(new String(password));
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }
}
