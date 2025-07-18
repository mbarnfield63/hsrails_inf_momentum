package no.netb.mc.hsrails;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.RedstoneRail;
import org.bukkit.entity.Minecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class MinecartListener implements Listener {

    static class MinecartState {
        int blocksCoasted = 0;

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof MinecartState) {
                MinecartState s = (MinecartState) obj;
                return s.blocksCoasted == this.blocksCoasted;
            }
            return super.equals(obj);
        }

        @Override
        public int hashCode() {
            return Objects.hash(blocksCoasted);
        }
    }

    /**
     * Default speed, in meters per tick. A tick is 0.05 seconds, thus 0.4 * 1/0.05 = 8 m/s
     */
    private final MinecartSpeedGameruleValue currentDefaultSpeedMetersPerTick;
    private final Map<Integer, MinecartState> boostedMinecarts = new HashMap<>();
    private final Material boostBlock;
    private final Material hardBrakeBlock;
    private final boolean isCheatMode;

    public MinecartListener(Material boostBlock, Material hardBrakeBlock, boolean isCheatMode) {
        this.boostBlock = boostBlock;
        this.hardBrakeBlock = hardBrakeBlock;
        this.isCheatMode = isCheatMode;
        this.currentDefaultSpeedMetersPerTick = new MinecartSpeedGameruleValue();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onVehicleMove(VehicleMoveEvent event) {
        if (event.getVehicle() instanceof Minecart) {
            Minecart cart = (Minecart) event.getVehicle();
            final boolean isEnteredNewBlock = !event.getTo().getBlock().equals(event.getFrom().getBlock());
            if (!HsRails.debuggers.isEmpty() && isEnteredNewBlock) {
                Vector v = cart.getVelocity();
                log(true, "velocity: [%f %f] |%f|", v.getX(), v.getZ(), v.length());
            }

            final Integer entityId = event.getVehicle().getEntityId();
            final Location cartLocation = cart.getLocation();
            final World cartsWorld = cart.getWorld();

            final Block rail = cartsWorld.getBlockAt(cartLocation);
            final double defaultSpeed = currentDefaultSpeedMetersPerTick.obtain(cartsWorld);
            final double speedMultiplier = HsRails.getConfiguration().getSpeedMultiplier();
            final double boostedMaxSpeed = defaultSpeed * speedMultiplier;

            if (rail.getType() == Material.POWERED_RAIL) {
                Block blockBelow = cartsWorld.getBlockAt(cartLocation.add(0, -1, 0));

                if (isCheatMode || blockBelow.getType() == boostBlock) {
                    if (!boostedMinecarts.containsKey(entityId)) { // if cart is not in high speed state then make it high speed
                        boostedMinecarts.put(entityId, new MinecartState());
                        cart.setMaxSpeed(boostedMaxSpeed);
                        log(false, "minecart [%d] added", entityId);
                    } else { // the cart is already in high speed state; refresh the values since we are on a boost block.
                        boostedMinecarts.get(entityId).blocksCoasted = 0;
                        if (cart.getMaxSpeed() != boostedMaxSpeed) {
                            cart.setMaxSpeed(boostedMaxSpeed);
                            log(false, "minecart [%d] max speed refreshed", entityId);
                        }
                    }
                } else {
                    // If the minecart was previously boosted but now is on a normal powered rail without boost block
                    if (boostedMinecarts.containsKey(entityId)) {
                        boostedMinecarts.remove(entityId);
                        cart.setMaxSpeed(defaultSpeed);
                        
                        // Reset velocity to default speed, in the direction of current movement normalized
                        Vector velocity = cart.getVelocity();
                        if (velocity.lengthSquared() > 0) {
                            Vector normalized = velocity.normalize();
                            cart.setVelocity(normalized.multiply(defaultSpeed));
                        } else {
                            // If velocity is zero, give it a default forward direction (optional)
                            cart.setVelocity(new Vector(defaultSpeed, 0, 0)); // Adjust direction if needed
                        }

                        log(false, "minecart [%d] speed and velocity reset on normal powered rail", entityId);
                    }
                }
                
                RedstoneRail railBlockData = (RedstoneRail) rail.getBlockData();
                if (!railBlockData.isPowered()
                        && blockBelow.getType() == hardBrakeBlock) {
                    Vector cartVelocity = cart.getVelocity();
                    cartVelocity.multiply(HsRails.getConfiguration().getHardBrakeMultiplier());
                    cart.setVelocity(cartVelocity);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onVehicleDestroyed(VehicleDestroyEvent event) {
        Integer vehicleId = event.getVehicle().getEntityId();
        boolean wasPresent = boostedMinecarts.remove(vehicleId) != null;
        if (wasPresent) {
            log(false, "minecart [%d] evicted", vehicleId);
        } else {
            log(false, "minecart [%d] was already cleared", vehicleId);
        }
    }

    private void log(boolean verbose, String template, Object... args) {
        for (DebugSubscription subscriber : HsRails.debuggers.values()) {
            if (!verbose || subscriber.getAcceptsVerbose()) {
                subscriber.getPlayer().sendMessage(String.format(template, args));
            }
        }
    }
}
