package com.crystaelix.simurail.extension;

import org.jetbrains.annotations.Nullable;

import com.crystaelix.simurail.api.consist.SimurailConsist;

import net.minecraft.network.chat.Component;

/**
 * Exposed by {@code StationBlockEntityMixin} on Create's own {@code StationBlockEntity} (package
 * {@code com.simibubi.create.content.trains.station}), so the rest of Simurail (display sources, cargo ports)
 * can query whether a physics bogey/consist is currently docked at a <em>real</em> Create station without
 * needing a custom station block of its own. See {@code StationBlockEntityMixin} for how this is populated.
 */
public interface StationBlockEntityExtension {

	boolean simurail$hasDockedConsist();

	@Nullable
	Component simurail$getDockedConsistName();

	@Nullable
	SimurailConsist simurail$getDockedConsist();

	int simurail$getDockedLength();

	int simurail$getDwellTicks();
}
