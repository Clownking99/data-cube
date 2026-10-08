package com.datacube.service;

import com.datacube.spi.*;
import com.datacube.spi.model.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionManagerExportSnapshotTest {
 @Test void abaUnregisterAndProviderReplacementInvalidatePinnedIdentityAndUnsubscribe()throws Exception{
  var fixture=new TableExportJdbcMocks();var manager=fixture.manager;var target=manager.captureExport("synthetic");var events=new AtomicInteger();var listener=target.whenChanged(events::incrementAndGet);
  manager.register(fixture.config("changed"));manager.register(fixture.config("synthetic"));assertFalse(target.current());assertEquals(2,events.get());
  listener.close();listener.close();manager.unregister("synthetic");assertEquals(2,events.get());manager.register(fixture.config("synthetic"));assertFalse(target.current());
  var next=manager.captureExport("synthetic");fixture.provider=new TableExportJdbcMocks().provider;assertFalse(next.current());
  var late=new AtomicInteger();try(var ignored=next.whenChanged(late::incrementAndGet)){assertEquals(1,late.get(),"Atomic subscribe closes capture/provider window");}
  manager.register(fixture.config("synthetic"));assertFalse(next.current());assertEquals(0,fixture.owners.size());
 }
 @Test void equalRegistrationRetainsCurrentSnapshotAndDoesNotNotify()throws Exception{
  var fixture=new TableExportJdbcMocks();var snapshot=fixture.manager.captureExport("synthetic");var intent=new AtomicInteger();
  try(var ignored=snapshot.whenChanged(intent::incrementAndGet)){fixture.manager.register(fixture.config("synthetic"));assertTrue(snapshot.current());assertEquals(0,intent.get());}
 }
 @Test void configurationAndRedisRegistrationRemainBehindOriginalSynchronizationWhileExportIntentPrecedesSharedClose()throws Exception{
  var fixture=new TableExportJdbcMocks();fixture.manager.acquire("synthetic");var snapshot=fixture.manager.captureExport("synthetic");
  var announced=new CountDownLatch(1);var close=new CountDownLatch(1);var release=new CountDownLatch(1);var outside=new AtomicBoolean();
  fixture.beforeClose=()->{close.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}};
  try(var listener=snapshot.whenChanged(()->{assertTrue(Thread.holdsLock(fixture.manager));announced.countDown();})){
   var update=new FutureTask<Void>(()->{fixture.manager.register(fixture.config("changed"));return null;});Thread.ofVirtual().start(update);
   try{
    assertTrue(announced.await(5,TimeUnit.SECONDS));assertTrue(close.await(5,TimeUnit.SECONDS));
    var capture=new FutureTask<Void>(()->{fixture.manager.captureExport("synthetic");outside.set(true);return null;});Thread.ofVirtual().start(capture);
    assertThrows(TimeoutException.class,()->capture.get(60,TimeUnit.MILLISECONDS));assertFalse(outside.get());
    release.countDown();update.get(5,TimeUnit.SECONDS);capture.get(5,TimeUnit.SECONDS);assertFalse(snapshot.current());
   }finally{release.countDown();update.get(5,TimeUnit.SECONDS);}
  }
 }
}