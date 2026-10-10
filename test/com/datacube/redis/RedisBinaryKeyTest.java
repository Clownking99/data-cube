package com.datacube.redis;

import org.junit.jupiter.api.Test;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class RedisBinaryKeyTest {
    private static RedisDisplayLimits limits(int keys,int bytes,int nodes,int depth) {
        return RedisDisplayBudgetTest.limits(32,keys,bytes,nodes,depth,2,16,128,32);
    }
    @Test void contentIdentityDefensivelyCopiesInputsAndReturnedArrays() {
        byte[] raw={(byte)0xff,0,':'};RedisKey key=RedisKey.of(raw),equal=RedisKey.of(raw.clone());
        int hash=key.hashCode();raw[0]=1;byte[] exposed=key.bytes();exposed[1]=2;
        assertEquals(equal,key);assertEquals(hash,key.hashCode());assertEquals(equal.hashCode(),key.hashCode());
        assertArrayEquals(new byte[]{(byte)0xff,0,':'},key.bytes());
        assertNotEquals(RedisKey.utf8("�\0:"),key);assertNotEquals("hex:ff003a",key);
    }
    @Test void mixedPageKeepsTextBinaryControlsEmptyAndDistinctTextLookalikes() {
        byte[] binary={(byte)0xff};byte[] control="a:\n".getBytes(UTF_8);
        String lookalike="⟦hex#2 1B⟧ ff";
        var page=new RedisSession.ScanPage(19,List.of("user:a".getBytes(UTF_8),binary,control,new byte[0],binary.clone(),lookalike.getBytes(UTF_8),"�".getBytes(UTF_8)));
        var snapshot=RedisKeySnapshot.candidate(null,page,0,"*",":",RedisDisplayLimits.DEFAULT);
        assertEquals(6,snapshot.keys().size());assertEquals(6,snapshot.tree().keyCount());assertEquals(19,snapshot.cursor());
        assertEquals(page.values().stream().mapToInt(v->v.length).sum()-1,snapshot.rawBytes());
        assertEquals("user",snapshot.tree().children().getFirst().segment());
        assertEquals(RedisKey.utf8("user:a"),snapshot.tree().children().getFirst().children().getFirst().key());
        var leaves=flatten(snapshot.tree());assertTrue(leaves.contains(RedisKey.of(binary)));assertTrue(leaves.contains(RedisKey.of(control)));assertTrue(leaves.contains(RedisKey.of(new byte[0])));
        assertNotEquals(RedisKey.of(binary).display(64),RedisKey.utf8(lookalike).display(64));
        assertNull(RedisKey.of(control).text());assertNull(RedisKey.utf8("a\u202eb").text());
        assertEquals("",RedisKey.of(new byte[0]).text());assertEquals("⟦empty⟧",RedisKey.of(new byte[0]).display(32));
        assertThrows(UnsupportedOperationException.class,()->snapshot.keys().clear());
        binary[0]=0;assertTrue(snapshot.keys().containsKey(RedisKey.of(new byte[]{(byte)0xff})));
    }
    private static List<RedisKey> flatten(KeyTreeBuilder.Node node) {
        List<RedisKey> result=new ArrayList<>();if(node.key()!=null)result.add(node.key());for(var child:node.children())result.addAll(flatten(child));return result;
    }
    @Test void sameBoundedPreviewDoesNotMergeRawKeysOrTextNamespace() {
        byte[] a=new byte[64],b=new byte[64];Arrays.fill(a,(byte)0xff);Arrays.fill(b,(byte)0xff);b[63]=0;
        RedisKey ka=RedisKey.of(a),kb=RedisKey.of(b);
        assertEquals(ka.display(32),kb.display(32));assertNotEquals(ka,kb);
        var tree=KeyTreeBuilder.buildKeys(List.of(ka,kb,RedisKey.utf8("⟦hex#1 64B⟧ ffff")),":",RedisDisplayLimits.DEFAULT);
        assertEquals(3,tree.keyCount());assertEquals(3,flatten(tree).size());
        assertEquals(3,tree.children().stream().map(KeyTreeBuilder.Node::segment).distinct().count());
        assertTrue(tree.children().getFirst().segment().startsWith("⟦text⟧"));
    }
    @Test void pagesDeduplicateByBytesChargeRawBudgetAndRejectWholeCandidate() {
        var small=limits(2,2,3,1);byte[] ff={(byte)0xff};
        var first=RedisKeySnapshot.candidate(null,new RedisSession.ScanPage(7,List.of(ff)),0,"*",":",small);
        var next=RedisKeySnapshot.candidate(first,new RedisSession.ScanPage(-1,List.of(ff.clone(),new byte[]{0})),0,"*",":",small);
        assertEquals(2,next.keys().size());assertEquals(2,next.rawBytes());assertEquals(-1,next.cursor());
        assertThrows(IllegalArgumentException.class,()->RedisKeySnapshot.candidate(next,new RedisSession.ScanPage(0,List.of(new byte[]{1})),0,"*",":",small));
        assertEquals(-1,next.cursor());assertEquals(2,next.keys().size());
        assertEquals(0,RedisKeySnapshot.candidate(next,new RedisSession.ScanPage(0,List.of()),0,"*",":",small).cursor());
        assertThrows(IllegalArgumentException.class,()->RedisKeySnapshot.candidate(null,new RedisSession.ScanPage(0,List.of(ff,new byte[]{0})),0,"*",":",limits(3,4,2,1)));
        assertThrows(IllegalArgumentException.class,()->RedisKeySnapshot.candidate(null,new RedisSession.ScanPage(0,List.of(new byte[5])),0,"*",":",limits(3,4,8,1)));
    }
    @Test void legacyTreeConversionStopsAtFirstCountOrByteBudgetRefusal() {
        for(var budget:List.of(limits(1,4,8,1),limits(3,1,8,1))) {
            AtomicInteger reads=new AtomicInteger();
            List<String> controlled=new AbstractList<>() {
                public int size(){return 3;}
                public String get(int index){reads.incrementAndGet();if(index>=2)throw new AssertionError("visited after budget refusal");return index==0?"a":"b";}
            };
            assertThrows(IllegalArgumentException.class,()->KeyTreeBuilder.build(controlled,":",budget));assertEquals(2,reads.get());
        }
    }
    @Test void clipboardIsCompleteLiteralTextOrExplicitHexAndFactoriesRejectBeforeEncoding() {
        assertEquals("user:中文",RedisKey.utf8("user:中文").clipboardText(32));
        assertEquals("hex:ff000a",RedisKey.of(new byte[]{(byte)0xff,0,10}).clipboardText(32));
        assertEquals("",RedisKey.of(new byte[0]).clipboardText(1));
        assertThrows(IllegalArgumentException.class,()->RedisKey.of(new byte[]{-1,0,1}).clipboardText(8));
        assertThrows(IllegalArgumentException.class,()->RedisKey.utf8("\ud800"));
        assertThrows(RedisException.class,()->RedisKey.utf8("x".repeat(RedisResourceLimits.DEFAULT.requestPayloadBytes()+1)));
        assertThrows(IllegalArgumentException.class,()->RedisKey.of(new byte[RedisResourceLimits.DEFAULT.requestPayloadBytes()+1]));
    }
}
