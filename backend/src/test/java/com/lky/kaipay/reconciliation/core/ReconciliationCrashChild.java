package com.lky.kaipay.reconciliation.core;

import java.sql.DriverManager;

/** Disposable child process terminates at the real runner commit boundaries. */
public final class ReconciliationCrashChild {
    public static void main(String[] args)throws Exception{
        int n=Integer.parseInt(args[3]);String stage=args[4];
        var hooks=new ReconciliationRunner.Hooks(){
            public void beforeCommit(){if(stage.equals("BEFORE"))Runtime.getRuntime().halt(19);}
            public void afterCommit(){if(stage.equals("AFTER"))Runtime.getRuntime().halt(19);}
        };
        try(var c=DriverManager.getConnection(args[0],args[1],args[2])){
            new ReconciliationRunner(hooks,ReconciliationCoreIntegrationTest.NOW).run(c,ReconciliationCoreIntegrationTest.request(n),"group","build");
        }
    }
}
