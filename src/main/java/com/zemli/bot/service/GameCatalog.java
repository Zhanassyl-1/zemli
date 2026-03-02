package com.zemli.bot.service;

import com.zemli.bot.model.Faction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GameCatalog {

    public record Cost(int wood, int stone, int food, int iron, int gold, int mana, int alcohol) {
        public Cost multiply(int n) {
            return new Cost(wood * n, stone * n, food * n, iron * n, gold * n, mana * n, alcohol * n);
        }
    }

    public record BuildingSpec(String key, String title, String emoji, int requiredCityLevel, Cost cost, int hours) {}
    public record UnitSpec(String key, String title, int tier, String requiredBuilding, Cost cost, int power) {}

    private final Map<String, BuildingSpec> buildings = new LinkedHashMap<>();
    private final Map<Faction, List<UnitSpec>> unitsByFaction = new LinkedHashMap<>();

    public GameCatalog() {
        buildings.put("TOWN_HALL", new BuildingSpec("TOWN_HALL", "Ратуша", "🏛️", 1, new Cost(200, 150, 0, 0, 50, 0, 0), 4));
        buildings.put("BARRACKS", new BuildingSpec("BARRACKS", "Казарма", "🛡️", 1, new Cost(80, 60, 0, 0, 0, 0, 0), 2));
        buildings.put("RANGE", new BuildingSpec("RANGE", "Стрельбище", "🏹", 1, new Cost(120, 100, 0, 0, 0, 0, 0), 3));
        buildings.put("STABLE", new BuildingSpec("STABLE", "Конюшня", "🐎", 1, new Cost(200, 150, 0, 30, 0, 0, 0), 5));
        buildings.put("FARM", new BuildingSpec("FARM", "Ферма", "🌾", 1, new Cost(60, 40, 0, 0, 0, 0, 0), 1));
        buildings.put("MINE", new BuildingSpec("MINE", "Шахта", "⛏️", 1, new Cost(70, 50, 0, 0, 0, 0, 0), 2));
        buildings.put("TAVERN", new BuildingSpec("TAVERN", "Таверна", "🍺", 1, new Cost(100, 80, 0, 0, 20, 0, 0), 2));
        buildings.put("MARKET", new BuildingSpec("MARKET", "Рынок", "🏦", 2, new Cost(150, 120, 0, 0, 50, 0, 0), 4));
        buildings.put("PORT", new BuildingSpec("PORT", "Порт", "⚓", 3, new Cost(200, 150, 0, 50, 0, 0, 0), 6));
        buildings.put("TEMPLE", new BuildingSpec("TEMPLE", "Храм", "🛕", 4, new Cost(250, 200, 0, 0, 0, 30, 0), 8));

        unitsByFaction.put(Faction.KNIGHTS, List.of(
                new UnitSpec("SWORDSMAN", "Мечник", 1, "BARRACKS", new Cost(0, 0, 20, 0, 10, 0, 0), 5),
                new UnitSpec("CROSSBOWMAN", "Арбалетчик", 2, "RANGE", new Cost(0, 0, 30, 10, 20, 0, 0), 12),
                new UnitSpec("PALADIN", "Паладин", 3, "STABLE", new Cost(0, 0, 50, 20, 40, 0, 0), 25)
        ));
        unitsByFaction.put(Faction.SAMURAI, List.of(
                new UnitSpec("ASHIGARU", "Асигару", 1, "BARRACKS", new Cost(0, 0, 15, 0, 8, 0, 0), 5),
                new UnitSpec("SAM_ARCHER", "Лучник", 2, "RANGE", new Cost(0, 0, 25, 8, 15, 0, 0), 12),
                new UnitSpec("SAMURAI", "Самурай", 3, "STABLE", new Cost(0, 0, 45, 15, 35, 0, 0), 25)
        ));
        unitsByFaction.put(Faction.VIKINGS, List.of(
                new UnitSpec("BERSERK", "Берсерк", 1, "BARRACKS", new Cost(0, 0, 20, 0, 12, 0, 0), 6),
                new UnitSpec("VIK_ARCHER", "Лучник", 2, "RANGE", new Cost(0, 0, 30, 10, 20, 0, 0), 13),
                new UnitSpec("JARL", "Ярл", 3, "STABLE", new Cost(0, 0, 55, 20, 45, 0, 0), 28)
        ));
        unitsByFaction.put(Faction.MONGOLS, List.of(
                new UnitSpec("MONGOL_ARCHER", "Лучник", 1, "BARRACKS", new Cost(0, 0, 18, 0, 10, 0, 0), 5),
                new UnitSpec("RIDER", "Всадник", 2, "RANGE", new Cost(0, 0, 35, 12, 25, 0, 0), 15),
                new UnitSpec("KHAN", "Хан", 3, "STABLE", new Cost(0, 0, 60, 25, 50, 0, 0), 30)
        ));
        unitsByFaction.put(Faction.DESERT_DWELLERS, List.of(
                new UnitSpec("SPEARMAN", "Копейщик", 1, "BARRACKS", new Cost(0, 0, 15, 0, 8, 0, 0), 5),
                new UnitSpec("DES_ARCHER", "Лучник", 2, "RANGE", new Cost(0, 0, 25, 8, 15, 0, 0), 11),
                new UnitSpec("MAMLUK", "Мамлюк", 3, "STABLE", new Cost(0, 0, 45, 18, 35, 0, 0), 24)
        ));
        unitsByFaction.put(Faction.AZTECS, List.of(
                new UnitSpec("WARRIOR", "Воин", 1, "BARRACKS", new Cost(0, 0, 18, 0, 10, 0, 0), 5),
                new UnitSpec("JAGUAR", "Ягуар", 2, "RANGE", new Cost(0, 0, 30, 0, 20, 10, 0), 14),
                new UnitSpec("PRIEST", "Жрец-воин", 3, "STABLE", new Cost(0, 0, 50, 0, 40, 20, 0), 27)
        ));
    }

    public Map<String, BuildingSpec> buildings() {
        return buildings;
    }

    public List<UnitSpec> unitsForFaction(Faction faction) {
        return unitsByFaction.getOrDefault(faction, List.of());
    }

    public UnitSpec unitByKey(Faction faction, String key) {
        return unitsForFaction(faction).stream().filter(u -> u.key().equals(key)).findFirst().orElse(null);
    }

    public Integer unitPowerByKey(String key) {
        for (List<UnitSpec> list : unitsByFaction.values()) {
            for (UnitSpec u : list) {
                if (u.key().equals(key)) {
                    return u.power();
                }
            }
        }
        return null;
    }

    public String shortFactionLabel(Faction faction) {
        return switch (faction) {
            case KNIGHTS -> "⚔️ Рыцари — броня, сильны против лучников";
            case SAMURAI -> "🥷 Самураи — мастера ближнего боя";
            case VIKINGS -> "🪓 Викинги — ярость и море";
            case MONGOLS -> "🏹 Монголы — быстрые всадники степи";
            case DESERT_DWELLERS -> "🐪 Пустынники — выносливые и дешёвые";
            case AZTECS -> "🗿 Ацтеки — магия и манна";
        };
    }

    public String fullFactionDescription(Faction faction) {
        return switch (faction) {
            case KNIGHTS -> "⚔️ Рыцари\nЮниты: Мечник → Арбалетчик → Паладин\nБонус: броня +20% против лучников";
            case SAMURAI -> "🥷 Самураи\nЮниты: Асигару → Лучник → Самурай\nБонус: ближний бой +15%";
            case VIKINGS -> "🪓 Викинги\nЮниты: Берсерк → Лучник → Ярл\nБонус: море +25%, порт даёт двойных юнитов";
            case MONGOLS -> "🏹 Монголы\nЮниты: Лучник → Всадник → Хан\nБонус в степи: шанс победы ×1.1, поражения ×0.9";
            case DESERT_DWELLERS -> "🐪 Пустынники\nЮниты: Копейщик → Лучник → Мамлюк\nБонус в жаре/пустыне +15%";
            case AZTECS -> "🗿 Ацтеки\nЮниты: Воин → Ягуар → Жрец-воин\nБонус: манна +20%, джунгли +15%";
        };
    }

    public String itemDisplay(String key) {
        return switch (key) {
            case "BLUEPRINT_PISTOL" -> "📜 Чертёж пистолета";
            case "BLUEPRINT_CANNON" -> "📜 Чертёж пушки";
            case "BLUEPRINT_CROSSBOW" -> "📜 Чертёж усиленного арбалета";
            case "CRAFTED_PISTOL" -> "🔫 Пистолет";
            case "CRAFTED_CANNON" -> "💣 Пушка";
            case "CRAFTED_CROSSBOW" -> "🏹 Усиленный арбалет";
            case "LEATHER_ARMOR" -> "🟫 Кожаная броня";
            case "CHAINMAIL_ARMOR" -> "⚪ Кольчуга";
            case "IRON_ARMOR" -> "⚫ Железная броня";
            case "GOLD_ARMOR" -> "🟡 Золотая броня";
            case "DIAMOND_ARMOR" -> "💎 Алмазная броня";
            case "NETHERITE_ARMOR" -> "🌑 Незеритовая броня";
            default -> key;
        };
    }
}
