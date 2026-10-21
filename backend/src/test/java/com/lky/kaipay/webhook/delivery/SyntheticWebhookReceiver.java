package com.lky.kaipay.webhook.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.net.ssl.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Test-only, loopback TLS receiver. Certificates, keys, receipts and secrets are generated per run. */
public final class SyntheticWebhookReceiver implements AutoCloseable {
    public enum Mode { OK, FAIL, FAIL_FIRST, RATE_LIMIT, BAD_REQUEST, REDIRECT, LOST_ACK, DELAY, SLOW_HEADERS, STALL_TLS, HUGE_HEADERS, BODY_DRIP }
    final UUID merchant,key;
    final byte[] secret=new byte[32];
    final SSLContext clientContext;
    final SSLServerSocket server;
    final String host="webhook.fixture.test";
    final AtomicReference<Mode> mode=new AtomicReference<>(Mode.OK);
    final AtomicInteger requests=new AtomicInteger();
    final ConcurrentMap<UUID,String> receipts=new ConcurrentHashMap<>();
    final List<String> paths=new CopyOnWriteArrayList<>();
    final List<String> sni=new CopyOnWriteArrayList<>();
    final Path journal;
    final Path certificate;
    private final ExecutorService threads=Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean closed;
    record Identity(UUID merchant,UUID key,byte[] secret){Identity{secret=secret.clone();}}
    public SyntheticWebhookReceiver(Path dir)throws Exception {this(dir,newIdentity());}
    private static Identity newIdentity(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return new Identity(UUID.randomUUID(),UUID.randomUUID(),bytes);}
    Identity identity(){return new Identity(merchant,key,secret);}
    SyntheticWebhookReceiver(Path dir,Identity identity)throws Exception {
        merchant=identity.merchant();key=identity.key();System.arraycopy(identity.secret(),0,secret,0,32);
        Files.createDirectories(dir);journal=dir.resolve("synthetic-receipts.txt");
        if(Files.exists(journal))for(String row:Files.readAllLines(journal)){String[] values=row.split(" ");receipts.put(UUID.fromString(values[0]),values[1]);}
        String password=UUID.randomUUID().toString();Path keystore=dir.resolve("receiver-"+UUID.randomUUID()+".p12");
        ProcessBuilder keytool=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),
                "-genkeypair","-alias","receiver","-keyalg","RSA","-keysize","2048","-validity","2",
                "-dname","CN="+host,"-ext","SAN=dns:"+host,"-storetype","PKCS12","-keystore",keystore.toString(),
                "-storepass:env","FIXTURE_KEYSTORE_PASSWORD","-keypass:env","FIXTURE_KEYSTORE_PASSWORD").redirectErrorStream(true);
        keytool.environment().put("FIXTURE_KEYSTORE_PASSWORD",password);Process process=keytool.start();
        process.getInputStream().readAllBytes();
        if(!process.waitFor(30,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IOException("Fixture certificate generation failed");
        KeyStore keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(keystore)){keys.load(input,password.toCharArray());}
        certificate=dir.resolve("receiver-"+UUID.randomUUID()+".cer");Files.write(certificate,keys.getCertificate("receiver").getEncoded());
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,password.toCharArray());
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);
        SSLContext serverContext=SSLContext.getInstance("TLS");serverContext.init(km.getKeyManagers(),null,null);
        clientContext=SSLContext.getInstance("TLS");clientContext.init(null,tm.getTrustManagers(),null);
        server=(SSLServerSocket)serverContext.getServerSocketFactory().createServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),0));
        threads.submit(()->{while(!closed)try{SSLSocket socket=(SSLSocket)server.accept();threads.submit(()->handle(socket));}catch(IOException e){if(!closed)throw new UncheckedIOException(e);}});
    }
    int port(){return server.getLocalPort();}
    public WebhookClaim claim(UUID event,byte[] body){return new WebhookClaim(UUID.randomUUID(),event,merchant,UUID.randomUUID(),UUID.randomUUID(),1,
            Instant.now().plusSeconds(86400),Instant.now().plusSeconds(30),body,"https://"+host+":"+port()+"/webhook",host,"/webhook",List.of("127.0.0.1"),key,"fixture");}
    PinnedWebhookTransport transport(java.time.Duration deadline){
        return fixtureTransport(port(),host,clientContext,deadline);
    }
    PinnedWebhookTransport untrustedTransport(java.time.Duration deadline)throws Exception{
        return fixtureTransport(port(),host,SSLContext.getDefault(),deadline);
    }
    static PinnedWebhookTransport fixtureTransport(int port,String host,SSLContext clientContext,java.time.Duration deadline){
        return new PinnedWebhookTransport(new WebhookAddressPolicy(){
            @Override public URI endpoint(String url,String hostname,String path){
                URI uri=URI.create(url);
                if(!Set.of(host,"wrong.fixture.test").contains(hostname)||!hostname.equals(uri.getHost())||!"https".equals(uri.getScheme())
                        ||uri.getPort()!=port||!"/webhook".equals(path)||!path.equals(uri.getRawPath())||uri.getUserInfo()!=null||uri.getQuery()!=null)
                    throw new IllegalArgumentException("Nonfixture endpoint");
                return uri;
            }
            @Override protected InetAddress address(String text){
                if(!"127.0.0.1".equals(text))throw new IllegalArgumentException("Nonfixture pin");
                try{return InetAddress.getByAddress(new byte[]{127,0,0,1});}catch(UnknownHostException e){throw new IllegalStateException(e);}
            }
        },clientContext,deadline);
    }
    private void handle(SSLSocket socket){
        try(socket){
            Mode current=mode.get();
            if(current==Mode.STALL_TLS){Thread.sleep(3000);return;}
            socket.setSoTimeout(5000);socket.startHandshake();
            for(SNIServerName name:((ExtendedSSLSession)socket.getSession()).getRequestedServerNames())
                if(name instanceof SNIHostName h)sni.add(h.getAsciiName());
            var input=socket.getInputStream();var headers=new ByteArrayOutputStream();int last=0,b;
            while((b=input.read())!=-1){headers.write(b);if(headers.size()>8192)return;last=(last<<8)|b;if(last==0x0d0a0d0a)break;}
            String[] lines=headers.toString(StandardCharsets.US_ASCII).split("\r\n");if(lines.length<2)return;
            requests.incrementAndGet();paths.add(lines[0]);Map<String,String> map=new HashMap<>();
            for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');if(colon>0){String name=lines[i].substring(0,colon).toLowerCase(Locale.ROOT);if(map.put(name,lines[i].substring(colon+1).trim())!=null)return;}}
            int length=Integer.parseInt(map.getOrDefault("content-length","0"));if(length<1||length>4096)return;
            byte[] body=input.readNBytes(length);int status=verify(map,body);
            if(status==204){
                if(current==Mode.FAIL||current==Mode.FAIL_FIRST&&requests.get()==1)status=503;else if(current==Mode.RATE_LIMIT)status=429;else if(current==Mode.BAD_REQUEST)status=400;
                else if(current==Mode.REDIRECT)status=307;
                else{
                    UUID event=UUID.fromString(map.get("webhook-id"));String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
                    synchronized(receipts){
                        if(receipts.containsKey(event)&&!receipts.get(event).equals(digest))status=409;
                        else if(!receipts.containsKey(event)){Files.writeString(journal,event+" "+digest+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);receipts.put(event,digest);}
                    }
                    if(current==Mode.LOST_ACK){mode.set(Mode.OK);return;}
                }
            }
            if(current==Mode.DELAY)Thread.sleep(3000);
            if(current==Mode.HUGE_HEADERS){socket.getOutputStream().write(("HTTP/1.1 204 Fixture\r\nX-Too-Large: "+"x".repeat(8192)+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();return;}
            if(current==Mode.BODY_DRIP){socket.getOutputStream().write("HTTP/1.1 200 Fixture\r\nContent-Length: 1000000\r\n\r\n".getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();Thread.sleep(3000);return;}
            String response="HTTP/1.1 "+status+" Fixture\r\nContent-Length: 0\r\nConnection: close\r\n"
                    +(status==307?"Location: https://"+host+":"+port()+"/escaped\r\n":"")+"\r\n";
            if(current==Mode.SLOW_HEADERS){for(byte c:response.getBytes(StandardCharsets.US_ASCII)){socket.getOutputStream().write(c);socket.getOutputStream().flush();Thread.sleep(100);}}
            else{socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();}
        }catch(Exception ignored){ /* Deliberate aborts/invalid input are fixture outcomes, never log request material. */ }
    }
    int verify(Map<String,String> headers,byte[] body)throws Exception{
        String signature=headers.getOrDefault("webhook-signature","");
        var matcher=java.util.regex.Pattern.compile("t=([0-9]{1,12}),kid=([a-f0-9-]{36}),v1=([a-f0-9]{64})").matcher(signature);
        if(!matcher.matches())return 401;
        long t=Long.parseLong(matcher.group(1));if(Math.abs(Instant.now().getEpochSecond()-t)>300||!key.toString().equals(matcher.group(2)))return 401;
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
        mac.update(("v1\n"+key+"\n"+t+"\n").getBytes(StandardCharsets.US_ASCII));
        if(!MessageDigest.isEqual(mac.doFinal(body),HexFormat.of().parseHex(matcher.group(3))))return 401;
        var json=new ObjectMapper().readTree(body);
        return merchant.toString().equals(json.path("merchant_id").asText())&&headers.get("webhook-id").equals(json.path("id").asText())?204:401;
    }
    @Override public void close()throws IOException{closed=true;server.close();threads.shutdownNow();Arrays.fill(secret,(byte)0);}
}
