package com.datacube.redis;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class RedisDisplayBudgetTest {
    static RedisDisplayLimits limits(int chars,int keys,int bytes,int nodes,int depth,int rows,int cell,int page,int editor) {
        return new RedisDisplayLimits(chars,3,64,3,32,keys,bytes,Math.min(bytes,64),nodes,depth,8,32,32,rows,cell,page,editor);
    }
    private static final RedisDisplayLimits SMALL=limits(32,3,16,8,3,2,16,128,32);
    @Test void utf8TextHexAndJsonAreBoundedAndModesCanRestoreCompleteEditing() {
        byte[] raw="a".repeat(16).getBytes(UTF_8);
        var value=RedisDisplaySupport.stringViews(raw,true,SMALL);
        assertTrue(value.editable(RedisDisplaySupport.Mode.TEXT)); assertFalse(value.editable(RedisDisplaySupport.Mode.HEX));
        assertTrue(value.editable(RedisDisplaySupport.Mode.TEXT));
        var range=RedisDisplaySupport.stringViews(raw,false,SMALL);
        for(var mode:RedisDisplaySupport.Mode.values()) assertFalse(range.editable(mode));
        assertEquals("😀",RedisDisplaySupport.text("😀".getBytes(UTF_8),2,false).text());
        assertFalse(RedisDisplaySupport.text("😀".getBytes(UTF_8),1,false).complete());
        assertNull(RedisDisplaySupport.text(new byte[]{(byte)0xc0,(byte)0x80},32,false));
        assertNull(RedisDisplaySupport.text(new byte[]{(byte)0xed,(byte)0xa0,(byte)0x80},32,false));
        var binary=RedisDisplaySupport.stringViews(new byte[]{0,-1,1},true,SMALL);
        assertFalse(binary.editable(RedisDisplaySupport.Mode.TEXT)); assertTrue(binary.editable(RedisDisplaySupport.Mode.HEX));
        assertEquals("00 ff 01",binary.hex().text());
        var deep=RedisDisplaySupport.prettyJson(new RedisDisplaySupport.Preview("[".repeat(6000),true),32,3);
        assertFalse(deep.complete()); assertTrue(deep.text().length()<=32);
    }
    @Test void consoleRejectsBeforeTokenizeAndFormatsCyclesUnknownObjectsAndBinaryPreview() {
        assertEquals(List.of("x".repeat(32)),RedisConsoleSupport.tokenize("x".repeat(32),SMALL));
        assertThrows(IllegalArgumentException.class,()->RedisConsoleSupport.tokenize("x".repeat(33),SMALL));
        List<Object> cycle=new ArrayList<>(); cycle.add(cycle);
        var preview=RedisConsoleSupport.format(cycle,SMALL); assertFalse(preview.complete()); assertTrue(preview.text().length()<=32);
        Object hostile=new Object(){ @Override public String toString(){ fail("unknown toString"); return ""; } };
        assertFalse(RedisConsoleSupport.format(hostile,SMALL).complete());
        byte[] binary=new byte[8*1024*1024]; binary[0]=-1;
        var hex=RedisConsoleSupport.format(binary,SMALL); assertFalse(hex.complete()); assertTrue(hex.text().length()<=32); assertTrue(hex.text().startsWith("(hex)"));
        Object deep=1L; for(int i=0;i<6000;i++) deep=List.of(deep);
        assertFalse(RedisConsoleSupport.format(deep,SMALL).complete());
    }
    @Test void retentionEnforcesBothDimensionsAndOversizeEntriesAreNotRetained() {
        RedisTextRetention count=new RedisTextRetention(2,10);
        count.add("a"); count.add("b"); assertEquals(1,count.add("c")); assertEquals(List.of("b","c"),count.values());
        RedisTextRetention chars=new RedisTextRetention(10,4);
        chars.add("ab"); chars.add("cd"); assertEquals(1,chars.add("ef")); assertEquals(4,chars.characters());
        assertEquals(2,chars.add("12345")); assertEquals(0,chars.size()); assertEquals(0,chars.characters());
    }
    @Test void scanCandidatesPreserveCursorOnRejectedFinalPageDuplicatesAndEmptyPages() {
        var first=RedisKeySnapshot.candidate(null,page(7,"a:b","a"),0,"*",":",SMALL);
        var duplicate=RedisKeySnapshot.candidate(first,page(-1,"a:b"),0,"*",":",SMALL);
        assertEquals(2,duplicate.keys().size()); assertEquals(4,duplicate.rawBytes()); assertEquals(-1,duplicate.cursor());
        var empty=RedisKeySnapshot.candidate(duplicate,page(3),0,"*",":",SMALL); assertEquals(3,empty.cursor());
        assertThrows(IllegalArgumentException.class,()->RedisKeySnapshot.candidate(first,page(0,"c","d"),0,"*",":",SMALL));
        assertEquals(7,first.cursor()); assertEquals(List.of("a:b","a"),first.keys().keySet().stream().map(RedisKey::text).toList());
        var mixed=RedisKeySnapshot.candidate(first,new RedisSession.ScanPage(0,List.of(new byte[]{-1})),0,"*",":",SMALL);
        assertEquals(3,mixed.keys().size());assertTrue(mixed.keys().containsKey(RedisKey.of(new byte[]{-1})));assertEquals(0,mixed.cursor());assertEquals(7,first.cursor());
        assertThrows(IllegalArgumentException.class,()->RedisKeySnapshot.candidate(first,page(0,"a:b:c:d"),0,"*",":",SMALL));
    }
    @Test void countIsOnlyAHintAndKeyByteTreeDepthAndNodeBoundariesAreIndependent() {
        List<byte[]> sixHundred=new ArrayList<>(); for(int i=0;i<600;i++) sixHundred.add(("k"+i).getBytes(UTF_8));
        assertEquals(600,RedisKeySnapshot.candidate(null,new RedisSession.ScanPage(0,sixHundred),0,"*",":",RedisDisplayLimits.DEFAULT).keys().size());
        var exact=limits(32,2,4,3,1,2,16,128,32);
        assertEquals(2,RedisKeySnapshot.candidate(null,page(1,"ab","cd"),0,"*","",exact).keys().size());
        assertThrows(RuntimeException.class,()->RedisKeySnapshot.candidate(null,page(0,"ab","cde"),0,"*","",exact));
        var leaf=KeyTreeBuilder.build(List.of("a:b"),":",SMALL).children().getFirst().children().getFirst();
        assertEquals("a:b",leaf.fullKey()); assertEquals(1,leaf.keyCount());
        assertThrows(IllegalArgumentException.class,()->KeyTreeBuilder.build(List.of("a:b:c:d"),":",SMALL));
        assertThrows(IllegalArgumentException.class,()->KeyTreeBuilder.build(List.of("a","b"),"",limits(32,3,16,2,3,2,16,128,32)));
    }
    @Test void collectionWholePageRejectsAndRawIdentityIndexNeverComesFromDisplay() {
        byte[] identity="long member beyond cell".getBytes(UTF_8);
        var page=RedisDisplaySupport.set(new RedisSession.ScanPage(17,List.of(identity)),SMALL);
        assertSame(identity,page.rows().getFirst().rawA()); assertTrue(page.rows().getFirst().a().length()<=16);
        assertThrows(IllegalArgumentException.class,()->RedisDisplaySupport.set(new RedisSession.ScanPage(0,List.of(identity,identity,identity)),SMALL));
        assertThrows(IllegalArgumentException.class,()->RedisDisplaySupport.set(new RedisSession.ScanPage(0,List.of(identity)),limits(32,3,16,8,3,2,16,65,32)));
        var index=RedisDisplaySupport.list(List.of(identity),123456789L,999999999L,limits(32,3,16,8,3,2,2,128,32)).rows().getFirst();
        assertTrue(index.a().length()<=2); assertNotEquals("123456789",index.a());
        assertEquals(123456789L,index.index()); assertSame(identity,index.rawB());
    }
    @Test void inputHexAndUtf8PreflightIncludesWholeCommandBeforeValueCopy() {
        assertArrayEquals(new byte[]{0,-1,1},RedisDisplaySupport.valueBytes("00 ff\n01",true,"SET","k"));
        assertThrows(IllegalArgumentException.class,()->RedisDisplaySupport.valueBytes("0",true,"SET","k"));
        assertThrows(IllegalArgumentException.class,()->RedisDisplaySupport.valueBytes("zz",true,"SET","k"));
        byte[] hugeField=new byte[RedisResourceLimits.DEFAULT.requestPayloadBytes()];
        assertThrows(RedisException.class,()->RedisDisplaySupport.valueBytes("value",false,"HSET","k",hugeField));
        assertThrows(RedisException.class,()->RedisDisplaySupport.valueBytes("00",true,"HSET","k",hugeField));
        assertArrayEquals("中文".getBytes(UTF_8),RedisDisplaySupport.valueBytes("中文",false,"SET","k"));
    }
    @Test void pageRejectStopsBeforeFormattingOrVisitingLaterValues() {
        List<byte[]> guarded=new java.util.AbstractList<>() {
            @Override public int size(){return 2;}
            @Override public byte[] get(int i){if(i>0) fail("formatted tail after page budget rejection"); return "first-cell".getBytes(UTF_8);}
        };
        assertThrows(IllegalArgumentException.class,()->RedisDisplaySupport.set(new RedisSession.ScanPage(0,guarded),limits(32,3,16,8,3,2,16,65,32)));
        var tiny=RedisDisplaySupport.stringViews(new byte[]{-1},true,limits(32,3,16,8,3,2,16,128,2));
        assertFalse(tiny.text().complete()); assertTrue(tiny.text().text().length()<=2); assertTrue(tiny.json().text().length()<=2);
    }
    private static RedisSession.ScanPage page(long cursor,String... keys) { return new RedisSession.ScanPage(cursor,List.of(keys).stream().map(value->value.getBytes(UTF_8)).toList()); }
}
