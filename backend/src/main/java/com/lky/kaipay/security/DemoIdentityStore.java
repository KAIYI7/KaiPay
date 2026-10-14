package com.lky.kaipay.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DemoIdentityStore implements UserDetailsService {
    private final JdbcTemplate jdbc;
    public DemoIdentityStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public DemoPrincipal loadUserByUsername(String username) {
        return find("username", username).orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
    public Optional<DemoPrincipal> findById(UUID id) { return find("id", id); }
    private Optional<DemoPrincipal> find(String column, Object value) {
        return jdbc.query("SELECT id,username,password_hash,role,enabled,credential_version FROM demo_users WHERE "
                + column + " = ?", (rs, row) -> new DemoPrincipal(rs.getObject("id", UUID.class),
                rs.getString("username"), rs.getString("password_hash"), rs.getString("role"),
                rs.getBoolean("enabled"), rs.getLong("credential_version")), value).stream().findFirst();
    }
    public boolean isMember(UUID userId, UUID merchantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM demo_merchant_memberships mm JOIN merchants m ON m.id=mm.merchant_id
                WHERE mm.user_id=? AND mm.merchant_id=? AND m.status='ACTIVE')
                """, Boolean.class, userId, merchantId));
    }
    public List<MerchantMembership> memberships(UUID userId) {
        return jdbc.query("""
                SELECT m.id,m.name FROM demo_merchant_memberships mm JOIN merchants m ON m.id=mm.merchant_id
                WHERE mm.user_id=? AND m.status='ACTIVE' ORDER BY m.name,m.id
                """, (rs, row) -> new MerchantMembership(rs.getObject("id", UUID.class), rs.getString("name")), userId);
    }
    public record MerchantMembership(UUID id, String name) {}
}
