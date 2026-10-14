package com.lky.kaipay.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginPasswordWorkUnitTest {
    @Test void unknownWrongAndDisabledUsersEachPerformVerificationBeforeGenericFailure() {
        var store = mock(DemoIdentityStore.class);
        var wrong = new DemoPrincipal(UUID.randomUUID(),"wrong","stored","ADMIN",true,0);
        var disabled = new DemoPrincipal(UUID.randomUUID(),"disabled","stored","ADMIN",false,0);
        when(store.loadUserByUsername("wrong")).thenReturn(wrong);
        when(store.loadUserByUsername("disabled")).thenReturn(disabled);
        when(store.loadUserByUsername("unknown")).thenThrow(new org.springframework.security.core.userdetails.UsernameNotFoundException("Invalid credentials"));
        var matches = new AtomicInteger();
        PasswordEncoder encoder = new PasswordEncoder() {
            public String encode(CharSequence value) { return "dummy-encoded"; }
            public boolean matches(CharSequence raw, String encoded) { matches.incrementAndGet(); return raw.toString().equals("correct"); }
        };
        var provider = new SecurityConfiguration().authenticationProvider(store,encoder);
        for (String[] attempt : new String[][]{{"wrong","incorrect"},{"unknown","incorrect"},{"disabled","incorrect"},{"disabled","correct"}}) {
            int before = matches.get();
            assertThatThrownBy(() -> provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(attempt[0],attempt[1])))
                    .isInstanceOf(BadCredentialsException.class);
            assertThat(matches.get()).isEqualTo(before+1);
        }
        assertThat(provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("wrong","correct")).isAuthenticated()).isTrue();
    }
}
