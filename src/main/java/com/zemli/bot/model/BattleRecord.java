package com.zemli.bot.model;

public record BattleRecord(
        long id,
        long attackerId,
        long defenderId,
        int attackerHp,
        int defenderHp,
        int attackerMaxHp,
        int defenderMaxHp,
        int currentRound,
        int maxRounds,
        String attackerAction,
        String defenderAction,
        String status,
        long createdAt,
        Long roundStartedAt,
        String history
) {
}
