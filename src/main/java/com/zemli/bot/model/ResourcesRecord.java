package com.zemli.bot.model;

public record ResourcesRecord(
        int wood,
        int stone,
        int food,
        int iron,
        int gold,
        int mana,
        int alcohol,
        int population,
        int maxPopulation,
        int storageLimit
) {
}
