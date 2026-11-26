package hackerrouter.perimeterdigger.client.api;

import java.lang.reflect.*;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Capture command messages while delegating world/player/suggestion access to Fabric. */
public final class MinecraftCommandSource {
    private MinecraftCommandSource() {}

    public static FabricClientCommandSource create(List<String> output) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.getConnection() == null) {
            throw new IllegalStateException("Enter a world before using mod commands");
        }
        var source = (FabricClientCommandSource) client.getConnection().getSuggestionsProvider();
        return (FabricClientCommandSource) Proxy.newProxyInstance(
            FabricClientCommandSource.class.getClassLoader(),
            new Class<?>[] { FabricClientCommandSource.class },
            (proxy, method, args) -> {
                if (method.getName().equals("sendFeedback") || method.getName().equals("sendError")) {
                    output.add(((Component) args[0]).getString());
                    return null;
                }
                try {
                    return method.invoke(source, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
    }
}
