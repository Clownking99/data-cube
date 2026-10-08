import com.datacube.export.*;
import com.datacube.spi.model.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;

/** External acceptance classes only; uses the actual linked product with synthetic JDBC. */
public final class G9RuntimeTableExportProbe {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);Files.createDirectories(root);int successes=0,failures=0;
        for(var format:new ExportFormat[]{ExportFormat.SQL,ExportFormat.XLSX}){
            for(boolean fail:new boolean[]{false,true}){
                var mock=new G9RuntimeJdbcMocks();mock.rows=501;mock.label="Actual.Header";
                var shared=mock.manager.acquire("synthetic");shared.setAutoCommit(false);
                var next=new AtomicInteger();if(fail)mock.beforeNext=()->{if(next.incrementAndGet()==2)mock.failure="next";};
                Path dir=root.resolve(format+"-"+fail);Files.createDirectories(dir);
                Path target=Files.writeString(dir.resolve("result."+format.name().toLowerCase()),"old synthetic target");
                Path neighbor=Files.writeString(dir.resolve("neighbor"),"unrelated synthetic neighbor");
                var operation=new ResultExportOperation();
                var request=new TableExporter.Request("synthetic",new TableRef("S.ch\"ema","T.ab\"le"),ExportContent.DATA,format,SafeResultFilePublisher.capture(target));
                boolean failed=false;
                try{TableExporter.export(mock.manager,request,operation);}catch(Exception expected){failed=true;require(fail,"Unexpected export failure");require(!expected.getMessage().contains("SYNTHETIC_PRIVATE"),"Private source error escaped");}
                require(failed==fail,"Failure outcome");require(operation.published()==!fail,"Publication state");
                require(!operation.cleanupPending()&&operation.cleanupResidues().isEmpty(),"Unsettled cleanup");
                require(mock.owners.size()==2,"Dedicated connection");var owned=mock.owners.getLast();
                require(!mock.owners.getFirst().closed&&mock.owners.getFirst().rollbacks.get()==0,"Shared connection changed");
                require(owned.closed&&owned.rollbacks.get()==1&&owned.readOnly&&!owned.autoCommit,"Owned connection cleanup/read-only");
                require(owned.isolation==java.sql.Connection.TRANSACTION_READ_COMMITTED,"Transaction isolation");
                require(mock.queries.size()==1&&mock.queries.getFirst().equals("SELECT * FROM \"S.ch\"\"ema\".\"T.ab\"\"le\""),"Single quoted cursor");
                require(mock.pages.get()==0,"Paging forbidden");var statement=owned.statements.getFirst();
                require(statement.closed&&statement.cursor.closed&&statement.fetchSize==16,"Cursor ownership/fetch");
                require(mock.exportSubscriptions()==0,"Listener retained");
                require(Files.readString(neighbor).equals("unrelated synthetic neighbor"),"Neighbor changed");
                try(var listing=Files.list(dir)){require(listing.count()==2,"Temporary residue");}
                if(fail){require(mock.getters.get()==1,"Failure injected after one row");require(Files.readString(target).equals("old synthetic target"),"Failed export replaced target");failures++;}
                else{
                    require(mock.getters.get()==501,"Full cursor rows");
                    if(format==ExportFormat.SQL){String sql=Files.readString(target);require(sql.split("'complete value'",-1).length-1==501,"SQL row fidelity");}
                    else{try(var zip=new ZipFile(target.toFile())){String xml=new String(zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readAllBytes(),StandardCharsets.UTF_8);require(xml.contains("Actual.Header")&&xml.split("<row r=",-1).length-1==502,"XLSX row/header fidelity");}}
                    successes++;
                }
                mock.manager.release("synthetic");
            }
        }
        System.out.println("G9_LINKED_SQL_XLSX_SUCCESS="+successes+"; FAILURE_TARGET_RETAINED="+failures+"; actualDriverConnectCalls=0; mockConnections=8; nativeUI=false");
    }
}
