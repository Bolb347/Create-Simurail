package com.crystaelix.simurail.api.controller;

public interface ICustomStationPresence {
    boolean simurail$hasCustomTrain();

    void simurail$setCustomTrain(boolean present);
}