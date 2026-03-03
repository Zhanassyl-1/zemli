package com.zemli.bot.service;

public enum Tactics {
    NONE("NONE", "Без тактики", new GameCatalog.Cost(0, 0, 0, 0, 0, 0, 0)),
    AMBUSH("AMBUSH", "🏹 Засада", new GameCatalog.Cost(0, 0, 0, 0, 0, 20, 0)),
    PHALANX("PHALANX", "🛡️ Фаланга", new GameCatalog.Cost(0, 50, 0, 0, 0, 0, 0)),
    SWIFT("SWIFT", "⚡ Стремительная", new GameCatalog.Cost(0, 0, 0, 0, 0, 0, 30)),
    ARSON("ARSON", "🔥 Поджог", new GameCatalog.Cost(0, 0, 0, 0, 0, 30, 20)),
    POISON("POISON", "💀 Отравление", new GameCatalog.Cost(0, 0, 0, 0, 0, 40, 0)),
    FEIGNED_RETREAT("FEIGNED_RETREAT", "🏃 Ложное отступление", new GameCatalog.Cost(0, 0, 0, 0, 0, 0, 50)),
    NIGHT_ATTACK("NIGHT_ATTACK", "🌙 Ночная атака", new GameCatalog.Cost(0, 0, 0, 0, 0, 25, 0));

    private final String key;
    private final String title;
    private final GameCatalog.Cost cost;

    Tactics(String key, String title, GameCatalog.Cost cost) {
        this.key = key;
        this.title = title;
        this.cost = cost;
    }

    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    public GameCatalog.Cost cost() {
        return cost;
    }

    public static Tactics fromKey(String key) {
        if (key == null || key.isBlank()) {
            return NONE;
        }
        for (Tactics t : values()) {
            if (t.key.equalsIgnoreCase(key)) {
                return t;
            }
        }
        return NONE;
    }
}

