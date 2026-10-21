package com.lky.kaipay.webhook.delivery;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Direct HTTP/1.1; pinned resolver is the only connection address source. No response body is consumed. */
public final class PinnedWebhookTransport implements WebhookTransport {
    private final WebhookAddressPolicy policy;
    private final SSLContext ssl;
    private final Duration deadline;
    private final ScheduledExecutorService cancellation=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"webhook-request-deadline");t.setDaemon(true);return t;
    });
    private final java.util.Set<HttpPost> active=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public PinnedWebhookTransport() {
        this(new WebhookAddressPolicy(),defaultSsl(),Duration.ofSeconds(10));
    }
    // Package access: a loopback policy/test CA can only be supplied from test source in this package.
    PinnedWebhookTransport(WebhookAddressPolicy policy,SSLContext ssl,Duration deadline) {
        if(deadline.isNegative()||deadline.isZero()||deadline.compareTo(Duration.ofSeconds(10))>0)
            throw new IllegalArgumentException("Invalid transport deadline");
        this.policy=policy;this.ssl=ssl;this.deadline=deadline;
    }
    private static SSLContext defaultSsl() {
        try { return SSLContext.getDefault(); }
        catch(java.security.NoSuchAlgorithmException e){ throw new IllegalStateException("TLS unavailable"); }
    }
    @Override public Response send(WebhookClaim claim,String signature) {
        if(closed) return new Response(WebhookResult.CANCELLED,null);
        if(claim.payload().length>4096) return new Response(WebhookResult.CONFIGURATION,null);
        HttpPost request;
        InetAddress[] pins;
        try {
            request=new HttpPost(policy.endpoint(claim.url(),claim.hostname(),claim.path()));
            pins=policy.addresses(claim.pins());
        } catch(IllegalArgumentException e){return new Response(WebhookResult.UNSAFE_DESTINATION,null);}
        DnsResolver resolver=new DnsResolver(){
            private void check(String host)throws UnknownHostException{
                if(!claim.hostname().equals(host))throw new UnknownHostException("Unapproved host");
            }
            public InetAddress[] resolve(String host)throws UnknownHostException{check(host);return pins.clone();}
            public String resolveCanonicalHostname(String host)throws UnknownHostException{check(host);return claim.hostname();}
        };
        var tlsBuilder=ClientTlsStrategyBuilder.create().setSslContext(ssl);
        tlsBuilder.setHostnameVerificationPolicy(HostnameVerificationPolicy.BOTH);
        tlsBuilder.setHostnameVerifier(new org.apache.hc.client5.http.ssl.DefaultHostnameVerifier());
        var tls=tlsBuilder.buildClassic();
        var manager=PoolingHttpClientConnectionManagerBuilder.create().setMaxConnTotal(1).setMaxConnPerRoute(1)
                .setDnsResolver(resolver)
                .setConnectionFactory(ManagedHttpClientConnectionFactory.builder().http1Config(Http1Config.custom()
                        .setMaxHeaderCount(31).setMaxLineLength(256).build()).build())
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(2))
                        .setSocketTimeout(Timeout.ofSeconds(3)).build())
                .setDefaultTlsConfig(TlsConfig.custom().setHandshakeTimeout(Timeout.ofSeconds(2)).build())
                .setTlsSocketStrategy((socket,host,port,attachment,context)->{
                    if(!claim.hostname().equals(host)||Arrays.stream(pins).noneMatch(socket.getInetAddress()::equals)){
                        socket.close();throw new SSLException("Unapproved connection peer");
                    }
                    return tls.upgrade(socket,host,port,attachment,context);
                }).build();
        request.setEntity(new ByteArrayEntity(claim.payload(),ContentType.APPLICATION_JSON));
        request.setHeader("Webhook-Id",claim.eventId().toString());
        request.setHeader("Webhook-Delivery-Id",claim.id().toString());
        request.setHeader("Webhook-Signature",signature);
        request.setHeader("Connection","close");
        AtomicBoolean timedOut=new AtomicBoolean();
        active.add(request);
        try(var client=HttpClients.custom().setConnectionManager(manager)
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                .setConnectionReuseStrategy((a,b,c)->false).disableRedirectHandling().disableAutomaticRetries()
                .disableAuthCaching().disableCookieManagement().disableContentCompression().disableConnectionState()
                .setDefaultAuthSchemeRegistry(org.apache.hc.core5.http.config.RegistryBuilder
                        .<org.apache.hc.client5.http.auth.AuthSchemeFactory>create().build())
                .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofMilliseconds(250))
                        .setResponseTimeout(Timeout.ofSeconds(3)).setAuthenticationEnabled(false).build())
                .setUserAgent("KaiPay-Webhooks/1").build()){
            if(closed)return new Response(WebhookResult.CANCELLED,null);
            var timer=cancellation.schedule(()->{timedOut.set(true);request.cancel();manager.close(org.apache.hc.core5.io.CloseMode.IMMEDIATE);},
                    deadline.toMillis(),TimeUnit.MILLISECONDS);
            try {
                // executeOpen/IMMEDIATE close avoids the response-handler auto-drain of an untrusted entity.
                var response=client.executeOpen(null,request,null);
                try {
                    int status=response.getCode();
                    return new Response(WebhookResult.http(status),status);
                } finally {
                    manager.close(org.apache.hc.core5.io.CloseMode.IMMEDIATE);
                    // The socket is already closed: never let entity cleanup/draining replace an acknowledged status.
                    try { response.close(); } catch(IOException ignored) { }
                }
            } catch(SSLException e){return new Response(WebhookResult.TLS_ERROR,null);}
            catch(IOException e){return new Response(timedOut.get()||e instanceof java.net.SocketTimeoutException?WebhookResult.TIMEOUT:WebhookResult.IO_ERROR,null);}
            finally {timer.cancel(false);}
        } catch(IOException|RejectedExecutionException e){return new Response(closed?WebhookResult.CANCELLED:WebhookResult.IO_ERROR,null);}
        finally { active.remove(request);manager.close(org.apache.hc.core5.io.CloseMode.IMMEDIATE); }
    }
    @Override public void close(){closed=true;active.forEach(HttpPost::cancel);cancellation.shutdownNow();}
}
