package com.datacube.provider.jdbc;

import com.datacube.spi.RowWriteException;
import com.datacube.spi.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JdbcRowTransactionTest {
    static final TableRef TABLE=new TableRef("synthetic","items");
    static final RowKey KEY=new RowKey(List.of("id","name"),Arrays.asList(1,null));
    static LinkedHashMap<String,String> values(){return new LinkedHashMap<>(Map.of("id","2","name",""));}
    @ParameterizedTest @ValueSource(strings={"insert","update","delete"})
    void everyMutationCommitsExactlyOneRowWithParameterBinding(String kind) throws Exception {
        var probe=new RowJdbcProbe();
        try(var connection=probe.open()) {
            var editor=new JdbcDataEditor(connection,probe.dialect);
            int result=switch(kind){case "insert"->editor.insert(TABLE,values());case "update"->editor.update(TABLE,values(),KEY);default->editor.delete(TABLE,KEY);};
            assertEquals(1,result);assertEquals(1,probe.commits.get());assertEquals(0,probe.rollbacks.get());
            assertEquals(List.of(false,true),probe.autoModes);assertFalse(probe.sql.getFirst().contains("synthetic-secret"));
            if(!kind.equals("insert")){assertTrue(probe.sql.getFirst().contains("\"name\" IS NULL"));assertTrue(probe.bindings.getFirst().containsValue(1));}
            if(!kind.equals("delete"))assertTrue(probe.bindings.getFirst().containsValue(""));
        }
    }
    @ParameterizedTest @ValueSource(ints={0,2})
    void zeroAndMultipleMatchesRollbackInsteadOfCommitting(int affected) throws Exception {
        for(boolean insert:List.of(false,true)){
            var probe=new RowJdbcProbe();probe.counts.add(affected);
            try(var connection=probe.open()){
                var editor=new JdbcDataEditor(connection,probe.dialect);
                var failure=assertThrows(RowGuardException.class,()->{if(insert)editor.insert(TABLE,values());else editor.delete(TABLE,KEY);});
                assertEquals(affected,failure.affectedRows());assertEquals(0,probe.commits.get());assertEquals(1,probe.rollbacks.get());
                assertEquals(List.of(false,true),probe.autoModes);
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"execute","commit","execute rollback","restore"})
    void driverFailuresPreserveHonestOutcomeAndNeverRestoreAnUnknownTransaction(String fault) throws Exception {
        var probe=new RowJdbcProbe();probe.faults.add(fault);
        try(var connection=probe.open()){
            var failure=assertThrows(RowWriteException.class,()->new JdbcDataEditor(connection,probe.dialect).update(TABLE,values(),KEY));
            var expected=fault.equals("execute")?RowWriteException.Outcome.ROLLED_BACK:fault.equals("restore")?RowWriteException.Outcome.COMMITTED:RowWriteException.Outcome.UNKNOWN;
            assertEquals(expected,failure.outcome());
            assertEquals(expected==RowWriteException.Outcome.UNKNOWN?List.of(false):List.of(false,true),probe.autoModes);
            assertFalse(failure.getMessage().contains("synthetic-secret-value"));
        }
    }
    @Test void invalidTypeNeverExecutesAndExistingTransactionIsNeverCommitted() throws Exception {
        var probe=new RowJdbcProbe();
        try(var connection=probe.open()){
            var editor=new JdbcDataEditor(connection,probe.dialect);
            var failure=assertThrows(RowWriteException.class,()->editor.insert(TABLE,new LinkedHashMap<>(Map.of("id","secret-not-number"))));
            assertEquals(RowWriteException.Outcome.ROLLED_BACK,failure.outcome());assertEquals(0,probe.executes.get());
            assertFalse(failure.toString().contains("secret-not-number"));
            connection.setAutoCommit(false);
            assertThrows(java.sql.SQLException.class,()->editor.delete(TABLE,KEY));
            assertEquals(0,probe.commits.get());assertEquals(1,probe.rollbacks.get());
        }
    }
}
