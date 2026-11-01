package ht.treechop.compat.polymer;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import eu.pb4.polymer.common.api.PolymerCommonUtils;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import eu.pb4.polymer.rsm.api.RegistrySyncUtils;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.soundpatcher.api.SoundPatcher;
import eu.pb4.polymer.virtualentity.api.attachment.BlockBoundAttachment;
import ht.treechop.TreeChop;
import ht.treechop.api.ChopDataImmutable;
import ht.treechop.api.TreeChopEvents;
import ht.treechop.common.block.PolymerFabricChoppedLogBlock;
import ht.treechop.common.chop.ChopUtil;
import ht.treechop.common.loot.CountBlockChopsLootItemCondition;
import ht.treechop.common.loot.TreeFelledLootItemCondition;
import ht.treechop.common.registry.FabricModBlocks;
import ht.treechop.server.FabricServer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Empty (invisible) leaves (to prevent culling and BlockDisplay darkening).
 * BlockDisplay to display the chopped log. Break speed uses Polymer server
 * mining. Stand-in leaf sounds are muted via SoundPatcher. Mining uses wood.
 */
public final class TreeChopPolymer {
    /**
     * Vanilla clients never re-send START after a non-felling chop at the same
     * pos (MC-255057). Polymer predictive mining still applies fatigue, so
     * mining stalls until release/reclick. Resume server mining after each chop
     * and abort if the client stops swinging (released or looked away).
     * <p>
     * Chained resumes require a swing since the previous START. Otherwise a
     * release just before the chop lands would keep mining the whole tree
     * (each chop used to refresh the swing timeout).
     */
    private static final int SWING_TIMEOUT_TICKS = 15;

    private static final Map<UUID, ResumeMining> RESUMED_MINING = new ConcurrentHashMap<>();

    private static @Nullable BlockState emptyLeaves;
    private static @Nullable BlockState emptyWaterlogged;

    private record ResumeMining(BlockPos pos, int lastSwingTick, boolean seenSwingSinceStart) {
        ResumeMining withSwing(int tick) {
            return new ResumeMining(pos, tick, true);
        }
    }

    public static void init() {
        PolymerResourcePackUtils.markAsRequired();
        PolymerResourcePackUtils.addModAssets(TreeChop.MOD_ID);

        emptyLeaves = PolymerBlockResourceUtils.requestEmpty(BlockModelType.LEAVES);
        try {
            emptyWaterlogged = PolymerBlockResourceUtils.requestEmpty(BlockModelType.LEAVES_WATERLOGGED);
        } catch (RuntimeException e) {
            emptyWaterlogged = emptyLeaves;
            TreeChop.LOGGER.warn("No empty waterlogged Polymer state!");
        }

        // Empty LEAVES pool starts at azalea (SoundType.AZALEA_LEAVES). Mute stand-in
        // hit/break so client prediction is silent. Send wood hits instead.
        muteStandInSounds(emptyLeaves);
        if (emptyWaterlogged != null && emptyWaterlogged != emptyLeaves)
            muteStandInSounds(emptyWaterlogged);

        PolymerBlockUtils.registerBlockEntity(FabricModBlocks.CHOPPED_LOG_ENTITY, new PolymerSyncedObject<>() {
            @Override
            public BlockEntityType<?> getPolymerReplacement(BlockEntityType<?> type, PacketContext context) {
                // null => Polymer omits this BE from chunk packets / BE updates.
                // TreeChop clients need the real BE on chunk enter (chops/radius/wood);
                // ServerUpdateChopsPacket only fires on setChanged, so without this they
                // render the stripped-oak radius-8 fallback until someone chops again.
                return canSyncRawToClient(context) ? type : null;
            }

            @Override
            public boolean canSyncRawToClient(PacketContext context) {
                ServerPlayer player = PolymerCommonUtils.getPlayer(context);
                return player == null || TreeChopPolymerClients.hasMod(player);
            }
        });

        PolymerBlockUtils.BREAKING_PROGRESS_UPDATE.register((player, pos, state, progress) -> {
            if (progress < 0 || !(state.getBlock() instanceof PolymerFabricChoppedLogBlock))
                return;

            if (TreeChopPolymerClients.hasMod(player))
                return;

            BlockState fx = ChopUtil.phaseChangeFxState(player.level(), pos, state);
            spawnMiningChips(player, pos, fx);
            // Vanilla hits every 4 ticks.
            if (progress % 4 == 0)
                playWoodHit(player, pos, fx);
        });

        TreeChopEvents.AFTER_CHOP.register(TreeChopPolymer::afterChopResumeVanillaMining);
        ServerTickEvents.END_SERVER_TICK.register(TreeChopPolymer::tickResumedMining);

        RegistrySyncUtils.setServerEntry(BuiltInRegistries.SOUND_EVENT, TreeChop.CHOP_WOOD_EVENT.get());
        RegistrySyncUtils.setServerEntry(BuiltInRegistries.LOOT_CONDITION_TYPE, CountBlockChopsLootItemCondition.ID);
        RegistrySyncUtils.setServerEntry(BuiltInRegistries.LOOT_CONDITION_TYPE, TreeFelledLootItemCondition.ID);

        FabricServer.setPacketFilter(TreeChopPolymerClients::shouldSendPacket);
        PolymerFx.setFeedback(TreeChopPolymer::playPhaseChangeFeedback);
        PolymerFx.setDisplayRefresh(TreeChopPolymer::refreshChoppedLogDisplay);

        TreeChop.LOGGER.info("Polymer compatibility enabled (empty leaves + BlockDisplay).");
    }

