package com.datacube.fx;

import com.datacube.redis.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import javafx.event.ActionEvent;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class RedisPaneBudgetTest {
    private static final RedisDisplayLimits SMALL=new RedisDisplayLimits(64,3,128,3,32,8,128,64,32,4,8,32,32,2,16,128,32);
    private static void gate() throws Exception { FxUiTestSupport.call(()->null); }
    private static byte[] bytes(String value) { return value.getBytes(UTF_8); }
    private static String command(byte[][] args) { return new String(args[0],UTF_8); }
    private static String key(byte[][] args) { return new String(args[1],UTF_8); }
    private static Object scan(long cursor,String... keys) { return List.of(bytes(Long.toUnsignedString(cursor)),List.of(keys).stream().map(RedisPaneBudgetTest::bytes).toList()); }
    @Test void consoleRetentionAndInstallFailureRestoreInputAndAllowNextCommand() throws Exception {
        gate(); AtomicInteger calls=new AtomicInteger(); AtomicBoolean fail=new AtomicBoolean(true);
        AtomicReference<CountDownLatch> installed=new AtomicReference<>(new CountDownLatch(1)); AtomicReference<RedisConsolePane> paneRef=new AtomicReference<>();
        RedisSession session=RedisTestSession.create(args->{calls.incrementAndGet();return bytes("PONG");},()->{});
        try(FxTaskRunner runner=new FxTaskRunner()) {
            RedisConsolePane pane=FxUiTestSupport.call(()->new RedisConsolePane(config(),db->session,RedisSession::close,runner,SMALL,()->{if(fail.getAndSet(false)) throw new IllegalStateException("synthetic install failure"); installed.get().countDown();})); paneRef.set(pane);
            try {
                execute(pane,"PING"); settleConsole(pane);
                for(int i=0;i<5;i++) { installed.set(new CountDownLatch(1)); execute(pane,"PING"); await(installed.get()); FxUiTestSupport.call(()->{assertFalse(find(pane.getNode(),"redis-console-input",TextField.class).isDisable());return null;}); }
                FxUiTestSupport.call(()->{
                    VBox output=find(pane.getNode(),"redis-console-output",VBox.class); assertTrue(output.getChildren().size()<=3);
                    assertTrue(output.getChildren().stream().map(Label.class::cast).mapToInt(label->label.getText().length()).sum()<=128);
                    RedisTextRetention history=field(pane,"history",RedisTextRetention.class); assertEquals(3,history.size()); assertTrue(history.characters()<=32);
                    TextField input=find(pane.getNode(),"redis-console-input",TextField.class); input.setText("x".repeat(65)); assertEquals("",input.getText()); return null;
                }); assertEquals(6,calls.get());
            } finally { FxUiTestSupport.call(()->{pane.close();return null;}); }
        }
    }
    @Test void consoleCloseSettlesBlockedWorkerAndNeverReenablesOrAppendsLateResponse() throws Exception {
        gate(); CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1); AtomicReference<Thread> worker=new AtomicReference<>(); AtomicInteger closes=new AtomicInteger();
        RedisSession session=RedisTestSession.create(args->{worker.set(Thread.currentThread());entered.countDown();awaitIgnoringInterrupt(release);return bytes("LATE");},()->{closes.incrementAndGet();release.countDown();});
        try(FxTaskRunner runner=new FxTaskRunner()) {
            RedisConsolePane pane=FxUiTestSupport.call(()->new RedisConsolePane(config(),db->session,RedisSession::close,runner,SMALL,()->fail("closed callback ran")));
            try { execute(pane,"PING");await(entered);FxUiTestSupport.call(()->{pane.close();return null;});join(worker.get());
                FxUiTestSupport.call(()->{assertTrue(find(pane.getNode(),"redis-console-input",TextField.class).isDisable());assertFalse(find(pane.getNode(),"redis-console-output",VBox.class).getChildren().stream().map(Label.class::cast).anyMatch(label->label.getText().contains("LATE")));return null;}); assertEquals(1,closes.get());
            } finally { release.countDown();pane.close(); }
        }
    }
    @Test void rejectedFinalScanPageAndFxInstallFailurePreserveOldCursorTreeThenRecover() throws Exception {
        gate(); AtomicInteger scans=new AtomicInteger(); List<String> cursors=new CopyOnWriteArrayList<>();
        try(Browser fixture=new Browser(db->args->{if(command(args).equals("SCAN")){cursors.add(key(args));return switch(scans.incrementAndGet()){case 1->scan(7,"a","b");case 2->scan(0,"c","d");default->scan(0,"c");};}return normal(args,new byte[]{'x'});},new RedisDisplayLimits(64,3,128,3,32,3,128,64,32,4,8,32,32,2,16,128,32))) {
            fixture.installed(); Object old=FxUiTestSupport.call(()->tree(fixture.pane).getRoot()); fixture.arm(); fixture.fire("redis-load-more"); fixture.failed();
            FxUiTestSupport.call(()->{assertSame(old,tree(fixture.pane).getRoot()); assertEquals(7,snapshot(fixture.pane).cursor()); assertTrue(find(fixture.pane.getNode(),"redis-status",Label.class).getText().contains("未完整加载"));assertFalse(field(fixture.pane,"busy",Boolean.class));return null;});
            fixture.arm();fixture.fire("redis-load-more");fixture.installed();assertEquals(List.of("0","7","7"),cursors);
            FxUiTestSupport.call(()->{assertEquals(3,snapshot(fixture.pane).keys().size());assertEquals(0,snapshot(fixture.pane).cursor());return null;});
            Object current=FxUiTestSupport.call(()->tree(fixture.pane).getRoot());fixture.hook.set(()->{throw new IllegalStateException("synthetic tree installation failure");});fixture.arm();fixture.fire("redis-refresh");fixture.failed();
            FxUiTestSupport.call(()->{assertSame(current,tree(fixture.pane).getRoot());assertEquals(3,snapshot(fixture.pane).keys().size());return null;});
            fixture.hook.set(()->{});fixture.arm();fixture.fire("redis-refresh");fixture.installed();
            FxUiTestSupport.call(()->{assertEquals(List.of("c"),snapshot(fixture.pane).keys().keySet().stream().map(RedisKey::text).toList());return null;});
        }
    }
    @Test void pendingRefreshWinsOldTreeSelectionAndLatestDbIntentIsNotDropped() throws Exception {
        gate();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);List<Integer> opened=new CopyOnWriteArrayList<>();AtomicInteger scans=new AtomicInteger();
        try(Browser fixture=new Browser(db->{opened.add(db);return args->{if(command(args).equals("SCAN")){scans.incrementAndGet();return scan(0,"a","b");}if(command(args).equals("GET") && key(args).equals("a")){entered.countDown();awaitIgnoringInterrupt(release);}return normal(args,bytes(keySafe(args)));};},SMALL)) {
            fixture.installed();fixture.arm();fixture.select("a");await(entered);fixture.fire("redis-refresh");fixture.select("b");release.countDown();fixture.installed();
            FxUiTestSupport.call(()->{assertFalse(field(fixture.pane,"busy",Boolean.class));assertNull(field(fixture.pane,"displayedKey",RedisKey.class));assertEquals(0,snapshot(fixture.pane).database());return null;});assertEquals(2,scans.get());
            CountDownLatch enteredAgain=new CountDownLatch(1),releaseAgain=new CountDownLatch(1);
            fixture.handlerOverride.set(args->{if(command(args).equals("GET")){enteredAgain.countDown();awaitIgnoringInterrupt(releaseAgain);}return normal(args,bytes("value"));});fixture.arm();fixture.select("a");await(enteredAgain);
            fixture.db(1);fixture.db(2);fixture.select("b");fixture.handlerOverride.set(null);releaseAgain.countDown();fixture.installed();
            assertEquals(List.of(0,2),opened);FxUiTestSupport.call(()->{assertEquals(2,snapshot(fixture.pane).database());assertFalse(field(fixture.pane,"busy",Boolean.class));return null;});
        } finally {release.countDown();}
    }
    @Test void staleValueCallbackCannotOverwriteNewSelection() throws Exception {
        gate();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(Browser fixture=new Browser(db->args->{if(command(args).equals("SCAN"))return scan(0,"a","b");if(command(args).equals("GET") && key(args).equals("a")){entered.countDown();awaitIgnoringInterrupt(release);}return normal(args,bytes(keySafe(args)));},SMALL)) {
            fixture.installed();fixture.arm();fixture.select("a");await(entered);fixture.select("b");release.countDown();fixture.installed();
            FxUiTestSupport.call(()->{assertEquals(RedisKey.utf8("b"),field(fixture.pane,"displayedKey",RedisKey.class));assertEquals("b",find(fixture.pane.getNode(),"redis-string-editor",TextArea.class).getText());return null;});
        } finally {release.countDown();}
    }
    @Test void failedDbSwitchRetainsReadonlyOldSourceAndOldActionCannotMutateNewSession() throws Exception {
        gate();AtomicInteger mutations=new AtomicInteger();
        try(Browser fixture=new Browser(db->args->{if(db==1 && command(args).equals("PING"))throw new IllegalStateException("synthetic DB failure");if(command(args).equals("SCAN"))return scan(0,"a");if(command(args).equals("DEL"))mutations.incrementAndGet();return normal(args,bytes("value"));},SMALL)) {
            fixture.installed();Runnable old=FxUiTestSupport.call(()->fixture.pane.deleteAction("a"));fixture.arm();fixture.db(1);fixture.failed();
            FxUiTestSupport.call(()->{long request=field(fixture.pane,"activeRequest",Long.class);old.run();assertEquals(request,field(fixture.pane,"activeRequest",Long.class));assertFalse(field(fixture.pane,"busy",Boolean.class));assertEquals(0,snapshot(fixture.pane).database());assertTrue(find(fixture.pane.getNode(),"redis-details",VBox.class).isDisable());assertTrue(find(fixture.pane.getNode(),"redis-status",Label.class).getText().contains("db0"));return null;});assertEquals(0,mutations.get());
            fixture.arm();fixture.db(2);fixture.installed();FxUiTestSupport.call(()->{long request=field(fixture.pane,"activeRequest",Long.class);old.run();assertEquals(request,field(fixture.pane,"activeRequest",Long.class));assertFalse(field(fixture.pane,"busy",Boolean.class));return null;});assertEquals(0,mutations.get());
        }
    }
    @Test void stringModesRecoverFullEditingWhileRangeAndOversizeFullLoadNeverSave() throws Exception {
        gate(); AtomicBoolean range=new AtomicBoolean(); AtomicInteger sets=new AtomicInteger();
        try(Browser fixture=new Browser(db->args->{return switch(command(args)){
            case "SCAN"->scan(0,"a");case "STRLEN"->range.get()?9_000_000L:16L;case "GET","GETRANGE"->bytes("a".repeat(16));case "SET"->{sets.incrementAndGet();yield bytes("OK");}default->normal(args,bytes("x"));};},SMALL)) {
            fixture.installed();fixture.arm();fixture.select("a");fixture.installed();
            FxUiTestSupport.call(()->{ComboBox<RedisDisplaySupport.Mode> mode=mode(fixture.pane);Button save=find(fixture.pane.getNode(),"redis-string-save",Button.class);assertFalse(save.isDisable());mode.setValue(RedisDisplaySupport.Mode.HEX);assertTrue(save.isDisable());mode.setValue(RedisDisplaySupport.Mode.TEXT);assertFalse(save.isDisable());return null;});
            range.set(true);fixture.arm();fixture.selectAgain("a");fixture.installed();
            FxUiTestSupport.call(()->{for(var value:RedisDisplaySupport.Mode.values()){mode(fixture.pane).setValue(value);assertTrue(find(fixture.pane.getNode(),"redis-string-save",Button.class).isDisable());}return null;});
            fixture.arm();fixture.fire("redis-string-full");fixture.failed();FxUiTestSupport.call(()->{assertTrue(find(fixture.pane.getNode(),"redis-string-save",Button.class).isDisable());assertFalse(field(fixture.pane,"busy",Boolean.class));return null;});assertEquals(0,sets.get());
        }
    }
    @Test void fiveTypesBinaryEditingAndRowsUseFullRawFieldMemberAndIndex() throws Exception {
        gate();byte[] binary={0,-1,1},field=bytes("field longer than display cell");List<byte[][]> writes=new CopyOnWriteArrayList<>();
        try(Browser fixture=new Browser(db->args->{switch(command(args)){
            case "SCAN":return scan(0,"string","hash","list","set","zset");case "TYPE":return args[1];case "STRLEN":return 3L;case "GET":return binary;
            case "HSCAN":return List.of(bytes("0"),List.of(field,binary));case "LRANGE":return List.of(binary);case "LLEN":return 1L;
            case "SSCAN":return List.of(bytes("0"),List.of(binary));case "ZSCAN":return List.of(bytes("0"),List.of(binary,bytes("1.5")));
            case "SET","HSET","LSET","SADD","ZADD":writes.add(args);return command(args).equals("SET") || command(args).equals("LSET")?bytes("OK"):1L;
            default:return normal(args,binary);
        }},SMALL)) {
            fixture.installed();fixture.arm();fixture.select("string");fixture.installed();fixture.arm();fixture.fire("redis-string-save");fixture.installed();
            assertArrayEquals(binary,writes.getFirst()[2]);
            for(String type:List.of("hash","list","set","zset")) {
                fixture.arm();fixture.select(type);fixture.installed();fixture.arm();
                FxUiTestSupport.call(()->{var row=values(fixture.pane).getItems().getFirst();if(type.equals("set"))fixture.pane.addMemberAction(type,binary,0).run();else if(type.equals("zset"))fixture.pane.updateScoreAction(row.rawA(),"2").run();else fixture.pane.updateRowAction(type,row,binary).run();return null;});fixture.installed();
            }
            assertEquals(5,writes.size());assertArrayEquals(field,writes.get(1)[2]);assertArrayEquals(binary,writes.get(1)[3]);assertEquals("0",new String(writes.get(2)[2],UTF_8));assertArrayEquals(binary,writes.get(2)[3]);assertArrayEquals(binary,writes.get(3)[2]);assertArrayEquals(binary,writes.get(4)[3]);
        }
    }
    @Test void closingAtFactoryPingAndQueuedFxInstallOwnsAndSettlesNewSession() throws Exception {
        gate();
        for(String phase:List.of("factory","ping","install")) {
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),fxBlocked=new CountDownLatch(1),fxRelease=new CountDownLatch(1);
            AtomicReference<Thread> worker=new AtomicReference<>();AtomicInteger closes=new AtomicInteger(),pings=new AtomicInteger(),installs=new AtomicInteger();AtomicReference<RedisKeyBrowserPane> paneRef=new AtomicReference<>();
            try(FxTaskRunner runner=new FxTaskRunner()) {
                RedisKeyBrowserPane pane=FxUiTestSupport.call(()->new RedisKeyBrowserPane(0,db->{
                    worker.set(Thread.currentThread());RedisSession session=RedisTestSession.create(args->{if(command(args).equals("PING")){pings.incrementAndGet();if(phase.equals("ping")){entered.countDown();awaitIgnoringInterrupt(release);}return bytes("PONG");}entered.countDown();if(phase.equals("install"))awaitIgnoringInterrupt(release);return scan(0,"a");},()->{closes.incrementAndGet();release.countDown();});
                    if(phase.equals("factory")){entered.countDown();awaitIgnoringInterrupt(release);}return session;
                },RedisSession::close,runner,SMALL,message->{},installs::incrementAndGet));paneRef.set(pane);
                try {await(entered);if(phase.equals("install")){Platform.runLater(()->{fxBlocked.countDown();awaitIgnoringInterrupt(fxRelease);});await(fxBlocked);release.countDown();join(worker.get());}
                    pane.close();release.countDown();fxRelease.countDown();join(worker.get());FxUiTestSupport.call(()->null);assertEquals(1,closes.get());assertEquals(0,installs.get());if(phase.equals("factory"))assertEquals(0,pings.get());
                } finally {release.countDown();fxRelease.countDown();pane.close();join(worker.get());}
            }
        }
    }
    @Test void listEditKeepsCurrentPageAndUsesLongIndexDespiteTruncatedCell() throws Exception {
        gate();long offset=123456789L;byte[] binary={0,-1,1};List<Long> reads=new CopyOnWriteArrayList<>();List<byte[][]> writes=new CopyOnWriteArrayList<>();
        RedisDisplayLimits tiny=new RedisDisplayLimits(64,3,128,3,32,8,128,64,32,4,8,32,32,2,2,128,32);
        try(Browser fixture=new Browser(db->args->{return switch(command(args)){
            case "SCAN"->scan(0,"list");case "TYPE"->bytes("list");case "LRANGE"->{reads.add(Long.parseLong(new String(args[2],UTF_8)));yield List.of(binary);}
            case "LLEN"->999999999L;case "LSET"->{writes.add(args);yield bytes("OK");}default->normal(args,binary);};},tiny)) {
            fixture.installed();fixture.arm();fixture.select("list");fixture.installed();fixture.arm();
            FxUiTestSupport.call(()->{Object binding=field(fixture.pane,"displayedBinding",Object.class);var method=RedisKeyBrowserPane.class.getDeclaredMethod("loadPage",binding.getClass(),String.class,long.class,boolean.class);method.setAccessible(true);method.invoke(fixture.pane,binding,"list",offset,false);return null;});fixture.installed();fixture.arm();
            FxUiTestSupport.call(()->{var row=values(fixture.pane).getItems().getFirst();assertEquals(offset,row.index());assertTrue(row.a().length()<=2);assertNotEquals(Long.toString(offset),row.a());fixture.pane.updateRowAction("list",row,binary).run();return null;});fixture.installed();
            assertEquals(List.of(0L,offset,offset),reads);assertEquals(1,writes.size());assertEquals(Long.toString(offset),new String(writes.getFirst()[2],UTF_8));assertArrayEquals(binary,writes.getFirst()[3]);
        }
    }
    @Test void collectionRejectAndInstallFailureKeepRowsCursorAndOldEditCannotQueueAfterDbSwitch() throws Exception {
        gate();AtomicInteger pages=new AtomicInteger(),writes=new AtomicInteger();List<String> cursors=new CopyOnWriteArrayList<>();byte[] rawField=bytes("full field beyond display"),value={0,-1,1};
        try(Browser fixture=new Browser(db->args->{return switch(command(args)){
            case "SCAN"->scan(0,"hash");case "TYPE"->bytes("hash");case "HSCAN"->{cursors.add(new String(args[2],UTF_8));int page=pages.incrementAndGet();yield page==2?List.of(bytes("0"),List.of(rawField,value,bytes("b"),value,bytes("c"),value)):List.of(bytes(page==1?"17":"0"),List.of(rawField,value));}
            case "HSET"->{writes.incrementAndGet();yield 1L;}default->normal(args,value);};},SMALL)) {
            fixture.installed();fixture.arm();fixture.select("hash");fixture.installed();Object old=FxUiTestSupport.call(()->values(fixture.pane));Button more=FxUiTestSupport.call(()->find(fixture.pane.getNode(),"redis-value-more",Button.class));
            fixture.arm();FxUiTestSupport.call(()->{more.fire();return null;});fixture.failed();
            FxUiTestSupport.call(()->{assertSame(old,values(fixture.pane));assertFalse(more.isDisable());assertFalse(field(fixture.pane,"busy",Boolean.class));return null;});
            fixture.hook.set(()->{throw new IllegalStateException("synthetic value installation failure");});fixture.arm();FxUiTestSupport.call(()->{more.fire();return null;});fixture.failed();
            FxUiTestSupport.call(()->{assertSame(old,values(fixture.pane));assertFalse(more.isDisable());return null;});
            fixture.hook.set(()->{});fixture.arm();FxUiTestSupport.call(()->{more.fire();return null;});fixture.installed();assertEquals(List.of("0","17","17","17"),cursors);
            Runnable stale=FxUiTestSupport.call(()->{assertTrue(find(fixture.pane.getNode(),"redis-value-more",Button.class).isDisable());var row=values(fixture.pane).getItems().getFirst();assertSame(rawField,row.rawA());assertNotEquals(new String(rawField,UTF_8),row.a());return fixture.pane.updateRowAction("hash",row,value);});
            fixture.arm();fixture.db(1);fixture.installed();FxUiTestSupport.call(()->{long request=field(fixture.pane,"activeRequest",Long.class);stale.run();assertEquals(request,field(fixture.pane,"activeRequest",Long.class));assertFalse(field(fixture.pane,"busy",Boolean.class));return null;});assertEquals(0,writes.get());
        }
    }
    private static ConnConfig config(){return new ConnConfig("synthetic","synthetic",DbType.REDIS,"127.0.0.1",1,"0","","",Map.of());}
    @Test void binaryKeySelectionAndFiveEditorsSendOriginalKeyBytesAndInvalidateOldBinding() throws Exception {
        gate();byte[] raw={(byte)0xff,0,':','\n'},value={1,2},field={0,(byte)0xfe};RedisKey identity=RedisKey.of(raw);
        List<byte[][]> writes=new CopyOnWriteArrayList<>();AtomicReference<String> type=new AtomicReference<>("string");
        try(Browser fixture=new Browser(db->args->{
            String command=command(args);
            if(command.equals("SCAN"))return List.of(bytes("0"),List.of(bytes("user:a"),raw.clone(),new byte[0],raw.clone()));
            if(command.equals("PING"))return bytes("PONG");
            assertArrayEquals(raw,args[1],command);
            return switch(command){
                case "TYPE"->bytes(type.get());case "TTL"->-1L;case "STRLEN"->2L;case "GET","GETRANGE"->value;
                case "HSCAN"->List.of(bytes("0"),List.of(field,value));case "LRANGE"->List.of(value);case "LLEN"->1L;
                case "SSCAN"->List.of(bytes("0"),List.of(value));case "ZSCAN"->List.of(bytes("0"),List.of(value,bytes("1")));
                case "SET","HSET","LSET","SADD","ZADD","DEL"->{writes.add(args);yield command.equals("SET")||command.equals("LSET")?bytes("OK"):1L;}
                default->throw new AssertionError(command);
            };
        },SMALL)) {
            fixture.installed();FxUiTestSupport.call(()->{assertEquals(3,snapshot(fixture.pane).keys().size());return null;});
            fixture.arm();fixture.select(identity);fixture.installed();
            FxUiTestSupport.call(()->{mode(fixture.pane).setValue(RedisDisplaySupport.Mode.HEX);assertEquals("01 02",find(fixture.pane.getNode(),"redis-string-editor",TextArea.class).getText());return null;});
            fixture.arm();fixture.fire("redis-string-save");fixture.installed();
            for(String kind:List.of("hash","list","set","zset")) {
                type.set(kind);fixture.arm();fixture.selectAgain(identity);fixture.installed();fixture.arm();
                FxUiTestSupport.call(()->{var row=values(fixture.pane).getItems().getFirst();
                    if(kind.equals("set"))fixture.pane.addMemberAction(kind,value,0).run();
                    else if(kind.equals("zset"))fixture.pane.updateScoreAction(row.rawA(),"2").run();
                    else fixture.pane.updateRowAction(kind,row,value).run();return null;});fixture.installed();
            }
            assertEquals(List.of("SET","HSET","LSET","SADD","ZADD"),writes.stream().map(RedisPaneBudgetTest::command).toList());
            for(var args:writes)assertArrayEquals(raw,args[1]);assertArrayEquals(field,writes.get(1)[2]);
            assertArrayEquals(value,writes.get(0)[2]);assertArrayEquals(value,writes.get(1)[3]);assertEquals("0",new String(writes.get(2)[2],UTF_8));
            assertArrayEquals(value,writes.get(2)[3]);assertArrayEquals(value,writes.get(3)[2]);assertEquals("2.0",new String(writes.get(4)[2],UTF_8));assertArrayEquals(value,writes.get(4)[3]);
            Runnable oldEdit=FxUiTestSupport.call(()->fixture.pane.updateScoreAction(value,"3"));
            Runnable oldDelete=FxUiTestSupport.call(()->fixture.pane.deleteAction(identity));
            fixture.arm();fixture.fire("redis-refresh");fixture.installed();
            FxUiTestSupport.call(()->{long request=field(fixture.pane,"activeRequest",Long.class);oldEdit.run();oldDelete.run();assertEquals(request,field(fixture.pane,"activeRequest",Long.class));return null;});
            fixture.arm();fixture.select(identity);fixture.installed();
            Runnable beforeDbEdit=FxUiTestSupport.call(()->fixture.pane.updateScoreAction(value,"3"));
            Runnable beforeDbDelete=FxUiTestSupport.call(()->fixture.pane.deleteAction(identity));
            fixture.arm();fixture.db(1);fixture.installed();
            FxUiTestSupport.call(()->{long request=field(fixture.pane,"activeRequest",Long.class);beforeDbEdit.run();beforeDbDelete.run();assertEquals(request,field(fixture.pane,"activeRequest",Long.class));return null;});
            fixture.arm();fixture.select(identity);fixture.installed();
            Runnable beforeCloseEdit=FxUiTestSupport.call(()->fixture.pane.updateScoreAction(value,"3"));
            Runnable beforeCloseDelete=FxUiTestSupport.call(()->fixture.pane.deleteAction(identity));
            FxUiTestSupport.call(()->{fixture.pane.close();long request=field(fixture.pane,"activeRequest",Long.class);beforeCloseEdit.run();beforeCloseDelete.run();assertEquals(request,field(fixture.pane,"activeRequest",Long.class));return null;});assertEquals(5,writes.size());
        }
    }
    @Test void binaryScanOverflowAndInstallFailureKeepOriginalTreeAndCursor() throws Exception {
        gate();byte[] a={(byte)0xff},b={0},c={1};AtomicInteger scans=new AtomicInteger();
        var limits=new RedisDisplayLimits(64,3,128,3,32,2,128,64,32,4,8,32,32,2,16,128,32);
        try(Browser fixture=new Browser(db->args->{if(command(args).equals("SCAN"))return switch(scans.incrementAndGet()){
            case 1->List.of(bytes("7"),List.of(a));case 2->List.of(bytes("0"),List.of(b,c));default->List.of(bytes("0"),List.of(a.clone(),b));};return normal(args,bytes("value"));},limits)) {
            fixture.installed();Object old=FxUiTestSupport.call(()->tree(fixture.pane).getRoot());fixture.arm();fixture.fire("redis-load-more");fixture.failed();
            FxUiTestSupport.call(()->{assertSame(old,tree(fixture.pane).getRoot());assertEquals(7,snapshot(fixture.pane).cursor());assertEquals(1,snapshot(fixture.pane).keys().size());return null;});
            fixture.hook.set(()->{throw new IllegalStateException("synthetic binary install refusal");});fixture.arm();fixture.fire("redis-load-more");fixture.failed();
            FxUiTestSupport.call(()->{assertSame(old,tree(fixture.pane).getRoot());assertEquals(7,snapshot(fixture.pane).cursor());return null;});
            fixture.hook.set(()->{});fixture.arm();fixture.fire("redis-load-more");fixture.installed();
            FxUiTestSupport.call(()->{assertEquals(2,snapshot(fixture.pane).keys().size());assertEquals(2,snapshot(fixture.pane).rawBytes());assertEquals(0,snapshot(fixture.pane).cursor());return null;});
        }
    }
    @Test void delayedBinaryValueCannotOverwriteDifferentRawSelection() throws Exception {
        gate();byte[] first={(byte)0xff},second={(byte)0xfe};RedisKey last=RedisKey.of(second);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(Browser fixture=new Browser(db->args->{
            if(command(args).equals("SCAN"))return List.of(bytes("0"),List.of(first,second));
            if(command(args).equals("GET")){
                if(java.util.Arrays.equals(first,args[1])){entered.countDown();awaitIgnoringInterrupt(release);return bytes("old");}
                assertArrayEquals(second,args[1]);return bytes("new");
            }
            return normal(args,bytes("value"));
        },SMALL)) {
            fixture.installed();fixture.arm();fixture.select(RedisKey.of(first));await(entered);fixture.select(last);release.countDown();fixture.installed();
            FxUiTestSupport.call(()->{assertEquals(last,field(fixture.pane,"displayedKey",RedisKey.class));assertEquals("new",find(fixture.pane.getNode(),"redis-string-editor",TextArea.class).getText());return null;});
        }finally{release.countDown();}
    }
    private static Object normal(byte[][] args,byte[] value){return switch(command(args)){case "PING"->bytes("PONG");case "TYPE"->bytes("string");case "TTL"->-1L;case "STRLEN"->(long)value.length;case "GET","GETRANGE"->value;case "DEL"->1L;default->throw new AssertionError("Unexpected synthetic command: "+command(args));};}
    private static String keySafe(byte[][] args){return args.length>1?key(args):"value";}
    private static void execute(RedisConsolePane pane,String text)throws Exception{FxUiTestSupport.call(()->{TextField input=find(pane.getNode(),"redis-console-input",TextField.class);input.setText(text);input.fireEvent(new ActionEvent());return null;});}
    private static void settleConsole(RedisConsolePane pane)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<end){if(FxUiTestSupport.call(()->!find(pane.getNode(),"redis-console-input",TextField.class).isDisable()))return;Thread.sleep(5);}fail("console failed to settle");}
    private static void await(CountDownLatch latch)throws InterruptedException{assertTrue(latch.await(3,TimeUnit.SECONDS),"controlled callback did not complete");}
    private static void awaitIgnoringInterrupt(CountDownLatch latch){boolean interrupted=false;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);try{while(latch.getCount()!=0 && System.nanoTime()<end){try{if(latch.await(100,TimeUnit.MILLISECONDS))return;}catch(InterruptedException ignored){interrupted=true;}}if(latch.getCount()!=0)throw new AssertionError("controlled gate not released");}finally{if(interrupted)Thread.currentThread().interrupt();}}
    private static void join(Thread worker)throws InterruptedException{if(worker!=null){worker.join(2000);assertFalse(worker.isAlive(),"owned worker leaked");}}
    private static <T>T field(Object owner,String name,Class<T> type)throws Exception{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return type.cast(field.get(owner));}
    private static RedisKeySnapshot snapshot(RedisKeyBrowserPane pane)throws Exception{return field(pane,"snapshot",RedisKeySnapshot.class);}
    @SuppressWarnings("unchecked")private static TreeView<Object> tree(RedisKeyBrowserPane pane){return (TreeView<Object>)(TreeView<?>)find(pane.getNode(),"redis-keys",TreeView.class);}
    @SuppressWarnings("unchecked")private static ComboBox<RedisDisplaySupport.Mode> mode(RedisKeyBrowserPane pane){return (ComboBox<RedisDisplaySupport.Mode>)(ComboBox<?>)find(pane.getNode(),"redis-string-mode",ComboBox.class);}
    @SuppressWarnings("unchecked")private static TableView<RedisDisplaySupport.Row> values(RedisKeyBrowserPane pane){return (TableView<RedisDisplaySupport.Row>)(TableView<?>)find(pane.getNode(),"redis-values",TableView.class);}
    private static <T extends Node>T find(Node root,String id,Class<T> type){T found=findOrNull(root,id,type);if(found!=null)return found;throw new AssertionError("Missing control: "+id);}
    private static <T extends Node>T findOrNull(Node root,String id,Class<T> type){if(id.equals(root.getId()))return type.cast(root);for(Node child:children(root)){T found=findOrNull(child,id,type);if(found!=null)return found;}return null;}
    private static List<? extends Node> children(Node node){if(node instanceof SplitPane split)return split.getItems();if(node instanceof ScrollPane scroll)return scroll.getContent()==null?List.of():List.of(scroll.getContent());return node instanceof Parent parent?parent.getChildrenUnmodifiable():List.of();}
    private static final class Browser implements AutoCloseable {
        final FxTaskRunner runner=new FxTaskRunner();final RedisKeyBrowserPane pane;
        final AtomicReference<CountDownLatch> install=new AtomicReference<>(new CountDownLatch(1)),error=new AtomicReference<>(new CountDownLatch(1));
        final AtomicReference<Runnable> hook=new AtomicReference<>(()->{});final AtomicReference<Function<byte[][],Object>> handlerOverride=new AtomicReference<>();
        Browser(Function<Integer,Function<byte[][],Object>> handlers,RedisDisplayLimits limits)throws Exception{
            try {pane=FxUiTestSupport.call(()->new RedisKeyBrowserPane(0,db->{Function<byte[][],Object> handler=handlers.apply(db);return RedisTestSession.create(args->{Function<byte[][],Object> override=handlerOverride.get();return (override==null?handler:override).apply(args);},()->{});},RedisSession::close,runner,limits,message->error.get().countDown(),()->{hook.get().run();install.get().countDown();}));}
            catch(Exception | Error failure){runner.close();throw failure;}
        }
        void arm(){install.set(new CountDownLatch(1));error.set(new CountDownLatch(1));}
        void installed()throws Exception{await(install.get());FxUiTestSupport.call(()->null);}
        void failed()throws Exception{await(error.get());FxUiTestSupport.call(()->null);}
        void fire(String id)throws Exception{FxUiTestSupport.call(()->{find(pane.getNode(),id,Button.class).fire();return null;});}
        void db(int db)throws Exception{FxUiTestSupport.call(()->{find(pane.getNode(),"redis-database",ComboBox.class).setValue(db);return null;});}
        void select(String key)throws Exception{FxUiTestSupport.call(()->{TreeItem<Object> selected=findItem(tree(pane).getRoot(),key);tree(pane).getSelectionModel().select(selected);return null;});}
        void selectAgain(String key)throws Exception{FxUiTestSupport.call(()->{tree(pane).getSelectionModel().clearSelection();return null;});select(key);}
        void select(RedisKey key)throws Exception{FxUiTestSupport.call(()->{TreeItem<Object> item=findIdentity(tree(pane).getRoot(),key);assertNotNull(item);tree(pane).getSelectionModel().select(item);return null;});}
        void selectAgain(RedisKey key)throws Exception{FxUiTestSupport.call(()->{tree(pane).getSelectionModel().clearSelection();return null;});select(key);}
        private static TreeItem<Object> findIdentity(TreeItem<Object> item,RedisKey key)throws Exception{
            var accessor=item.getValue().getClass().getDeclaredMethod("key");accessor.setAccessible(true);
            if(key.equals(accessor.invoke(item.getValue())))return item;
            for(var child:item.getChildren()){var found=findIdentity(child,key);if(found!=null)return found;}return null;
        }
        private static TreeItem<Object> findItem(TreeItem<Object> root,String key){if(root.getValue().toString().equals(key))return root;for(var child:root.getChildren()){TreeItem<Object> found=findItemOrNull(child,key);if(found!=null)return found;}throw new AssertionError("key missing");}
        private static TreeItem<Object> findItemOrNull(TreeItem<Object> item,String key){if(item.getValue().toString().equals(key))return item;for(var child:item.getChildren()){TreeItem<Object> found=findItemOrNull(child,key);if(found!=null)return found;}return null;}
        @Override public void close()throws Exception{pane.close();runner.close();}
    }
}
