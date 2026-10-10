package com.datacube.redis;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class RedisBinaryKeySessionTest {
    @Test void everyTypedKeyCommandUsesOriginalBulkBytesIncludingRenameAndEmptyKey() {
        for(byte[] raw:List.of(new byte[]{(byte)0xff,0,':','\n'},new byte[0])) {
            List<String> commands=new ArrayList<>();RedisKey key=RedisKey.of(raw),target=RedisKey.of(new byte[]{0,(byte)0xfe});
            RedisSession session=new RedisSession(args->{
                String command=new String(args[0],UTF_8);commands.add(command);assertArrayEquals(raw,args[1],command);
                // Mutating executor-owned arrays must not change any future command or key identity.
                if(args[1].length>0)args[1][0]=7;
                return switch(command){
                    case "TYPE"->"string".getBytes(UTF_8);case "GET","GETRANGE"->new byte[]{1};
                    case "SET","LSET"->"OK".getBytes(UTF_8);
                    case "RENAME"->{assertArrayEquals(target.bytes(),args[2]);yield "OK".getBytes(UTF_8);}
                    case "HSCAN","SSCAN","ZSCAN"->List.of("0".getBytes(UTF_8),List.of());
                    case "LRANGE"->List.of();default->1L;
                };
            },()->{});
            session.type(key);session.ttl(key);session.expire(key,10);session.persist(key);session.del(key);session.rename(key,target);session.exists(key);
            session.get(key);session.strlen(key);session.getrange(key,0,3);session.set(key,new byte[]{1});
            session.hscan(key,0,1);session.hset(key,new byte[]{2},new byte[]{3});session.hdel(key,new byte[]{2});
            session.llen(key);session.lrange(key,0,1);session.lpush(key,new byte[]{1});session.rpush(key,new byte[]{1});session.lset(key,0,new byte[]{1});session.lrem(key,1,new byte[]{1});
            session.sscan(key,0,1);session.sadd(key,new byte[]{1});session.srem(key,new byte[]{1});
            session.zscan(key,0,1);session.zadd(key,1,new byte[]{1});session.zrem(key,new byte[]{1});session.zcard(key);
            assertEquals(List.of("TYPE","TTL","EXPIRE","PERSIST","DEL","RENAME","EXISTS","GET","STRLEN","GETRANGE","SET","HSCAN","HSET","HDEL","LLEN","LRANGE","LPUSH","RPUSH","LSET","LREM","SSCAN","SADD","SREM","ZSCAN","ZADD","ZREM","ZCARD"),commands);
            assertArrayEquals(raw,key.bytes());
        }
    }
    @Test void wholeCommandPreflightChargesRawKeyBytesAndRetainsStringFacadeBudget() {
        AtomicInteger calls=new AtomicInteger();var tiny=RespBudgetTest.limits(4,4,20,64,8,4,2,8,64,4,1000);
        RedisSession session=new RedisSession(args->{calls.incrementAndGet();return "OK".getBytes(UTF_8);},()->{},tiny);
        RedisKey key=RedisKey.of(new byte[]{(byte)0xff,0,1});
        session.set(key,new byte[]{1,2});assertEquals(1,calls.get()); // SET(3)+raw key(3)+value(2)=8; display is much longer.
        assertThrows(RedisException.class,()->session.set(key,new byte[]{1,2,3}));
        assertThrows(RedisException.class,()->session.set("x".repeat(100000),new byte[0]));assertEquals(1,calls.get());
        byte[][] args=RespCodec.arguments(new Object[]{"GET",key},tiny);assertArrayEquals(key.bytes(),args[1]);
        assertArrayEquals(RespCodec.encode("GET".getBytes(UTF_8),key.bytes()),RespCodec.encodeArguments(new Object[]{"GET",key},tiny));
    }
}
