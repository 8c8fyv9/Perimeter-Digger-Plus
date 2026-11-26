package hackerrouter.perimeterdigger.client.mixin;

import hackerrouter.perimeterdigger.client.PerimeterDiggerClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleRespawn", at = @At("TAIL"))
    private void perimeterRespawn(net.minecraft.network.protocol.game.ClientboundRespawnPacket packet, CallbackInfo ci) {
        PerimeterDiggerClient.onRespawn();
    }
    // TAIL runs after vanilla's packet-thread check and damage handling.
    @Inject(method = "handleDamageEvent", at = @At("TAIL"))
    private void perimeterDamage(ClientboundDamageEventPacket packet, CallbackInfo ci) {
        PerimeterDiggerClient.onDamage(packet);
    }
}
