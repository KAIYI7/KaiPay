package com.lky.kaipay.webhook.delivery;

import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Immutable startup snapshot. No DB-controlled file paths, reload or automatic key generation. */
public final class WebhookSecrets implements AutoCloseable {
    private record Binding(UUID merchant, UUID endpoint, byte[] secret) {}
    private final Map<UUID,Binding> bindings=new HashMap<>();
    public WebhookSecrets(JdbcTemplate jdbc,String directory) {
        if(directory==null||directory.isBlank())return;
        Path root=Path.of(directory).toAbsolutePath().normalize();
        try { restrictedPermissions(root); }
        catch(java.io.IOException e) { return; }
        jdbc.query("SELECT id,merchant_id,key_id,secret_ref FROM merchant_webhook_endpoints WHERE state='ACTIVE'", rs->{
            String alias=rs.getString("secret_ref");
            try {
                if(!alias.matches("[A-Za-z0-9_-]{1,100}")||Files.isSymbolicLink(root))return;
                Path file=root.resolve(alias);
                byte[] secret=readRestricted(file);
                bindings.put(rs.getObject("key_id",UUID.class),new Binding(rs.getObject("merchant_id",UUID.class),rs.getObject("id",UUID.class),secret));
            }catch(Exception ignored){ /* Missing/insecure configuration leaves only this endpoint unavailable. */ }
        });
        // A different alias must not disguise reuse of another merchant's secret material.
        var keys=new ArrayList<>(bindings.keySet());
        var duplicates=new HashSet<UUID>();
        for(int i=0;i<keys.size();i++)for(int j=i+1;j<keys.size();j++)
            if(java.security.MessageDigest.isEqual(bindings.get(keys.get(i)).secret(),bindings.get(keys.get(j)).secret())){
                duplicates.add(keys.get(i));duplicates.add(keys.get(j));
            }
        duplicates.forEach(key->Arrays.fill(bindings.remove(key).secret(),(byte)0));
    }
    WebhookSecrets(UUID key,UUID merchant,UUID endpoint,byte[] secret) {
        if(secret.length!=32)throw new IllegalArgumentException("Invalid secret length");
        bindings.put(key,new Binding(merchant,endpoint,secret.clone()));
    }
    public byte[] secret(WebhookClaim c) {
        Binding b=bindings.get(c.keyId());
        if(b==null||!b.merchant().equals(c.merchantId())||!b.endpoint().equals(c.endpointId()))
            throw new IllegalStateException("Webhook secret unavailable");
        return b.secret().clone();
    }
    static byte[] readRestricted(Path file)throws java.io.IOException {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file)||Files.size(file)!=32)
            throw new java.io.IOException("Invalid secret file");
        restrictedPermissions(file);
        byte[] bytes;
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(33);}
        if(bytes.length!=32)throw new java.io.IOException("Invalid secret file");
        return bytes;
    }
    static void restrictedPermissions(Path file)throws java.io.IOException {
        if(Files.isSymbolicLink(file))throw new java.io.IOException("Symlink secret path");
        var posix=Files.getFileAttributeView(file,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(posix!=null){
            if(posix.readAttributes().permissions().stream().anyMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_")))
                throw new java.io.IOException("Secret permissions too broad");
        }else{
            var acl=Files.getFileAttributeView(file,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
            if(acl==null)throw new java.io.IOException("Cannot establish secret permissions");
            String owner=acl.getOwner().getName();
            for(var entry:acl.getAcl())if(entry.type()==AclEntryType.ALLOW){
                String name=entry.principal().getName();
                if(!name.equals(owner)&&!name.equalsIgnoreCase("NT AUTHORITY\\SYSTEM")&&!name.equalsIgnoreCase("BUILTIN\\Administrators"))
                    throw new java.io.IOException("Secret permissions too broad");
            }
        }
    }
    @Override public void close(){bindings.values().forEach(b->Arrays.fill(b.secret(),(byte)0));bindings.clear();}
}
