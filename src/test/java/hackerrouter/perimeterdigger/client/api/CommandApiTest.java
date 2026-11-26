package hackerrouter.perimeterdigger.client.api;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandApiTest {
    private CommandApi<List<String>> api() {
        var dispatcher = new CommandDispatcher<List<String>>();
        dispatcher.register(LiteralArgumentBuilder.<List<String>>literal("perimeterdig")
            .then(LiteralArgumentBuilder.<List<String>>literal("status").executes(c -> {
                c.getSource().add("IDLE"); return 1;
            }))
            .then(LiteralArgumentBuilder.<List<String>>literal("stop").executes(c -> 1)));
        return new CommandApi<>(dispatcher, output -> output);
    }
    @Test void listsActualRegisteredCommands() {
        var response = api().route("GET", "/v1/commands", new JsonObject()).toString();
        assertTrue(response.contains("perimeterdig status"));
        assertTrue(response.contains("perimeterdig stop"));
    }
    @Test void acceptsShortAndFullCommandsAndCapturesOutput() {
        for (String command : List.of("status", "perimeterdig status", "/perimeterdig status")) {
            var input = new JsonObject(); input.addProperty("command", command);
            var result = (CommandApi.Result) api().route("POST", "/v1/commands", input);
            assertTrue(result.success()); assertEquals(List.of("IDLE"), result.output());
        }
    }
    @Test void syntaxErrorsReturnFailureAndNeverDispatchOtherCommands() {
        var input = new JsonObject(); input.addProperty("command", "/give @s diamond");
        var result = (CommandApi.Result) api().route("POST", "/v1/commands", input);
        assertFalse(result.success()); assertFalse(result.output().isEmpty());
    }
}
