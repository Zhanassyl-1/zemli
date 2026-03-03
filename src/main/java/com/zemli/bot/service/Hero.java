package com.zemli.bot.service;

import com.zemli.bot.model.Faction;

public record Hero(
        String key,
        String name,
        Faction faction,
        int rarityRank,
        double attackBonus,
        double defenseBonus,
        int personalKills,
        String effectText
) {
    public static Hero byKey(String key) {
        return switch (key) {
            case "HERO_LANCELOT" -> new Hero(key, "Сэр Ланселот", Faction.KNIGHTS, 3, 0.40, 0.00, 50, "+40% атаки рыцарям");
            case "HERO_ARTHUR" -> new Hero(key, "Король Артур", Faction.KNIGHTS, 4, 1.00, 0.20, 80, "Экскалибур +100% урона 1 раз");
            case "HERO_RICHARD" -> new Hero(key, "Ричард", Faction.KNIGHTS, 2, 0.00, 0.20, 20, "+20% защиты");

            case "HERO_MUSASHI" -> new Hero(key, "Миямото Мусаси", Faction.SAMURAI, 3, 0.30, 0.00, 100, "+30% крит");
            case "HERO_DZINGORO" -> new Hero(key, "Дзингоро", Faction.SAMURAI, 4, 0.15, 0.50, 40, "Отражение 50% урона");
            case "HERO_TOKUGAWA" -> new Hero(key, "Токугава", Faction.SAMURAI, 2, 0.15, 0.00, 20, "+15% тактика");

            case "HERO_RAGNAR" -> new Hero(key, "Рагнар Лодброк", Faction.VIKINGS, 3, 0.50, 0.00, 60, "Вампиризм +50% в 3 раунде");
            case "HERO_BJORN" -> new Hero(key, "Бьорн", Faction.VIKINGS, 4, 1.00, 0.30, 80, "Неуязвим 1 раунд");
            case "HERO_IVAR" -> new Hero(key, "Ивар", Faction.VIKINGS, 2, 0.25, 0.00, 25, "+25% силы раненым");

            case "HERO_GENGHIS" -> new Hero(key, "Чингисхан", Faction.MONGOLS, 3, 0.50, 0.00, 70, "Двойной выстрел 1 раунд");
            case "HERO_SUBEDEI" -> new Hero(key, "Субэдэй", Faction.MONGOLS, 4, 0.30, 0.20, 50, "Контртактика");
            case "HERO_JEBE" -> new Hero(key, "Джебе", Faction.MONGOLS, 2, 0.30, 0.00, 20, "+30% точности");

            case "HERO_SALADIN" -> new Hero(key, "Саладин", Faction.DESERT_DWELLERS, 3, 0.30, 0.15, 40, "+30% морали, лечение");
            case "HERO_ALAMUT" -> new Hero(key, "Аламут", Faction.DESERT_DWELLERS, 4, 0.50, 0.20, 70, "Шанс убить героя до боя");
            case "HERO_HARUN" -> new Hero(key, "Харун", Faction.DESERT_DWELLERS, 2, 0.20, 0.00, 20, "+20% экономики");

            case "HERO_MONTEZUMA" -> new Hero(key, "Монтесума", Faction.AZTECS, 3, 0.50, 0.00, 70, "Жертвы +100% силы");
            case "HERO_CUAUHTEMOC" -> new Hero(key, "Куаутемок", Faction.AZTECS, 4, 0.40, 0.30, 60, "Воскрешение 30% 1 раз");
            case "HERO_TLALOC" -> new Hero(key, "Тлалок", Faction.AZTECS, 2, 0.25, 0.00, 20, "+25% стихий");
            default -> null;
        };
    }
}

