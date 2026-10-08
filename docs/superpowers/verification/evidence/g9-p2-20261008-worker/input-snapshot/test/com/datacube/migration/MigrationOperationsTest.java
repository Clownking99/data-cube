package com.datacube.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MigrationOperationsTest {
    @TempDir Path root;
    private MigrationRequest request() {
        return new MigrationRequest(new MigrationRequest.Endpoint("synthetic-source","lowercase-login","fake-source-secret"),new MigrationRequest.Endpoint("synthetic-target","pg","fake-target-secret"),"OWNER","dest",root.resolve("dest"),MigrationRequest.Mode.EMPTY_TABLES_ONLY,false);
    }
    @Test void dataExportAuthenticatesAsLoginUsesOwnerForObjectsAndNeverOpensTarget() throws Exception {
        var db=new MigrationTableExporterTest.Db();var opens=Collections.synchronizedList(new ArrayList<String>());
        var operations=new MigrationOperations(new MigrationTestLogger(),(url,user,pass)->{
            assertEquals("synthetic-source",url);assertEquals("lowercase-login",user);assertEquals("fake-source-secret",pass);opens.add(url);return db.connection();
        });
        operations.export(request(),new MigrationCancellation(),1,true);
        assertEquals(2,opens.size());assertEquals(1,db.dataReads);assertEquals(1,db.rollbacks);
        var evidence=MigrationExportEvidence.read(root.resolve("dest/data/items.sql"));assertNotNull(evidence);assertEquals(0,evidence.rows());
    }
    @Test void connectionTestOnlyOpensReadOnlyConnectionsAndCancellationPreventsAcquisition() throws Exception {
        List<String> events=new ArrayList<>();
        var operations=new MigrationOperations(new MigrationTestLogger(),(url,user,pass)->{
            events.add(url);return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->switch(m.getName()){
                case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0];
                case "setReadOnly"->{assertEquals(true,a[0]);events.add("readonly");yield null;}
                case "close"->{events.add("close");yield null;}default->throw new AssertionError(m.getName());
            });
        });
        operations.test(request(),new MigrationCancellation());
        assertEquals(List.of("synthetic-source","readonly","close","synthetic-target","readonly","close"),events);
        var cancelled=new MigrationCancellation();cancelled.cancel();
        assertThrows(java.util.concurrent.CancellationException.class,()->operations.test(request(),cancelled));assertEquals(6,events.size());
    }
}
