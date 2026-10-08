package com.datacube.export;

import com.datacube.service.TableExportJdbcMocks;
import com.datacube.spi.model.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class TableExportJdbcBaselineRedTest {
    @TempDir Path root;
    void export(TableExportJdbcMocks mock,ExportFormat format,ExportContent content,Path target)throws Exception{
        TableExporter.export(mock.manager,"synthetic",new TableRef("Synthetic.Schema","Mixed.Table"),content,format,target.toFile());
    }
    @ParameterizedTest @EnumSource(value=ExportFormat.class,names={"SQL","XLSX"})
    void exportOwnsDedicatedSingleCursorAndNeverRollsBackSharedTransaction(ExportFormat format)throws Exception{
        var mock=new TableExportJdbcMocks();var shared=mock.manager.acquire("synthetic");shared.setAutoCommit(false);
        Path target=root.resolve("result"); export(mock,format,ExportContent.DATA,target);
        assertAll(()->assertEquals(2,mock.owners.size(),"Dedicated connection required"),
                ()->assertEquals(0,mock.pages.get(),"No paged accessor or first-row probe"),
                ()->assertEquals(1,mock.executions.get(),"One data SELECT"),
                ()->assertFalse(mock.owners.getFirst().closed),()->assertEquals(0,mock.owners.getFirst().rollbacks.get()));
    }
    @ParameterizedTest @EnumSource(value=ExportFormat.class,names={"SQL","XLSX"})
    void unknownProviderValueNeverReachesToStringOrPublishedOutput(ExportFormat format)throws Exception{
        var mock=new TableExportJdbcMocks(); mock.rows=1;var conversions=new AtomicInteger();
        mock.value=new Object(){public String toString(){conversions.incrementAndGet();return "SYNTHETIC_PRIVATE_VALUE";}};
        Path target=Files.writeString(root.resolve("old"),"old bytes");
        assertThrows(Exception.class,()->export(mock,format,ExportContent.DATA,target));
        assertEquals(0,conversions.get());assertEquals("old bytes",Files.readString(target));
    }
    @Test void xlsxNumericPrecisionLossMustFailInsteadOfPublishingSixteenDigits()throws Exception{
        var mock=new TableExportJdbcMocks();mock.rows=1;mock.value=new BigInteger("1234567890123456");
        Path target=Files.writeString(root.resolve("old"),"old bytes");
        assertThrows(Exception.class,()->export(mock,ExportFormat.XLSX,ExportContent.DATA,target));
        assertEquals("old bytes",Files.readString(target));
    }
    @Test void oracleTableDdlExceptionCannotBecomeSuccessfulSensitiveComment()throws Exception{
        var mock=new TableExportJdbcMocks(DbType.ORACLE);mock.failure="ddl";
        Path target=Files.writeString(root.resolve("old"),"old bytes");
        assertThrows(Exception.class,()->export(mock,ExportFormat.SQL,ExportContent.STRUCTURE,target));
        assertEquals("old bytes",Files.readString(target));
    }
}
