package com.lky.kaipay.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public record DemoPrincipal(UUID id, String username, String password, String role,
                            boolean enabled, long credentialVersion) implements UserDetails {
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return username; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }

    // The authenticated session needs identity/version only, never the stored hash.
    public DemoPrincipal withoutPassword() {
        return new DemoPrincipal(id, username, null, role, enabled, credentialVersion);
    }
    @Override public String toString() { return "DemoPrincipal[id=" + id + ", role=" + role + "]"; }
}
