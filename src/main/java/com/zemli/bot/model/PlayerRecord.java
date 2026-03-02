package com.zemli.bot.model;

public record PlayerRecord(
        long id,
        long telegramId,
        String villageName,
        Faction faction,
        int cityLevel,
        int buildersCount,
        boolean hasCannon,
        boolean hasArmor,
        boolean hasCrossbow,
        String equippedArmor,
        long createdAt
) {
}
