package com.lky.kaipay.webhook.delivery;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;

/** Explicit offline operator command. Does not bootstrap Spring, schedule work or contact any receiver. */
public final class WebhookEndpointCli {
    private WebhookEndpointCli() {}
    public static void main(String[] args)throws Exception {
        if(!(args.length==8&&"enroll".equals(args[0]))&&!(args.length==4&&"retire".equals(args[0])))
            throw new IllegalArgumentException("Use enroll merchant actor reason url pins keyId secretAlias, or retire endpoint actor reason");
        if(!Set.of("enroll","retire").contains(args[0])||!args[2].matches("[A-Za-z0-9_-]{1,100}")||!args[3].matches("[A-Z_]{1,40}"))
            throw new IllegalArgumentException("Invalid operator command");
        UUID subject=UUID.fromString(args[1]);byte[] material=null;
        try(var connection=DriverManager.getConnection(required("DB_URL"),required("DB_USERNAME"),required("DB_PASSWORD"))){
            connection.setAutoCommit(false);
            try{
                UUID merchant;
                if(args[0].equals("enroll"))merchant=subject;
                else try(var find=connection.prepareStatement("SELECT merchant_id FROM merchant_webhook_endpoints WHERE id=?")){
                    find.setObject(1,subject);try(var rs=find.executeQuery()){if(!rs.next())throw new IllegalArgumentException("Unknown endpoint");merchant=rs.getObject(1,UUID.class);}
                }
                String lockSql="SELECT id FROM merchants WHERE id=?"+(args[0].equals("enroll")?" AND status='ACTIVE'":"")+" FOR UPDATE";
                try(var lock=connection.prepareStatement(lockSql)){
                    lock.setObject(1,merchant);try(var rs=lock.executeQuery()){if(!rs.next())throw new IllegalArgumentException("Merchant unavailable");}
                }
                if(args[0].equals("retire")){
                    try(var update=connection.prepareStatement("UPDATE merchant_webhook_endpoints SET state='RETIRED',retired_at=NOW(),retired_by=?,retirement_reason_code=? WHERE id=? AND state='ACTIVE'")){
                        update.setString(1,args[2]);update.setString(2,args[3]);update.setObject(3,subject);
                        if(update.executeUpdate()!=1)throw new IllegalArgumentException("Endpoint not active");
                    }
                }else{
                    String url=args[4];var uri=java.net.URI.create(url);String host=uri.getHost(),path=uri.getRawPath();
                    var pins=List.of(args[5].split(","));var policy=new WebhookAddressPolicy();policy.endpoint(url,host,path);policy.addresses(pins);
                    UUID key=UUID.fromString(args[6]);String alias=args[7];
                    if(!alias.matches("[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("Invalid secret alias");
                    Path root=Path.of(required("WEBHOOK_SECRET_DIRECTORY")).toAbsolutePath().normalize();WebhookSecrets.restrictedPermissions(root);
                    material=WebhookSecrets.readRestricted(root.resolve(alias));
                    // Avoid reusing material even when an operator chooses a different alias/revision.
                    try(var aliases=connection.prepareStatement("SELECT secret_ref FROM merchant_webhook_endpoints");var rs=aliases.executeQuery()){
                        while(rs.next()){
                            Path file=root.resolve(rs.getString(1));
                            if(java.nio.file.Files.exists(file)){
                                byte[] other=WebhookSecrets.readRestricted(file);
                                try{if(java.security.MessageDigest.isEqual(material,other))throw new IllegalArgumentException("Secret material already used");}
                                finally{Arrays.fill(other,(byte)0);}
                            }
                        }
                    }
                    try(var insert=connection.prepareStatement("""
                            INSERT INTO merchant_webhook_endpoints(id,merchant_id,url,hostname,path,pins,key_id,secret_ref,state,created_by,reason_code)
                            VALUES (?,?,?,?,?,?,?,?,'ACTIVE',?,?)
                            """)){
                        insert.setObject(1,UUID.randomUUID());insert.setObject(2,merchant);insert.setString(3,url);insert.setString(4,host);insert.setString(5,path);
                        insert.setArray(6,connection.createArrayOf("text",pins.toArray()));insert.setObject(7,key);insert.setString(8,alias);insert.setString(9,args[2]);insert.setString(10,args[3]);insert.executeUpdate();
                    }
                }
                connection.commit();System.out.println("Webhook configuration recorded. Dispatch remains disabled.");
            }catch(Exception e){connection.rollback();throw new IllegalStateException("Webhook configuration rejected; no changes committed");}
        }finally{if(material!=null)Arrays.fill(material,(byte)0);}
    }
    private static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("Missing "+key);return value;}
}
