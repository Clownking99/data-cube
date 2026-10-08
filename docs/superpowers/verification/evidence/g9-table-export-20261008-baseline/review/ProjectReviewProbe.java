import com.datacube.export.*;
import com.datacube.provider.postgres.PgSqlDialect;
import com.datacube.spi.model.TableRef;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
public final class ProjectReviewProbe {
  public static void main(String[] args) throws Exception {
    Path root=Path.of(args[0]);
    for(String format:List.of("sql","xlsx")) {
      Path out=root.resolve("synthetic-existing."+format);
      byte[] before="SYNTHETIC ORIGINAL BYTES".getBytes(StandardCharsets.UTF_8);
      Files.write(out,before,StandardOpenOption.CREATE_NEW);
      RowFeed feed=sink->{sink.row(List.of("synthetic row"));throw new IOException("synthetic feed failure");};
      String failure="NONE";
      try {
        if(format.equals("xlsx")) XlsxWriter.write(out.toFile(),List.of("C"),feed);
        else SqlScriptExporter.write(out.toFile(),new TableRef("review","synthetic"),ExportContent.DATA,null,List.of("C"),feed,new PgSqlDialect());
      } catch(Exception expected) {failure=expected.getClass().getSimpleName();}
      boolean preserved=Arrays.equals(before,Files.readAllBytes(out));
      if(failure.equals("NONE")||preserved)throw new AssertionError("Diagnostic expectation changed: "+format);
      System.out.println("{\"case\":\"writer-failure-"+format+"\",\"exception\":\""+failure+"\",\"oldBytesPreserved\":"+preserved+",\"afterBytes\":"+Files.size(out)+"}");
    }
    Method decode=Class.forName("com.datacube.redis.RespCodec").getDeclaredMethod("decode",InputStream.class);
    decode.setAccessible(true);
    try {
      decode.invoke(null,new ByteArrayInputStream("*2147483647\r\n".getBytes(StandardCharsets.US_ASCII)));
      throw new AssertionError("Expected bounded child diagnostic");
    } catch(InvocationTargetException invocation) {
      Throwable actual=invocation.getCause();
      if(!(actual instanceof OutOfMemoryError))throw new AssertionError(actual);
      System.out.println("{\"case\":\"redis-array-length-header\",\"exception\":\"OutOfMemoryError\",\"heapLimitMiB\":32,\"networkCalls\":0}");
    }
  }
}
