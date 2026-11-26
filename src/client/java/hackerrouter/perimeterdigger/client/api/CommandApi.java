package hackerrouter.perimeterdigger.client.api;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.*;

/** Runs only the mod's own command tree, shared with in-game chat. */
public final class CommandApi<S> {
    public record Result(int result, boolean success, List<String> output) {}
    public interface Source<S> {
        S create(List<String> output);
    }
    private final CommandDispatcher<S> dispatcher;
    private final Source<S> source;

    public CommandApi(CommandDispatcher<S> dispatcher, Source<S> source) {
        this.dispatcher = dispatcher;
        this.source = source;
    }

    public Object route(String method, String path, JsonObject input) {
        List<String> output = new ArrayList<>();
        S context = source.create(output);
        if (method.equals("GET") && path.equals("/v1/commands")) {
            return Map.of("commands", List.of(dispatcher.getAllUsage(dispatcher.getRoot(), context, false)));
        }
        if (!method.equals("POST") || !path.equals("/v1/commands")) {
            throw new IllegalArgumentException("Unsupported command route");
        }
        String command = CuboidTask.string(input, "command").strip();
        if (command.startsWith("/")) command = command.substring(1);
        if (!command.equals("perimeterdig") && !command.startsWith("perimeterdig ")) {
            command = "perimeterdig " + command;
        }
        try {
            int result = dispatcher.execute(command, context);
            return new Result(result, result > 0, output);
        } catch (CommandSyntaxException e) {
            output.add(e.getMessage());
            return new Result(0, false, output);
        }
    }
}
