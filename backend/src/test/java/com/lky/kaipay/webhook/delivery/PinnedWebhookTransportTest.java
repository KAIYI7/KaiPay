package com.lky.kaipay.webhook.delivery;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PinnedWebhookTransportTest {
    @TempDir Path directory;
    @TempDir static Path receiverDirectory;
    static SyntheticWebhookReceiver receiver;
    @BeforeAll static void receiver()throws Exception{receiver=new SyntheticWebhookReceiver(receiverDirectory);}
    @AfterAll static void stop()throws Exception{receiver.close();}
    @BeforeEach void mode(){receiver.mode.set(SyntheticWebhookReceiver.Mode.OK);receiver.paths.clear();receiver.sni.clear();}
    byte[] body(UUID event){return ("{\"id\":\""+event+"\",\"merchant_id\":\""+receiver.merchant+"\",\"type\":\"payment.captured\",\"version\":1}").getBytes(StandardCharsets.UTF_8);}
    WebhookTransport.Response send(PinnedWebhookTransport transport,WebhookClaim c){return transport.send(c,WebhookSigner.sign(receiver.secret,c.keyId(),Instant.now().getEpochSecond(),c.payload()));}
    @Test void pinnedSocketPreservesHostnameSniAndNeverUsesSystemDnsOrProxy()throws Exception{
        String old=System.getProperty("https.proxyHost");System.setProperty("https.proxyHost","127.0.0.2");System.setProperty("https.proxyPort","1");
        try(var transport=receiver.transport(Duration.ofSeconds(2))){
            UUID event=UUID.randomUUID();var claim=receiver.claim(event,body(event));
            assertThat(send(transport,claim).result()).isEqualTo(WebhookResult.ACCEPTED);
            assertThat(receiver.sni).contains(receiver.host);assertThat(receiver.paths).containsExactly("POST /webhook HTTP/1.1");
            assertThat(receiver.receipts).containsKey(event);
        }finally{if(old==null)System.clearProperty("https.proxyHost");else System.setProperty("https.proxyHost",old);System.clearProperty("https.proxyPort");}
    }
    @Test void redirectAndRetryStatusesMakeExactlyOneNetworkRequest()throws Exception{
        try(var transport=receiver.transport(Duration.ofSeconds(2))){
            for(var mode:List.of(SyntheticWebhookReceiver.Mode.REDIRECT,SyntheticWebhookReceiver.Mode.FAIL,SyntheticWebhookReceiver.Mode.RATE_LIMIT)){
                receiver.mode.set(mode);int before=receiver.requests.get();UUID event=UUID.randomUUID();
                var result=send(transport,receiver.claim(event,body(event)));
                assertThat(result.result()).isEqualTo(mode==SyntheticWebhookReceiver.Mode.REDIRECT?WebhookResult.HTTP_TERMINAL:WebhookResult.HTTP_RETRYABLE);
                assertThat(receiver.requests.get()).isEqualTo(before+1);assertThat(receiver.paths).noneMatch(s->s.contains("escaped"));
            }
        }
    }
    @Test void remoteAcceptanceLostAcknowledgementDuplicatesSameEventWithoutDuplicateEffect()throws Exception{
        try(var transport=receiver.transport(Duration.ofSeconds(2))){
            UUID event=UUID.randomUUID();var c=receiver.claim(event,body(event));receiver.mode.set(SyntheticWebhookReceiver.Mode.LOST_ACK);
            assertThat(send(transport,c).result()).isEqualTo(WebhookResult.IO_ERROR);
            assertThat(receiver.receipts).containsKey(event);
            assertThat(send(transport,c).result()).isEqualTo(WebhookResult.ACCEPTED);
            assertThat(java.nio.file.Files.readAllLines(receiver.journal).stream().filter(s->s.startsWith(event.toString()))).hasSize(1);
        }
    }
    @ParameterizedTest @ValueSource(strings={"DELAY","SLOW_HEADERS","STALL_TLS"})
    void absoluteDeadlineClosesActualNetworkRequest(String mode)throws Exception{
        try(var transport=receiver.transport(Duration.ofMillis(350))){
            UUID event=UUID.randomUUID();receiver.mode.set(SyntheticWebhookReceiver.Mode.valueOf(mode));long start=System.nanoTime();
            assertThat(send(transport,receiver.claim(event,body(event))).result()).isEqualTo(WebhookResult.TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
        }
    }
    @Test void wrongHostnameAndUntrustedCertificateFailClosed()throws Exception{
        UUID event=UUID.randomUUID();var c=receiver.claim(event,body(event));
        var wrong=new WebhookClaim(c.id(),c.eventId(),c.merchantId(),c.endpointId(),c.attemptId(),1,c.expiresAt(),c.deadline(),c.payload(),
                c.url().replace(receiver.host,"wrong.fixture.test"),"wrong.fixture.test",c.path(),c.pins(),c.keyId(),c.secretRef());
        try(var transport=receiver.transport(Duration.ofSeconds(2))){assertThat(send(transport,wrong).result()).isEqualTo(WebhookResult.TLS_ERROR);}
        try(var transport=receiver.untrustedTransport(Duration.ofSeconds(2))){assertThat(send(transport,c).result()).isEqualTo(WebhookResult.TLS_ERROR);}
        // Default transport rejects private pins before attempting even a trusted fixture connection.
        try(var transport=new PinnedWebhookTransport()){assertThat(send(transport,c).result()).isEqualTo(WebhookResult.UNSAFE_DESTINATION);}
    }
    @Test void receiverRestartReloadsDedupReceiptsWithRetainedIdentity()throws Exception{
        var first=new SyntheticWebhookReceiver(directory);
        var identity=first.identity();UUID event=UUID.randomUUID();
        byte[] bytes=("{\"id\":\""+event+"\",\"merchant_id\":\""+first.merchant+"\"}").getBytes(StandardCharsets.UTF_8);
        try(var transport=first.transport(Duration.ofSeconds(2))){var c=first.claim(event,bytes);assertThat(transport.send(c,WebhookSigner.sign(first.secret,first.key,Instant.now().getEpochSecond(),bytes)).result()).isEqualTo(WebhookResult.ACCEPTED);}
        finally{first.close();}
        try(var restarted=new SyntheticWebhookReceiver(directory,identity);var transport=restarted.transport(Duration.ofSeconds(2))){
            var c=restarted.claim(event,bytes);assertThat(transport.send(c,WebhookSigner.sign(restarted.secret,restarted.key,Instant.now().getEpochSecond(),bytes)).result()).isEqualTo(WebhookResult.ACCEPTED);
            assertThat(java.nio.file.Files.readAllLines(restarted.journal)).hasSize(1);assertThat(restarted.receipts).hasSize(1);
        }
    }
    @Test void receiverRejectsTamperWrongMerchantKeyAndOldOrFutureTimestamp()throws Exception{
        UUID event=UUID.randomUUID();byte[] bytes=body(event);String signature=WebhookSigner.sign(receiver.secret,receiver.key,Instant.now().getEpochSecond(),bytes);
        Map<String,String> headers=new HashMap<>(Map.of("webhook-id",event.toString(),"webhook-signature",signature));
        assertThat(receiver.verify(headers,bytes)).isEqualTo(204);
        assertThat(receiver.verify(headers,(new String(bytes,StandardCharsets.UTF_8)+" ").getBytes(StandardCharsets.UTF_8))).isEqualTo(401);
        for(long offset:new long[]{-301,301}){headers.put("webhook-signature",WebhookSigner.sign(receiver.secret,receiver.key,Instant.now().getEpochSecond()+offset,bytes));assertThat(receiver.verify(headers,bytes)).isEqualTo(401);}
        headers.put("webhook-signature",WebhookSigner.sign(receiver.secret,UUID.randomUUID(),Instant.now().getEpochSecond(),bytes));assertThat(receiver.verify(headers,bytes)).isEqualTo(401);
        byte[] foreign=new String(bytes,StandardCharsets.UTF_8).replace(receiver.merchant.toString(),UUID.randomUUID().toString()).getBytes(StandardCharsets.UTF_8);
        headers.put("webhook-signature",WebhookSigner.sign(receiver.secret,receiver.key,Instant.now().getEpochSecond(),foreign));assertThat(receiver.verify(headers,foreign)).isEqualTo(401);
    }
    @ParameterizedTest @ValueSource(strings={"0.0.0.0","10.0.0.1","100.100.100.100","127.0.0.1","169.254.169.254","172.16.0.1","192.168.1.1","192.0.2.1","198.19.0.1","198.51.100.1","203.0.113.1","224.0.0.1","255.255.255.255","::1","::ffff:127.0.0.1","64:ff9b::7f00:1","127.1","0177.0.0.1","2130706433","8.8.8.256"})
    void productionRejectsNonpublicOrAmbiguousPins(String pin){assertThatThrownBy(()->new WebhookAddressPolicy().addresses(List.of(pin))).isInstanceOf(IllegalArgumentException.class);}
    @Test void canonicalEndpointAndPublicPinOnly(){
        var policy=new WebhookAddressPolicy();assertThat(policy.addresses(List.of("8.8.8.8"))[0].getHostAddress()).isEqualTo("8.8.8.8");
        for(String url:List.of("http://webhook.example/webhook","https://webhook.example:8443/webhook","https://user@webhook.example/webhook","https://webhook.example/webhook?x=y","https://webhook.example/webhook#x","https://webhook.example./webhook","https://webhook.example\\@127.0.0.1/webhook"))
            assertThatThrownBy(()->policy.endpoint(url,"webhook.example","/webhook")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->policy.addresses(List.of("8.8.8.8","10.0.0.1"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void responseHeadersAreBoundedAndBodyIsNeverDrained()throws Exception{
        try(var transport=receiver.transport(Duration.ofSeconds(2))){
            UUID event=UUID.randomUUID();receiver.mode.set(SyntheticWebhookReceiver.Mode.HUGE_HEADERS);
            assertThat(send(transport,receiver.claim(event,body(event))).result()).isEqualTo(WebhookResult.IO_ERROR);
            event=UUID.randomUUID();receiver.mode.set(SyntheticWebhookReceiver.Mode.BODY_DRIP);long start=System.nanoTime();
            assertThat(send(transport,receiver.claim(event,body(event))).result()).isEqualTo(WebhookResult.ACCEPTED);
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(1));
        }
    }
}
