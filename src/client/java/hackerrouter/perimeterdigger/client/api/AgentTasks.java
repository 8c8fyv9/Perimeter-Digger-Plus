package hackerrouter.perimeterdigger.client.api;

import com.google.gson.*;
import java.util.*;

/** Single-threaded task ownership. All calls run on the Minecraft client thread. */
public final class AgentTasks {
    public interface Backend {
        Map<String,Object> context();
        void start(CuboidTask task);
        void pause(); void resume(); void stop();
        Map<String,Object> status();
    }
    private final Backend backend;
    private CuboidTask task;
    private String result="NONE";
    private final Map<String,CuboidTask> accepted=new LinkedHashMap<>();
    public AgentTasks(Backend backend){this.backend=backend;}
    private String state(){return backend.status().get("state").toString();}
    private boolean active(){return task!=null&&(result.equals("RUNNING")||result.equals("PAUSED"));}
    public void tick(){
        if(!active())return;
        switch(state()) {
            case "COMPLETE" -> result="COMPLETE";
            case "ERROR" -> result="ERROR";
            case "IDLE" -> result="STOPPED";
            case "PAUSED" -> result="PAUSED";
            default -> result="RUNNING";
        }
    }
    public boolean ownsController(){tick();return active();}
    public void disconnect(){if(active())result="DISCONNECTED";}
    public Map<String,Object> snapshot(){
        tick();var out=new LinkedHashMap<String,Object>();out.put("task",task);out.put("result",result);
        out.put("controller",backend.status());out.put("context",backend.context());
        out.put("boundarySemantics","exact_target_cuboid; navigation_and_liquid_sealing_may_modify_outside");
        out.put("completionEvidence","controller_report_only_not_independent_block_verification");return out;
    }
    public Object route(String method,String path,JsonObject input){
        if(method.equals("GET")&&path.equals("/v1/status"))return snapshot();
        if(!method.equals("POST"))throw new IllegalArgumentException("Unsupported route or method");
        if(path.equals("/v1/tasks/plan")){var p=CuboidTask.parse(input);return Map.of("task",p,"volume",p.volume(),"inclusive",true,"chunkAligned",false,"started",false);}
        if(path.equals("/v1/tasks")){
            var next=CuboidTask.parse(input);tick();
            var prior=accepted.get(next.taskId());
            if(prior!=null){if(!prior.equals(next))throw new IllegalStateException("TASK_ID_CONFLICT");return Map.of("accepted",true,"replayed",true,"task",prior,"current",snapshot());}
            // PAUSED with no API task means a chat/debug session is parked; controller.start() rebuilds from scratch.
            if(active()||!Set.of("IDLE","COMPLETE","ERROR","PAUSED").contains(state()))throw new IllegalStateException("BUSY: stop the current controller first");
            var context=backend.context();
            if(!Boolean.TRUE.equals(context.get("connected")))throw new IllegalStateException("NO_WORLD");
            if(!next.world().equals(context.get("world"))||!next.dimension().equals(context.get("dimension")))throw new IllegalStateException("WORLD_MISMATCH");
            // Record before starting: an exceptional/unknown start must never be blindly replayed.
            task=next;accepted.put(next.taskId(),next);result="RUNNING";
            try{backend.start(next);}catch(RuntimeException e){result="ERROR";throw e;}
            return snapshot();
        }
        if(Set.of("/v1/tasks/pause","/v1/tasks/resume","/v1/tasks/stop").contains(path)){
            if(!input.keySet().equals(Set.of("taskId")))throw new IllegalArgumentException("Only taskId is accepted");
            String id=CuboidTask.string(input,"taskId");tick();
            if(task==null||!task.taskId().equals(id))throw new IllegalStateException("TASK_NOT_CURRENT");
            if(path.endsWith("/stop")){if(active()||result.equals("ERROR")){backend.stop();result="STOPPED";}return snapshot();}
            if(!active())throw new IllegalStateException("TASK_NOT_ACTIVE");
            if(path.endsWith("/pause")){if(!result.equals("PAUSED"))backend.pause();}
            else if(result.equals("PAUSED"))backend.resume();
            tick();return snapshot();
        }
        throw new IllegalArgumentException("Unsupported route or method");
    }
}
