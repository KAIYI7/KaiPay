package com.lky.kaipay.webhook.delivery;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public class WebhookWorker implements AutoCloseable {
    private final WebhookDeliveryStore store;
    private final WebhookSecrets secrets;
    private final WebhookTransport transport;
    private final Clock clock;
    private final boolean localHarness;
    private final AtomicBoolean running=new AtomicBoolean();
    private volatile boolean closed;
    public WebhookWorker(WebhookDeliveryStore store,WebhookSecrets secrets,WebhookTransport transport,Clock clock){
        this(store,secrets,transport,clock,false);
    }
    WebhookWorker(WebhookDeliveryStore store,WebhookSecrets secrets,WebhookTransport transport,Clock clock,boolean localHarness){
        this.store=store;this.secrets=secrets;this.transport=transport;this.clock=clock;this.localHarness=localHarness;
    }
    // Activation intentionally unavailable in this source batch. A later operational step must prove egress/ownership.
    @Scheduled(scheduler="webhookScheduler",fixedDelayString="${kaipay.webhooks.poll-interval-ms:1000}")
    public void scheduledTick() { if(localHarness)deliverOne(); }

    /** Explicit test/local harness entry; no HTTP route or production scheduler calls it. */
    @Transactional(propagation=Propagation.NEVER)
    public boolean deliverOne() {
        if(closed||!running.compareAndSet(false,true))return false;
        try {
            var candidate=store.claim(clock.instant());
            if(candidate.isEmpty())return false;
            var c=candidate.get();
            WebhookTransport.Response result;
            byte[] secret=null;
            try{
                if(closed)return true; // Keep the committed STARTED claim recoverable after shutdown.
                var now=clock.instant();
                if(!store.ready(c,now)){
                    if(!now.isBefore(c.deadline()))return true;
                    result=new WebhookTransport.Response(!now.isBefore(c.expiresAt())?WebhookResult.EXPIRED:WebhookResult.CANCELLED,null);
                }
                else{
                    if(closed)return true;
                    secret=secrets.secret(c);
                    if(closed)return true;
                    result=transport.send(c,WebhookSigner.sign(secret,c.keyId(),clock.instant().getEpochSecond(),c.payload()));
                    if(result.result()==WebhookResult.CANCELLED)result=new WebhookTransport.Response(WebhookResult.UNKNOWN,null);
                }
            }catch(IllegalStateException|IllegalArgumentException e){result=new WebhookTransport.Response(WebhookResult.CONFIGURATION,null);}
            finally{if(secret!=null)Arrays.fill(secret,(byte)0);}
            if(!closed)store.complete(c,result,clock.instant());
            return true;
        }finally{running.set(false);}
    }
    @Override public void close(){closed=true;transport.close();}
}
