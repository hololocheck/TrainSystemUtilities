package com.trainsystemutilities.schedule;

/**
 * Centralized constants for Create's schedule instruction / condition string IDs.
 *
 * <p>These were previously hardcoded as bare {@code "create:xxx"} string literals across
 * several files, creating a fragile coupling to Create. Centralizing them here keeps the
 * coupling in one place. The string values are unchanged (behavior-identical).
 */
public final class CreateScheduleIds {

    private CreateScheduleIds() {}

    // Instructions
    public static final String DESTINATION = "create:destination";
    public static final String PACKAGE_DELIVERY = "create:package_delivery";
    public static final String PACKAGE_RETRIEVAL = "create:package_retrieval";
    public static final String RENAME = "create:rename";
    public static final String THROTTLE = "create:throttle";

    // Conditions
    public static final String DELAY = "create:delay";
    public static final String TIME_OF_DAY = "create:time_of_day";
    public static final String ITEM_THRESHOLD = "create:item_threshold";
    public static final String FLUID_THRESHOLD = "create:fluid_threshold";
    public static final String CARGO_THRESHOLD = "create:cargo_threshold";
    public static final String REDSTONE_LINK = "create:redstone_link";
    public static final String PLAYER_COUNT = "create:player_count";
    public static final String PLAYER_PASSENGER = "create:player_passenger";
    public static final String IDLE = "create:idle";
    public static final String POWERED = "create:powered";
    public static final String UNLOADED = "create:unloaded";
    public static final String STATION_POWERED = "create:station_powered";
    public static final String STATION_UNLOADED = "create:station_unloaded";
}
