package com.lky.kaipay.webhook.delivery;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class WebhookSecretsIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @TempDir Path root;
    UUID first,second,firstEndpoint,secondEndpoint,firstKey,secondKey;
    @BeforeEach void setup()throws Exception{
        first=UUID.randomUUID();second=UUID.randomUUID();firstEndpoint=UUID.randomUUID();secondEndpoint=UUID.randomUUID();firstKey=UUID.randomUUID();secondKey=UUID.randomUUID();restrict(root);
        for(UUID merchant:List.of(first,second))jdbc.update("INSERT INTO merchants(id,name,api_key_hash,status)VALUES(?,'Secrets fixture',?,'ACTIVE')",merchant,merchant.toString());
        endpoint(first,firstEndpoint,firstKey,"first_"+first);endpoint(second,secondEndpoint,secondKey,"second_"+second);
    }
    void endpoint(UUID merchant,UUID endpoint,UUID key,String alias){jdbc.update("""
            INSERT INTO merchant_webhook_endpoints(id,merchant_id,url,hostname,path,pins,key_id,secret_ref,state,created_by,reason_code)
            VALUES(?,?,'https://receiver.example.test/webhook','receiver.example.test','/webhook',ARRAY['8.8.8.8'],?,?,'ACTIVE','test','TEST')
            """,endpoint,merchant,key,alias);}
    @AfterEach void cleanup(){jdbc.update("DELETE FROM merchant_webhook_endpoints WHERE merchant_id IN (?,?)",first,second);jdbc.update("DELETE FROM merchants WHERE id IN (?,?)",first,second);}
    @Test void exactSigningBytesMatchIndependentDotNetHmacVector(){
        byte[] secret=new byte[32];for(int i=0;i<32;i++)secret[i]=(byte)i;
        assertThat(WebhookSigner.sign(secret,UUID.fromString("00000000-0000-4000-8000-000000000001"),1700000000,
                "{\"id\":\"synthetic\"}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("t=1700000000,kid=00000000-0000-4000-8000-000000000001,v1=a555e38c7f12e9b174a677fe1fa70d1eabe73aa03a462fde7ad2ebde07270cbb");
    }
    @Test void startupRejectsEveryBindingSharingSecretBytesAcrossAliases()throws Exception{
        byte[] material=new byte[32];new java.security.SecureRandom().nextBytes(material);
        write("first_"+first,material);write("second_"+second,material);
        try(var secrets=new WebhookSecrets(jdbc,root.toString())){
            assertThatThrownBy(()->secrets.secret(claim(first,firstEndpoint,firstKey))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->secrets.secret(claim(second,secondEndpoint,secondKey))).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void startupSnapshotBindsMerchantRevisionAndKeyAndDoesNotReload()throws Exception{
        byte[] a=new byte[32],b=new byte[32];new java.security.SecureRandom().nextBytes(a);new java.security.SecureRandom().nextBytes(b);
        write("first_"+first,a);write("second_"+second,b);
        try(var secrets=new WebhookSecrets(jdbc,root.toString())){
            assertThat(secrets.secret(claim(first,firstEndpoint,firstKey))).containsExactly(a);
            assertThatThrownBy(()->secrets.secret(claim(second,firstEndpoint,firstKey))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->secrets.secret(claim(first,secondEndpoint,firstKey))).isInstanceOf(IllegalStateException.class);
            Files.write(root.resolve("first_"+first),b);
            assertThat(secrets.secret(claim(first,firstEndpoint,firstKey))).containsExactly(a);
            secrets.close();assertThatThrownBy(()->secrets.secret(claim(first,firstEndpoint,firstKey))).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void insecureOrWrongLengthFileFailsClosed()throws Exception{
        Path file=write("first_"+first,new byte[32]);
        Files.write(file,new byte[33]);assertThatThrownBy(()->WebhookSecrets.readRestricted(file)).isInstanceOf(java.io.IOException.class);
        Files.write(file,new byte[32]);
        var posix=Files.getFileAttributeView(file,PosixFileAttributeView.class);
        if(posix!=null)Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r--r--"));
        else{
            var acl=Files.getFileAttributeView(file,AclFileAttributeView.class);var entries=new ArrayList<>(acl.getAcl());
            entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(file.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("Everyone"))
                    .setPermissions(AclEntryPermission.READ_DATA).build());acl.setAcl(entries);
        }
        assertThatThrownBy(()->WebhookSecrets.readRestricted(file)).isInstanceOf(java.io.IOException.class);
        try(var secrets=new WebhookSecrets(jdbc,root.toString())){assertThatThrownBy(()->secrets.secret(claim(first,firstEndpoint,firstKey))).isInstanceOf(IllegalStateException.class);}
    }
    @Test void activationAndLocalFixtureFlagsCannotEnableNonlocalRuntime(){
        for(String mode:List.of("enabled","production","local-fixture","allow-private"))
            assertThatThrownBy(()->new WebhookRuntimeConfiguration().webhookSecrets(jdbc,new MockEnvironment().withProperty("kaipay.webhooks.mode",mode)))
                    .isInstanceOf(IllegalStateException.class);
    }
    private Path write(String alias,byte[] bytes)throws Exception{Path file=root.resolve(alias);Files.write(file,bytes);restrict(file);return file;}
    static void restrict(Path file)throws Exception{
        if(Files.getFileAttributeView(file,PosixFileAttributeView.class)!=null)Files.setPosixFilePermissions(file,PosixFilePermissions.fromString(Files.isDirectory(file)?"rwx------":"rw-------"));
        else{var acl=Files.getFileAttributeView(file,AclFileAttributeView.class);acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner()).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));}
    }
    private WebhookClaim claim(UUID merchant,UUID endpoint,UUID key){return new WebhookClaim(UUID.randomUUID(),UUID.randomUUID(),merchant,endpoint,UUID.randomUUID(),1,
            Instant.now().plusSeconds(86400),Instant.now().plusSeconds(30),new byte[]{1},"https://receiver.example.test/webhook","receiver.example.test","/webhook",List.of("8.8.8.8"),key,"fixture");}
}
