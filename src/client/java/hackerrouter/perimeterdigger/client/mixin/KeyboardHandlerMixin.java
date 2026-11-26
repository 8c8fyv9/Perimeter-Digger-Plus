package hackerrouter.perimeterdigger.client.mixin;

import hackerrouter.perimeterdigger.client.PerimeterDiggerClient;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"))
    private void stopOnEscape(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (action == GLFW.GLFW_PRESS && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            PerimeterDiggerClient.stopOnEscape();
        }
    }
}
