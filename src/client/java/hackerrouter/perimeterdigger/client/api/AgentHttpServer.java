package hackerrouter.perimeterdigger.client.api;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Opt-in loopback JSON API; game operations are dispatched to the client thread. */
public final class AgentHttpServer implements AutoCloseable {
    public interface Router {Object route(String method,String path,JsonObject body);}
    private final HttpServer server;
    private final ExecutorService workers;
    private final String token;
    private final Router router;
    private final Consumer<Runnable> dispatch;
    private static final Gson GSON=new GsonBuilder().serializeNulls().create();
    public AgentHttpServer(int port,String token,Router router,Consumer<Runnable> dispatch) throws java.io.IOException {
        this.token=token;this.router=router;this.dispatch=dispatch;
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),8);
        workers=Executors.newFixedThreadPool(2,r->{var t=new Thread(r,"perimeter-agent-http");t.setDaemon(true);return t;});
        server.setExecutor(workers);server.createContext("/",this::handle);server.start();
    }
    public int port(){return server.getAddress().getPort();}
    public static AgentHttpServer start(Path directory,Router router,Consumer<Runnable> dispatch) throws java.io.IOException {
        Files.createDirectories(directory);String token=UUID.randomUUID().toString();
        var api=new AgentHttpServer(Integer.getInteger("perimeterdig.api.port",18769),token,router,dispatch);
        try{Files.writeString(directory.resolve("connection.json"),GSON.toJson(Map.of("url","http://127.0.0.1:"+api.port(),"token",token)),StandardCharsets.UTF_8);}
        catch(java.io.IOException e){api.close();throw e;}return api;
    }
    private void handle(HttpExchange ex) throws java.io.IOException {
        int code=200;Object result;
        try {
            if(ex.getRequestHeaders().containsKey("Origin")||!("Bearer "+token).equals(ex.getRequestHeaders().getFirst("Authorization")))throw new SecurityException("Unauthorized");
            String method=ex.getRequestMethod(),path=ex.getRequestURI().getPath();
            if(!(method.equals("GET")&&(path.equals("/v1/status") || path.equals("/v1/commands")))&&!method.equals("POST"))throw new IllegalArgumentException("Unsupported method");
            byte[] bytes=ex.getRequestBody().readNBytes(8193);if(bytes.length>8192)throw new IllegalArgumentException("Body exceeds 8192 bytes");
            JsonObject input=bytes.length==0?new JsonObject():JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
            var future=new CompletableFuture<Object>();
            dispatch.accept(()->{if(future.isCancelled())return;try{future.complete(router.route(method,path,input));}catch(RuntimeException e){future.completeExceptionally(e);}});
            try{result=future.get(10,TimeUnit.SECONDS);}catch(TimeoutException e){future.cancel(false);code=504;result=Map.of("code","OUTCOME_UNKNOWN","error","Query status; do not submit a new taskId to retry");}
        }catch(Exception e){Throwable cause=e;while(cause instanceof ExecutionException&&cause.getCause()!=null)cause=cause.getCause();code=cause instanceof SecurityException?403:cause instanceof IllegalStateException?409:400;result=Map.of("code",code==409?"CONFLICT":"REQUEST_ERROR","error",Objects.toString(cause.getMessage(),cause.getClass().getSimpleName()));}
        byte[] bytes=GSON.toJson(result).getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");ex.getResponseHeaders().set("Cache-Control","no-store");ex.sendResponseHeaders(code,bytes.length);try(var out=ex.getResponseBody()){out.write(bytes);}finally{ex.close();}
    }
    public void close(){server.stop(0);workers.shutdownNow();}
}
