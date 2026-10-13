package com.lky.experimental.bridge;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DisabledApplicationTest {
    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    @Test void bundledDefaultsExposeNeitherDemoNorEndpointsNorStore() throws Exception {
        assertThat(context.getBeansOfType(BridgeController.class)).isEmpty();
        assertThat(context.getBeansOfType(ObservationService.class)).isEmpty();
        assertThat(context.getBeansOfType(SnapshotStore.class)).isEmpty();
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (String path : new String[]{"/", "/demo.js", "/api/state", "/api/observations"}) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(404);
                assertThat(response.body()).isEqualTo("{\"error\":\"EXPERIMENT_DISABLED\"}");
            }
        }
    }
}
