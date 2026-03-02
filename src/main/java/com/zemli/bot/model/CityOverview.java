package com.zemli.bot.model;

import java.util.List;

public record CityOverview(
        PlayerRecord player,
        ResourcesRecord resources,
        List<KeyValueAmount> army,
        int totalArmyPower,
        List<KeyValueAmount> buildings,
        List<KeyValueAmount> inventory,
        long nextPassiveTickEpoch,
        int rankingPosition
) {
}
