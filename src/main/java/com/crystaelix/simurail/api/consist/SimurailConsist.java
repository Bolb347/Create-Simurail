package com.crystaelix.simurail.api.consist;

import java.util.List;
import java.util.UUID;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import net.minecraft.network.chat.Component;

public interface SimurailConsist {
    UUID id();
    List<PhysicsBogeyBlockEntity> bogeys();
    Component name();

    default int length() { return bogeys().size(); }
    default boolean contains(PhysicsBogeyBlockEntity bogey) { return bogeys().contains(bogey); }
}