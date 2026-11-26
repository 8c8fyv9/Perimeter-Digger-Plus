package hackerrouter.perimeterdigger.client.api;

import com.google.gson.*;
import hackerrouter.perimeterdigger.client.config.*;
import hackerrouter.perimeterdigger.client.state.ConfiguredColumnarArea;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentApiTest {
    static JsonObject request(){return JsonParser.parseString("{\"taskId\":\"dig-1\",\"world\":\"world-1\",\"dimension\":\"minecraft:overworld\",\"from\":[19,63,-3],\"to\":[15,61,-1]}").getAsJsonObject();}
    static class Fake implements AgentTasks.Backend {
        String state="IDLE";int starts,stops;boolean connected=true,fail;
        public Map<String,Object> context(){return Map.of("connected",connected,"world","world-1","dimension","minecraft:overworld");}
        public void start(CuboidTask t){starts++;state="MINING";if(fail)throw new IllegalStateException("START_FAILED");}
        public void pause(){state="PAUSED";}public void resume(){state="MINING";}public void stop(){stops++;state="IDLE";}
        public Map<String,Object> status(){return Map.of("state",state);}
    }
    @Test void exactInclusiveBoundsAcrossChunks(){
        var task=CuboidTask.parse(request());assertEquals(45,task.volume());
        var config=new PerimeterConfig();task.apply(config);var area=new ConfiguredColumnarArea(config.detectedArea,config.diggingMinY,config.diggingMaxY);
        for(int x=13;x<=21;x++)for(int y=59;y<=65;y++)for(int z=-5;z<=1;z++)assertEquals(x>=15&&x<=19&&y>=61&&y<=63&&z>=-3&&z<=-1,area.contains(x,y,z));
        assertEquals(3,config.detectedArea.scanlines.size());assertEquals(15,config.detectedArea.columnCount);
    }
    @Test void rejectsFractionalMissingUnknownAndHugeBounds(){
        var j=request();j.getAsJsonArray("from").set(0,new JsonPrimitive(1.5));assertThrows(IllegalArgumentException.class,()->CuboidTask.parse(j));
        var missing=request();missing.remove("dimension");assertThrows(IllegalArgumentException.class,()->CuboidTask.parse(missing));
        var unknown=request();unknown.addProperty("chunkRadius",4);assertThrows(IllegalArgumentException.class,()->CuboidTask.parse(unknown));
        assertEquals(3010060200301L,new CuboidTask("t","w","minecraft:overworld",0,0,0,100000,300,100000).volume());
    }
    @Test void planDoesNotStartAndRetriesNeverRepeatStart(){
        var f=new Fake();var tasks=new AgentTasks(f);tasks.route("POST","/v1/tasks/plan",request());assertEquals(0,f.starts);
        tasks.route("POST","/v1/tasks",request());tasks.route("POST","/v1/tasks",request());assertEquals(1,f.starts);
        var conflicting=request();conflicting.getAsJsonArray("to").set(0,new JsonPrimitive(18));assertThrows(IllegalStateException.class,()->tasks.route("POST","/v1/tasks",conflicting));
        var other=request();other.addProperty("taskId","dig-2");assertThrows(IllegalStateException.class,()->tasks.route("POST","/v1/tasks",other));
    }
    @Test void controlsCompletionDisconnectAndStaleIds(){
        var f=new Fake();var tasks=new AgentTasks(f);tasks.route("POST","/v1/tasks",request());var id=new JsonObject();id.addProperty("taskId","dig-1");
        tasks.route("POST","/v1/tasks/pause",id);assertEquals("PAUSED",tasks.snapshot().get("result"));tasks.route("POST","/v1/tasks/resume",id);assertEquals("RUNNING",tasks.snapshot().get("result"));
        f.state="COMPLETE";assertEquals("COMPLETE",tasks.snapshot().get("result"));tasks.route("POST","/v1/tasks",request());assertEquals(1,f.starts);
        var second=request();second.addProperty("taskId","dig-2");tasks.route("POST","/v1/tasks",second);assertThrows(IllegalStateException.class,()->tasks.route("POST","/v1/tasks/stop",id));
        tasks.disconnect();assertEquals("DISCONNECTED",tasks.snapshot().get("result"));
    }
    @Test void rejectsWrongWorldAndManualBusy(){
        var f=new Fake();var tasks=new AgentTasks(f);var j=request();j.addProperty("world","other");assertThrows(IllegalStateException.class,()->tasks.route("POST","/v1/tasks",j));assertEquals(0,f.starts);
        f.state="MINING";assertThrows(IllegalStateException.class,()->tasks.route("POST","/v1/tasks",request()));
        f.state="IDLE";f.connected=false;assertThrows(IllegalStateException.class,()->tasks.route("POST","/v1/tasks",request()));
    }
    @Test void failedStartNotReplayedAndStopCleansUp(){
        var f=new Fake();f.fail=true;var tasks=new AgentTasks(f);assertThrows(IllegalStateException.class,()->tasks.route("POST","/v1/tasks",request()));
        tasks.route("POST","/v1/tasks",request());assertEquals(1,f.starts);assertEquals("ERROR",tasks.snapshot().get("result"));
        var id=new JsonObject();id.addProperty("taskId","dig-1");tasks.route("POST","/v1/tasks/stop",id);assertEquals(1,f.stops);
    }
    @Test void realHttpAuthDispatchAndPayloadLimits() throws Exception {
        var f=new Fake();var tasks=new AgentTasks(f);var game=Executors.newSingleThreadExecutor();
        try(var server=new AgentHttpServer(0,"test-token",tasks::route,game::execute);var http=HttpClient.newHttpClient()){
            var base="http://127.0.0.1:"+server.port();
            assertEquals(403,http.send(HttpRequest.newBuilder(URI.create(base+"/v1/status")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            var post=HttpRequest.newBuilder(URI.create(base+"/v1/tasks")).header("Authorization","Bearer test-token").POST(HttpRequest.BodyPublishers.ofString(request().toString())).build();
            var response=http.send(post,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());assertEquals("RUNNING",JsonParser.parseString(response.body()).getAsJsonObject().get("result").getAsString());
            assertEquals(200,http.send(post,HttpResponse.BodyHandlers.ofString()).statusCode());assertEquals(1,f.starts);
            var cross=HttpRequest.newBuilder(URI.create(base+"/v1/status")).header("Authorization","Bearer test-token").header("Origin","http://example.com").GET().build();assertEquals(403,http.send(cross,HttpResponse.BodyHandlers.ofString()).statusCode());
            var huge=HttpRequest.newBuilder(URI.create(base+"/v1/tasks")).header("Authorization","Bearer test-token").POST(HttpRequest.BodyPublishers.ofString("x".repeat(9000))).build();assertEquals(400,http.send(huge,HttpResponse.BodyHandlers.ofString()).statusCode());
        }finally{game.shutdownNow();}
    }
}
