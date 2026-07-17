package com.crystaelix.simurail.api.consist;

import java.util.List;
import java.util.UUID;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import net.minecraft.network.chat.Component;

final class SimpleConsist implements SimurailConsist {
    private final UUID id;
    private final List<PhysicsBogeyBlockEntity> bogeys;
    private final Component name;

    SimpleConsist(UUID id, List<PhysicsBogeyBlockEntity> bogeys, Component name) {
        this.id = id; this.bogeys = bogeys; this.name = name;
    }

    @Override public UUID id() { return id; }
    @Override public List<PhysicsBogeyBlockEntity> bogeys() { return bogeys; }
    @Override public Component name() { return name; }
}