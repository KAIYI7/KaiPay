package com.lky.kaipay.ledger.audit;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Database operator tooling grants no new merchant or administrator HTTP capability. */
class AccountingAuditHttpBoundaryIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    UUID merchant;
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM demo_users");
        if(merchant!=null) jdbc.update("DELETE FROM merchants WHERE id=?",merchant);
    }
    @Test void merchantAdminAndAnonymousSessionsCannotInvokeOperatorAudit() {
        merchant=UUID.randomUUID();
        jdbc.update("INSERT INTO merchants(id,name,api_key_hash) VALUES (?,'Synthetic accounting boundary',?)",merchant,UUID.randomUUID().toString());
        var merchantHeaders=security().merchantHeaders(merchant);
        var adminHeaders=security().adminHeaders();
        for(String route:List.of("/v1/ledger/accounting-audit","/v1/admin/accounting-audit","/v1/internal-accounting-audit")) {
            assertThat(http.exchange(route,HttpMethod.GET,new HttpEntity<>(new HttpHeaders()),String.class).getStatusCode().value()).isEqualTo(401);
            for(var headers:List.of(merchantHeaders,adminHeaders)) {
                assertThat(http.exchange(route,HttpMethod.GET,new HttpEntity<>(headers),String.class).getStatusCode().value()).isEqualTo(403);
                assertThat(http.exchange(route,HttpMethod.POST,new HttpEntity<>("{}",headers),String.class).getStatusCode().value()).isEqualTo(403);
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journals WHERE merchant_id=?",Integer.class,merchant)).isZero();
    }
}
