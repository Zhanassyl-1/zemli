package com.zemli.bot.model;

import java.util.Arrays;
import java.util.List;

public enum Faction {
    KNIGHTS(
            "Рыцари",
            "Мечник -> Арбалетчик -> Паладин",
            "Броня +20% против лучников",
            12
    ),
    SAMURAI(
            "Самураи",
            "Асигару -> Лучник -> Самурай",
            "Ближний бой +15%",
            10
    ),
    VIKINGS(
            "Викинги",
            "Берсерк -> Лучник -> Ярл",
            "Море +25%, порт даёт двойных юнитов",
            8
    ),
    MONGOLS(
            "Монголы",
            "Лучник -> Всадник -> Хан",
            "В степи: победа x1.1, поражение x0.9",
            4
    ),
    DESERT_DWELLERS(
            "Пустынники",
            "Копейщик -> Лучник -> Мамлюк",
            "В жаре/пустыне +15%",
            8
    ),
    AZTECS(
            "Ацтеки",
            "Воин -> Ягуар -> Жрец-воин",
            "Манна +20%, джунгли +15%",
            8
    );

    private final String title;
    private final String units;
    private final String bonus;
    private final int battleCooldownHours;

    Faction(String title, String units, String bonus, int battleCooldownHours) {
        this.title = title;
        this.units = units;
        this.bonus = bonus;
        this.battleCooldownHours = battleCooldownHours;
    }

    public String getTitle() {
        return title;
    }

    public String getUnits() {
        return units;
    }

    public String getBonus() {
        return bonus;
    }

    public int getBattleCooldownHours() {
        return battleCooldownHours;
    }

    public String callbackData() {
        return "faction:" + name();
    }

    public static Faction fromCallback(String callbackData) {
        String raw = callbackData.replace("faction:", "").trim();
        return Faction.valueOf(raw);
    }

    public static List<Faction> all() {
        return Arrays.asList(values());
    }
}