    private static void afterChopResumeVanillaMining(
            Level world, Player player, BlockPos pos, BlockState state,
            ChopDataImmutable chopData, boolean felled) {
        if (felled || !(player instanceof ServerPlayer serverPlayer) || !(world instanceof ServerLevel level))
            return;

        // Survival and polymer clients don't rely on our polymer solution
        if (TreeChopPolymerClients.hasMod(serverPlayer) || !serverPlayer.gameMode.isSurvival())
            return;

        BlockPos immutable = pos.immutable();
        level.getServer().execute(() -> tryResumeVanillaMining(serverPlayer, immutable));
    }

    private static void tryResumeVanillaMining(ServerPlayer player, BlockPos pos) {
        if (player.hasDisconnected() || TreeChopPolymerClients.hasMod(player) || !player.gameMode.isSurvival())
            return;

        BlockState state = player.level().getBlockState(pos);
        if (!(state.getBlock() instanceof PolymerFabricChoppedLogBlock))
            return;

        HitResult hit = player.pick(player.blockInteractionRange(), 0f, false);
        if (!(hit instanceof BlockHitResult blockHit) || !blockHit.getBlockPos().equals(pos))
            return;

        Direction face = blockHit.getDirection();

        UUID id = player.getUUID();
        int tick = player.level().getServer().getTickCount();
        ResumeMining existing = RESUMED_MINING.get(id);

        if (existing != null) {
            // Chopped again before any hold-swing since the last resume. Treat as released.
            if (!existing.seenSwingSinceStart()) {
                abortResumedMining(player, existing.pos());
                RESUMED_MINING.remove(id);
                return;
            }
            RESUMED_MINING.put(id, new ResumeMining(pos, existing.lastSwingTick(), false));
        } else if (!player.isSwinging()) {
            // First resume only while the client still looks like it is holding break.
            return;
        } else {
            RESUMED_MINING.put(id, new ResumeMining(pos, tick, false));
        }

        player.gameMode.handleBlockBreakAction(
                pos,
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                face,
                player.level().getMaxY() + 1,
                0);
    }

    private static void abortResumedMining(ServerPlayer player, BlockPos pos) {
        player.gameMode.handleBlockBreakAction(
                pos,
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                Direction.UP,
                player.level().getMaxY() + 1,
                0);
    }

    private static void tickResumedMining(MinecraftServer server) {
        if (RESUMED_MINING.isEmpty())
            return;

        int tick = server.getTickCount();
        Iterator<Map.Entry<UUID, ResumeMining>> it = RESUMED_MINING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ResumeMining> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.hasDisconnected()) {
                it.remove();
                continue;
            }

