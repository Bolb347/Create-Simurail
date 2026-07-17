package com.crystaelix.simurail.api.consist;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import com.crystaelix.simurail.content.automatic_coupler.AutomaticCouplerBlockEntity;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class ConsistLocator {
    private ConsistLocator() {}

    public static SimurailConsist locate(Level level, PhysicsBogeyBlockEntity start) {
        Set<BlockPos> visited = new HashSet<>();
        visited.add(start.getBlockPos());
        List<PhysicsBogeyBlockEntity> backward = walk(level, start.getConnectionBack(), start.getBlockPos(), visited);
        List<PhysicsBogeyBlockEntity> forward = walk(level, start.getConnectionFront(), start.getBlockPos(), visited);

        List<PhysicsBogeyBlockEntity> ordered = new ArrayList<>(backward.size() + 1 + forward.size());
        for (int i = backward.size() - 1; i >= 0; i--) ordered.add(backward.get(i));
        ordered.add(start);
        ordered.addAll(forward);

        UUID id = UUID.nameUUIDFromBytes(("simurail_consist:" + ordered.get(0).getBlockPos()).getBytes());
        Component name = resolveName(ordered);
        return new SimpleConsist(id, ordered, name);
    }

    private static List<PhysicsBogeyBlockEntity> walk(Level level, @Nullable BlockPos firstTarget, BlockPos fromPos, Set<BlockPos> visited) {
        List<PhysicsBogeyBlockEntity> chain = new ArrayList<>();
        BlockPos target = firstTarget;
        BlockPos entryMarker = fromPos;
        while (target != null) {
            Hop hop = resolveHop(level, target, entryMarker);
            if (hop == null) break;
            if (!visited.add(hop.bogey().getBlockPos())) break;
            chain.add(hop.bogey());
            entryMarker = hop.bogey().getBlockPos();
            target = otherSide(hop.bogey(), hop.entryMarker());
        }
        return chain;
    }

    @Nullable
    private static Hop resolveHop(Level level, BlockPos target, BlockPos fromPos) {
        BlockEntity be = level.getBlockEntity(target);
        if (be instanceof PhysicsBogeyBlockEntity bogey) return new Hop(bogey, fromPos);
        if (be instanceof AutomaticCouplerBlockEntity nearCoupler) {
            BlockPos partnerPos = nearCoupler.getPartner();
            if (partnerPos == null) return null;
            if (!(level.getBlockEntity(partnerPos) instanceof AutomaticCouplerBlockEntity farCoupler)) return null;
            BlockPos nextPos = farCoupler.getConnected();
            if (nextPos == null) return null;
            if (level.getBlockEntity(nextPos) instanceof PhysicsBogeyBlockEntity nextBogey) return new Hop(nextBogey, farCoupler.getBlockPos());
        }
        return null;
    }

    @Nullable
    private static BlockPos otherSide(PhysicsBogeyBlockEntity bogey, BlockPos entryMarker) {
        BlockPos front = bogey.getConnectionFront();
        BlockPos back = bogey.getConnectionBack();
        if (entryMarker.equals(front)) return back;
        if (entryMarker.equals(back)) return front;
        return null;
    }

    private static Component resolveName(List<PhysicsBogeyBlockEntity> bogeys) {
        for (PhysicsBogeyBlockEntity bogey : bogeys) if (bogey.getCustomName() != null) return bogey.getCustomName();
        return bogeys.get(0).getName();
    }

    private record Hop(PhysicsBogeyBlockEntity bogey, BlockPos entryMarker) {}
}