package com.lky.kaipay.integration;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class IntegrationPropertiesTest {
    @Test void absentConfigurationIsDisabledAndEnabledConfigurationCannotBePartial() {
        assertThat(new IntegrationProperties(false, null).enabled()).isFalse();
        assertThatThrownBy(() -> new IntegrationProperties(true, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegrationProperties(true, List.of(new IntegrationProperties.Binding(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true, List.of()))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void duplicateNamespacesMerchantsAndKeyIdsCannotStart() {
        UUID merchant = UUID.randomUUID();
        var key = new IntegrationProperties.Key("test", "a".repeat(64));
        var one = new IntegrationProperties.Binding(UUID.randomUUID(), merchant, UUID.randomUUID(), true, List.of(key));
        var two = new IntegrationProperties.Binding(UUID.randomUUID(), merchant, UUID.randomUUID(), true, List.of(key));
        assertThatThrownBy(() -> new IntegrationProperties(true, List.of(one, two)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid machine integration configuration");
        assertThat(key.toString()).doesNotContain(key.sha256());
    }
}