            ResumeMining resume = entry.getValue();
            if (player.isSwinging())
                entry.setValue(resume = resume.withSwing(tick));

            BlockState state = player.level().getBlockState(resume.pos());
            if (!(state.getBlock() instanceof PolymerFabricChoppedLogBlock)) {
                it.remove();
                continue;
            }

            HitResult hit = player.pick(player.blockInteractionRange(), 0f, false);
            boolean stillTargeting = hit instanceof BlockHitResult blockHit
                    && blockHit.getBlockPos().equals(resume.pos());
            if (!stillTargeting || tick - resume.lastSwingTick() > SWING_TIMEOUT_TICKS) {
                abortResumedMining(player, resume.pos());
                it.remove();
            }
        }
    }

    private static void refreshChoppedLogDisplay(Level level, BlockPos pos) {
        BlockBoundAttachment attachment = BlockBoundAttachment.get(level, pos);
        if (attachment != null && attachment.holder() instanceof ChoppedLogDisplay display)
            display.refresh();
    }

    /**
     * Silences client-predicted hits for the Polymer empty stand-in's SoundType.
     */
    private static void muteStandInSounds(@Nullable BlockState standIn) {
        if (standIn == null)
            return;

        SoundType soundType = standIn.getSoundType();
        SoundPatcher.convertIntoServerSound(soundType.getHitSound());
        SoundPatcher.convertIntoServerSound(soundType.getBreakSound());
    }

    public static BlockState emptyStandIn(boolean waterlogged) {
        if (waterlogged && emptyWaterlogged != null)
            return emptyWaterlogged;

        if (emptyLeaves != null)
            return emptyLeaves;

        return Blocks.OAK_LEAVES.defaultBlockState();
    }

    public static void playPhaseChangeFeedback(ServerPlayer player, BlockPos pos, BlockState fxState) {
        if (TreeChopPolymerClients.hasMod(player))
            return;

        if (!(player.level() instanceof ServerLevel level))
            return;

        Holder<SoundEvent> sound = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(TreeChop.CHOP_WOOD_EVENT.get());
        player.connection.send(new ClientboundSoundPacket(
                sound,
                SoundSource.BLOCKS,
                pos.getX() + 0.5,
                pos.getY() + 0.5,
                pos.getZ() + 0.5,
                0.3f,
                1f,
                level.getRandom().nextLong()));
    }

    private static void playWoodHit(ServerPlayer player, BlockPos pos, BlockState fxState) {
        if (!(player.level() instanceof ServerLevel level))
            return;

        SoundType soundType = fxState.getSoundType();
        Holder<SoundEvent> sound = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(soundType.getHitSound());
        player.connection.send(new ClientboundSoundPacket(
                sound,
                SoundSource.BLOCKS,
                pos.getX() + 0.5,
                pos.getY() + 0.5,
                pos.getZ() + 0.5,
                (soundType.getVolume() + 1f) / 16f,
                soundType.getPitch() * 0.5f,
                level.getRandom().nextLong()));
    }

    private static void spawnMiningChips(ServerPlayer miner, BlockPos pos, BlockState fxState) {
        sendParticlesNear(miner.level(), new BlockParticleOption(ParticleTypes.BLOCK, fxState),
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                0.4f, 0.4f, 0.4f, 0.15f, 4);
    }

    private static void sendParticlesNear(
            ServerLevel level, BlockParticleOption particle,
            double x, double y, double z,
            float xDist, float yDist, float zDist, float speed, int count) {
        ClientboundLevelParticlesPacket packet = new ClientboundLevelParticlesPacket(
                particle, false, false, x, y, z, xDist, yDist, zDist, speed, count);
        for (ServerPlayer player : level.players()) {
            if (TreeChopPolymerClients.hasMod(player) || player.distanceToSqr(x, y, z) > 64 * 64)
                continue;

            player.connection.send(packet);
        }
    }
}
