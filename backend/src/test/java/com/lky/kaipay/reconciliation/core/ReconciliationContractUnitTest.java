package com.lky.kaipay.reconciliation.core;

import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;
import java.lang.reflect.Proxy;
import static org.assertj.core.api.Assertions.*;

class ReconciliationContractUnitTest {
    static final Instant NOW=Instant.parse("2026-10-22T04:00:00Z");
    static AuthorizationSource approved(){
        var s=new AuthorizationSource();s.paymentId=UUID.fromString("30000000-0000-0000-0000-000000000001");s.amount=5000;s.version=1;s.currency="MYR";s.status="AUTHORIZED";s.createdAt=NOW.minusSeconds(3600);s.updatedAt=NOW.minusSeconds(600);
        s.operationPresent=true;s.merchantMatches=true;s.validShape=true;s.namespace="kaipay-sim-auth-v1";s.contractVersion=1;s.operationAmount=5000L;s.operationCurrency="MYR";s.disposition="APPROVED";s.evaluationCount=1L;
        s.operationCreatedAt=NOW.minusSeconds(3000);s.operationUpdatedAt=NOW.minusSeconds(600);s.resultAt=NOW.minusSeconds(600);s.referencePresent=true;s.referenceMatches=true;s.codeMatches=true;s.messageMatches=true;return s;
    }
    @Test void unsupportedAndImpossibleProjectionFailsClosedWithoutSqlConstraintWeakening(){
        var classifier=new AuthorizationClassifier();
        for(String field:List.of("STATUS_NULL","STATUS_UNKNOWN","DISPOSITION_NULL","DISPOSITION_UNKNOWN","CONTRACT","NAMESPACE","COUNT","MISSING_RESULT","SHAPE","MISSING_TIME")){
            var s=approved();switch(field){case "STATUS_NULL"->s.status=null;case "STATUS_UNKNOWN"->s.status="PRIVATE_STATUS";case "DISPOSITION_NULL"->s.disposition=null;case "DISPOSITION_UNKNOWN"->s.disposition="PRIVATE_DISPOSITION";case "CONTRACT"->s.contractVersion=2;case "NAMESPACE"->s.namespace="PRIVATE_NAMESPACE";case "COUNT"->s.evaluationCount=-1L;case "MISSING_RESULT"->s.resultAt=null;case "SHAPE"->s.validShape=false;case "MISSING_TIME"->s.createdAt=null;}
            var result=classifier.classify(s,NOW);assertThat(result.primaryClass()).as(field).isEqualTo("EVIDENCE_INVALID");assertThat(result.evidence().toString()).doesNotContain("PRIVATE_");
        }
    }
    @Test void fingerprintIsCanonicalAndRunIdIndependentWhileInputsRemainDistinct(){
        UUID merchant=UUID.fromString("10000000-0000-0000-0000-000000000001");
        var q=new RunRequest(UUID.randomUUID(),merchant,RunRequest.SCOPE,RunRequest.RULESET,NOW.minusSeconds(86400),NOW.minusSeconds(600),"operator","VERIFICATION");
        var same=new RunRequest(UUID.randomUUID(),merchant,q.scope(),q.ruleset(),q.fromInclusive(),q.cutoffExclusive(),q.actor(),q.reason());assertThat(q.fingerprint()).isEqualTo(same.fingerprint()).matches("[a-f0-9]{64}");
        var changed=new RunRequest(q.runId(),merchant,q.scope(),q.ruleset(),q.fromInclusive().plusNanos(1000),q.cutoffExclusive(),q.actor(),q.reason());assertThat(q.fingerprint()).isNotEqualTo(changed.fingerprint());
        assertThatThrownBy(()->new RunRequest(q.runId(),merchant,q.scope(),q.ruleset(),q.fromInclusive().plusNanos(1),q.cutoffExclusive(),q.actor(),q.reason())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new RunRequest(q.runId(),merchant,q.scope(),q.ruleset(),q.fromInclusive(),q.cutoffExclusive(),"unsafe actor","VERIFICATION")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void exactSevenDayWindowSupportedAndUnsupportedContractRejected(){
        var q=new RunRequest(UUID.randomUUID(),UUID.randomUUID(),RunRequest.SCOPE,RunRequest.RULESET,NOW.minusSeconds(604800),NOW,"operator","VERIFICATION");q.validateNew();
        assertThatThrownBy(()->new RunRequest(q.runId(),q.merchantId(),q.scope(),"AUTHORIZATION_V2",q.fromInclusive(),q.cutoffExclusive(),q.actor(),q.reason()).validateNew()).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void primaryRequestMismatchRetainsIndependentFinalStateAndFieldReasons(){
        var s=approved();s.amount=5001;s.status="FAILED";s.referenceMatches=false;s.codeMatches=false;s.messageMatches=false;
        var result=new AuthorizationClassifier().classify(s,NOW);assertThat(result.primaryClass()).isEqualTo("REQUEST_MISMATCH");
        assertThat(result.reasonCodes()).containsExactly("AMOUNT_DIFFERS","CODE_DIFFERS","MESSAGE_DIFFERS","REFERENCE_DIFFERS","STATE_DIFFERS");
    }
    @Test void acquisitionDeadlineReturnsWithoutWaitingForAnUninterruptibleDriverAndClosesLateConnection()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var aborted=new CountDownLatch(1);
        var driver=new Driver(){
            public Connection connect(String url,Properties p)throws SQLException{
                if(!acceptsURL(url))return null;entered.countDown();
                boolean interrupted=false;while(true){try{release.await();break;}catch(InterruptedException e){interrupted=true;}}
                if(interrupted)Thread.currentThread().interrupt();
                return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{if(method.getName().equals("abort")||method.getName().equals("close"))aborted.countDown();return null;});
            }
            public boolean acceptsURL(String url){return url.startsWith("jdbc:reconciliation-delayed:");}
            public DriverPropertyInfo[] getPropertyInfo(String u,Properties p){return new DriverPropertyInfo[0];}
            public int getMajorVersion(){return 1;}public int getMinorVersion(){return 0;}public boolean jdbcCompliant(){return false;}public Logger getParentLogger(){return Logger.getAnonymousLogger();}
        };
        DriverManager.registerDriver(driver);
        try{
            long start=System.nanoTime();
            assertThatThrownBy(()->ReconciliationCli.open("jdbc:reconciliation-delayed:test",new Properties(),System.nanoTime()-TimeUnit.MILLISECONDS.toNanos(14700))).isInstanceOf(SQLTimeoutException.class);
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
            release.countDown();assertThat(aborted.await(2,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->ReconciliationCli.open("jdbc:postgresql://localhost/test?socketTimeout=0",new Properties(),System.nanoTime())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->ReconciliationCli.open("jdbc:postgresql://localhost/test?x=1&connectTimeout=0",new Properties(),System.nanoTime())).isInstanceOf(IllegalArgumentException.class);
        }finally{release.countDown();DriverManager.deregisterDriver(driver);}
    }
}
