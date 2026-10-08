package com.datacube.export;

import com.datacube.service.ConnectionManager;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** Cross-package FX seam uses the real TableExporter, writers, publisher and JDBC owner. */
public final class TableExportJdbcTestJobs {
 public static final class Control {
  final AtomicLong clock=new AtomicLong();
  volatile TableExportJdbcJob.Receipt receipt;
  public void advanceCleanup(){clock.addAndGet(100);}
  public boolean settled(){return receipt!=null&&receipt.physicallySettled();}
  public String diagnostic(){return receipt==null?"not-observed":receipt.toString();}
 }
 public static java.nio.file.Path export(ConnectionManager manager,TableExporter.Request request,ResultExportOperation op,Control control)throws Exception{
  return TableExporter.export(manager,request,op,new SafeResultFilePublisher(),(a,b,c,d,e)->{throw new AssertionError("No dump");},Files::newOutputStream,
   new TableExportJdbcJob.Policy(Duration.ofMinutes(10),Duration.ofNanos(1),Duration.ofNanos(2),control.clock::get,value->control.receipt=value));
 }
}