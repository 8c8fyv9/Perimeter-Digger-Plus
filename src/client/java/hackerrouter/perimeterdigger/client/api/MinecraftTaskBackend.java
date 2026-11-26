package hackerrouter.perimeterdigger.client.api;

import com.google.gson.Gson;
import hackerrouter.perimeterdigger.client.config.*;
import hackerrouter.perimeterdigger.client.state.*;
import net.minecraft.client.Minecraft;
import java.util.*;

public final class MinecraftTaskBackend implements AgentTasks.Backend {
    private final WorldConfigManager configs;
    private final AutomationController controller;
    private PerimeterConfig taskConfig;
    public MinecraftTaskBackend(WorldConfigManager configs,AutomationController controller){this.configs=configs;this.controller=controller;}
    public Map<String,Object> context(){
        var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null)return Map.of("connected",false);
        return Map.of("connected",true,"world",configs.identity(),"dimension",mc.level.dimension().identifier().toString(),"minY",mc.level.getMinY(),"maxY",mc.level.getMaxY());
    }
    public void start(CuboidTask task){
        var mc=Minecraft.getInstance();
        if(task.minY()<mc.level.getMinY()||task.maxY()>mc.level.getMaxY())throw new IllegalArgumentException("Y outside dimension build bounds");
        var gson=new Gson();taskConfig=gson.fromJson(gson.toJson(configs.get()),PerimeterConfig.class);task.apply(taskConfig);
        // Task bounds are ephemeral; never replace/save the user's configured mining area.
        try {
            var invalid=controller.start(taskConfig);if(!invalid.isEmpty())throw new IllegalArgumentException(String.join(", ",invalid));
        } catch (RuntimeException e) { controller.handleTickFailure(e);throw e; }
    }
    public void pause(){controller.pause();}
    public void resume(){if(taskConfig==null)throw new IllegalStateException("No task configuration");controller.resume(taskConfig);}
    public void stop(){controller.stop();}
    public Map<String,Object> status(){return Map.of("state",controller.state().name(),"detail",controller.detail().getString(),"changedAt",controller.changedAt().toString(),"mining",controller.miningProgress());}
}
