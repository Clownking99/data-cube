import com.datacube.redis.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Outside-image acceptance; no config/profile/database access. */
public final class G10LinkedRedisProbe {
    private static final java.nio.charset.Charset UTF8=StandardCharsets.UTF_8;
    private static byte[] bytes(String text){return text.getBytes(UTF8);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static Object decode(InputStream input) throws Exception {
        Class<?> codec=Class.forName("com.datacube.redis.RespCodec");Method method=codec.getDeclaredMethod("decode",InputStream.class);method.setAccessible(true);
        try{return method.invoke(null,input);}catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Exception exception)throw exception;if(cause instanceof Error error)throw error;throw failure;}
    }
    private static List<String> request(Socket socket) throws Exception {
        Object result=decode(socket.getInputStream());check(result instanceof List<?>,"request shape");return ((List<?>)result).stream().map(value->new String((byte[])value,UTF8)).toList();
    }
    private static void reply(Socket socket,String response)throws IOException{socket.getOutputStream().write(bytes(response));socket.getOutputStream().flush();}
    public static void main(String[] args)throws Exception {
        check("com.datacube".equals(RespClient.class.getModule().getName()),"product must come from linked named module");
        List<String> trace=Collections.synchronizedList(new ArrayList<>());AtomicReference<Throwable> failure=new AtomicReference<>();
        try(ServerSocket server=new ServerSocket()) {
            server.bind(new InetSocketAddress("127.0.0.1",0));server.setSoTimeout(3000);
            Thread peer=Thread.ofPlatform().start(()->{try{
                try(Socket socket=server.accept()){socket.setSoTimeout(3000);trace.add(String.join(" ",request(socket)));reply(socket,"+OK\r\n");trace.add(String.join(" ",request(socket)));reply(socket,"+OK\r\n");trace.add(String.join(" ",request(socket)));}
                try(Socket socket=server.accept()){socket.setSoTimeout(3000);trace.add(String.join(" ",request(socket)));reply(socket,"+OK\r\n");trace.add(String.join(" ",request(socket)));reply(socket,"$5\r\nvalue\r\n");trace.add(String.join(" ",request(socket)));reply(socket,"-WRONGTYPE synthetic\r\n");trace.add(String.join(" ",request(socket)));reply(socket,"+PONG\r\n");check(socket.getInputStream().read()==-1,"owned client socket closes");}
            }catch(Throwable error){failure.set(error);}});
            try(RespClient client=new RespClient("127.0.0.1",server.getLocalPort(),"","",2)) {
                client.call("SELECT","7");check(Arrays.equals(bytes("value"),(byte[])client.call("GET","k")),"safe GET retry");
                try{client.call("HGET","k","f");throw new AssertionError("server error required");}catch(RedisException error){check(error.kind()==RedisException.Kind.SERVER && error.getMessage().equals("WRONGTYPE synthetic"),"exact server error");}
                check(Arrays.equals(bytes("PONG"),(byte[])client.call("PING")),"same socket remains usable");
            }finally{peer.join(4000);if(peer.isAlive()){server.close();peer.interrupt();peer.join(1000);}check(!peer.isAlive(),"peer physically settles");}
            if(failure.get()!=null)throw new AssertionError("scripted peer",failure.get());
        }
        check(trace.equals(List.of("SELECT 2","SELECT 7","GET k","SELECT 7","GET k","HGET k f","PING")),"confirmed DB trace: "+trace);
        CountingInput giant=new CountingInput(bytes("*2147483647\r\n"));try{decode(giant);throw new AssertionError("resource refusal required");}catch(Exception refusal){check(refusal.getClass().getName().contains("ReadFailure"),"typed codec rejection");check(giant.reads==13,"no tail read or declaration allocation");}
        RedisDisplayLimits small=new RedisDisplayLimits(32,2,64,2,32,2,64,32,8,3,8,32,32,2,16,128,32);
        var value=RedisDisplaySupport.stringViews(bytes("a".repeat(16)),true,small);check(value.editable(RedisDisplaySupport.Mode.TEXT)&&!value.editable(RedisDisplaySupport.Mode.HEX),"per-mode full editing");
        List<Object> cycle=new ArrayList<>();cycle.add(cycle);check(!RedisConsoleSupport.format(cycle,small).complete(),"cycle format refusal");
        var first=RedisKeySnapshot.candidate(null,new RedisSession.ScanPage(17,List.of(bytes("a"))),0,"*",":",small);
        try{RedisKeySnapshot.candidate(first,new RedisSession.ScanPage(0,List.of(bytes("b"),bytes("c"))),0,"*",":",small);throw new AssertionError("whole candidate rejects");}catch(IllegalArgumentException refusal){check(first.cursor()==17&&first.keys().size()==1,"old scan snapshot retained");}
        byte[] member={0,-1,1};var row=RedisDisplaySupport.list(List.of(member),123456789L,999999999L,small).rows().getFirst();check(row.index()==123456789L&&row.rawB()==member,"raw identity");
        check(Arrays.equals(member,RedisDisplaySupport.valueBytes("00 ff 01",true,"SET","k")),"binary editor preflight");
        System.out.println("G10_LINKED_REDIS=true; confirmedDb=7; serverErrorExact=true; giantHeaderReads=13; boundedDisplay=true; sourceRetained=true; rawIdentity=true; socketsSettled=true; realServices=0; trace="+trace);
    }
    private static final class CountingInput extends ByteArrayInputStream {int reads;CountingInput(byte[] value){super(value);}@Override public synchronized int read(){reads++;return super.read();}}
}
