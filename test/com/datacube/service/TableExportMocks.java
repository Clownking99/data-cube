package com.datacube.service;

import com.datacube.spi.model.*;
import java.sql.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Legacy A/B fixture fields retained; pages now counts cursor read checkpoints, never accessor pages. */
public final class TableExportMocks {
 public enum Failure { NONE,CONNECT,FIRST,MIDDLE }
 public final AtomicInteger opens=new AtomicInteger(),pages=new AtomicInteger();
 public volatile Failure failure=Failure.NONE;
 public volatile Runnable beforePage=()->{},beforeDialect=()->{};
 public volatile Object value="complete value";
 public final ConnectionManager manager;
 public final TableExportJdbcMocks jdbc;
 public TableExportMocks(){
  jdbc=new TableExportJdbcMocks();jdbc.rows=1;
  var base=jdbc.provider;
  jdbc.provider=(com.datacube.spi.DatabaseProvider)java.lang.reflect.Proxy.newProxyInstance(base.getClass().getClassLoader(),new Class<?>[]{com.datacube.spi.DatabaseProvider.class},(p,m,a)->{
   if(m.getName().equals("dialect"))beforeDialect.run();
   try{return m.invoke(base,a);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
  });
  jdbc.beforeOpen=()->{opens.incrementAndGet();jdbc.failure=failure==Failure.CONNECT?"open":"";jdbc.value=value;};
  jdbc.beforeExecute=()->{pages.incrementAndGet();};
  jdbc.beforeNext=()->{
   beforePage.run();int step=pages.incrementAndGet();
   if(failure==Failure.FIRST||failure==Failure.MIDDLE&&step>=3)jdbc.failure="next";
  };
  manager=jdbc.manager;
 }
}