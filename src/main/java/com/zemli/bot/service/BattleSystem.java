package com.zemli.bot.service;

import com.zemli.bot.model.Faction;
import com.zemli.bot.model.KeyValueAmount;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BattleSystem {

    public enum UnitRole {
        RANGED,
        LIGHT_MELEE,
        MELEE,
        CAVALRY,
        SPECIAL
    }

    public record BattleInput(
            Faction attackerFaction,
            Faction defenderFaction,
            List<KeyValueAmount> attackerArmy,
            List<KeyValueAmount> defenderArmy,
            Tactics attackerTactic,
            Tactics defenderTactic,
            Hero attackerHero,
            Hero defenderHero
    ) {}

    public record SideResult(
            Map<String, Integer> lostUnits,
            int remainingPower
    ) {}

    public record BattleResult(
            boolean attackerWon,
            List<String> roundLog,
            SideResult attackerResult,
            SideResult defenderResult
    ) {}

    private static final class UnitState {
        final String type;
        final UnitRole role;
        final int power;
        int qty;

        UnitState(String type, UnitRole role, int power, int qty) {
            this.type = type;
            this.role = role;
            this.power = power;
            this.qty = qty;
        }

        int totalPower() {
            return Math.max(0, qty) * power;
        }
    }

    private static final class SideState {
        final Faction faction;
        final Tactics tactic;
        final Hero hero;
        final Map<String, UnitState> units = new HashMap<>();
        final Map<String, Integer> initialQty = new HashMap<>();
        boolean lostRound1 = false;

        SideState(Faction faction, Tactics tactic, Hero hero, List<KeyValueAmount> army) {
            this.faction = faction;
            this.tactic = tactic == null ? Tactics.NONE : tactic;
            this.hero = hero;
            for (KeyValueAmount kv : army) {
                UnitRole role = detectRole(kv.type());
                int p = detectPower(kv.type());
                UnitState u = new UnitState(kv.type(), role, p, Math.max(0, kv.quantity()));
                units.put(kv.type(), u);
                initialQty.put(kv.type(), Math.max(0, kv.quantity()));
            }
        }

        int powerByRoles(UnitRole... roles) {
            int sum = 0;
            for (UnitState u : units.values()) {
                for (UnitRole role : roles) {
                    if (u.role == role) {
                        sum += u.totalPower();
                    }
                }
            }
            return sum;
        }

        int cavalryPower() {
            int sum = powerByRoles(UnitRole.CAVALRY);
            UnitState horseArcher = units.get("MG_HORSE_ARCHER");
            if (horseArcher != null) {
                sum += horseArcher.totalPower();
            }
            return sum;
        }

        int totalPower() {
            int sum = 0;
            for (UnitState u : units.values()) {
                sum += u.totalPower();
            }
            return sum;
        }

        void losePercentByRole(UnitRole role, int percent) {
            for (UnitState u : units.values()) {
                if (u.role != role) {
                    continue;
                }
                int loss = (int) Math.ceil(u.qty * (percent / 100.0));
                u.qty = Math.max(0, u.qty - loss);
            }
        }

        void losePercentByRoles(int percent, UnitRole... roles) {
            for (UnitRole role : roles) {
                losePercentByRole(role, percent);
            }
        }

        void losePercentAll(int percent) {
            for (UnitState u : units.values()) {
                int loss = (int) Math.ceil(u.qty * (percent / 100.0));
                u.qty = Math.max(0, u.qty - loss);
            }
        }

        Map<String, Integer> losses() {
            Map<String, Integer> out = new HashMap<>();
            for (UnitState u : units.values()) {
                int init = initialQty.getOrDefault(u.type, 0);
                int lost = Math.max(0, init - u.qty);
                if (lost > 0) {
                    out.put(u.type, lost);
                }
            }
            return out;
        }
    }

    public BattleResult run(BattleInput in) {
        SideState a = new SideState(in.attackerFaction(), in.attackerTactic(), in.attackerHero(), in.attackerArmy());
        SideState d = new SideState(in.defenderFaction(), in.defenderTactic(), in.defenderHero(), in.defenderArmy());
        List<String> log = new ArrayList<>();
        log.add("⚔️ БОЙ: " + factionLabel(a.faction) + " vs " + factionLabel(d.faction));

        // Round 1: ranged
        int aRanged = a.powerByRoles(UnitRole.RANGED);
        int dRanged = d.powerByRoles(UnitRole.RANGED);
        double aRangedMul = baseRoundMultiplier(1, a, d);
        double dRangedMul = baseRoundMultiplier(1, d, a);
        if (a.tactic == Tactics.AMBUSH) {
            dRanged = 0;
        }
        if (d.tactic == Tactics.AMBUSH) {
            aRanged = 0;
        }
        int aR1 = (int) Math.floor(aRanged * aRangedMul);
        int dR1 = (int) Math.floor(dRanged * dRangedMul);
        log.add("РАУНД 1: ОБСТРЕЛ");
        log.add("🏹 " + factionShort(a.faction) + " (" + aR1 + ") vs 🏹 " + factionShort(d.faction) + " (" + dR1 + ")");
        if (aR1 == dR1) {
            log.add("Ничья в обстреле.");
        } else if (aR1 > dR1) {
            d.losePercentByRole(UnitRole.RANGED, 30);
            d.lostRound1 = true;
            log.add(factionShort(a.faction) + " побеждают! " + counterpickText(a.faction, d.faction, 1));
        } else {
            a.losePercentByRole(UnitRole.RANGED, 30);
            a.lostRound1 = true;
            log.add(factionShort(d.faction) + " побеждают! " + counterpickText(d.faction, a.faction, 1));
        }
        if (aRanged == 0) a.losePercentAll(5);
        if (dRanged == 0) d.losePercentAll(5);
        applyRoundDamageEffects(1, a, d);

        // Round 2: light melee
        int aLight = a.powerByRoles(UnitRole.LIGHT_MELEE);
        int dLight = d.powerByRoles(UnitRole.LIGHT_MELEE);
        double aR2Mul = baseRoundMultiplier(2, a, d) * (a.lostRound1 ? 0.85 : 1.0);
        double dR2Mul = baseRoundMultiplier(2, d, a) * (d.lostRound1 ? 0.85 : 1.0);
        int aR2 = (int) Math.floor(aLight * aR2Mul);
        int dR2 = (int) Math.floor(dLight * dR2Mul);
        log.add("РАУНД 2: СБЛИЖЕНИЕ");
        log.add("🗡️ " + factionShort(a.faction) + " (" + aR2 + ") vs 🗡️ " + factionShort(d.faction) + " (" + dR2 + ")");
        if (aR2 > dR2) {
            int dmgPct = Math.min(35, Math.max(5, (int) Math.floor((aR2 * 25.0) / Math.max(1, dR2))));
            d.losePercentAll(dmgPct);
            log.add(factionShort(a.faction) + " побеждают! " + counterpickText(a.faction, d.faction, 2));
        } else if (dR2 > aR2) {
            int dmgPct = Math.min(35, Math.max(5, (int) Math.floor((dR2 * 25.0) / Math.max(1, aR2))));
            a.losePercentAll(dmgPct);
            log.add(factionShort(d.faction) + " побеждают! " + counterpickText(d.faction, a.faction, 2));
        } else {
            log.add("Ничья в сближении.");
        }
        applyRoundDamageEffects(2, a, d);

        // Round 3: melee + cavalry support
        int aMelee = a.powerByRoles(UnitRole.LIGHT_MELEE, UnitRole.MELEE, UnitRole.SPECIAL);
        int dMelee = d.powerByRoles(UnitRole.LIGHT_MELEE, UnitRole.MELEE, UnitRole.SPECIAL);
        double aR3Mul = baseRoundMultiplier(3, a, d) * (a.cavalryPower() > 0 ? 1.2 : 1.0);
        double dR3Mul = baseRoundMultiplier(3, d, a) * (d.cavalryPower() > 0 ? 1.2 : 1.0);
        int aR3 = (int) Math.floor(aMelee * aR3Mul);
        int dR3 = (int) Math.floor(dMelee * dR3Mul);
        log.add("РАУНД 3: РУКОПАШНАЯ");
        log.add("⚔️ " + factionShort(a.faction) + " (" + aR3 + ") vs ⚔️ " + factionShort(d.faction) + " (" + dR3 + ")");
        if (aR3 > dR3) {
            d.losePercentByRoles(40, UnitRole.LIGHT_MELEE, UnitRole.MELEE, UnitRole.SPECIAL);
            log.add(factionShort(a.faction) + " побеждают! " + counterpickText(a.faction, d.faction, 3));
        } else if (dR3 > aR3) {
            a.losePercentByRoles(40, UnitRole.LIGHT_MELEE, UnitRole.MELEE, UnitRole.SPECIAL);
            log.add(factionShort(d.faction) + " побеждают! " + counterpickText(d.faction, a.faction, 3));
        } else {
            log.add("Ничья в рукопашной.");
        }
        applyRoundDamageEffects(3, a, d);

        // Round 4: all
        int aAll = a.totalPower();
        int dAll = d.totalPower();
        double aR4Mul = baseRoundMultiplier(4, a, d);
        double dR4Mul = baseRoundMultiplier(4, d, a);
        if (a.cavalryPower() > d.cavalryPower()) {
            aR4Mul *= 1.3;
        } else if (d.cavalryPower() > a.cavalryPower()) {
            dR4Mul *= 1.3;
        }
        int aR4 = (int) Math.floor(aAll * aR4Mul);
        int dR4 = (int) Math.floor(dAll * dR4Mul);
        log.add("РАУНД 4: ДОБИВАНИЕ");
        log.add("ВСЕ ВОЙСКА: " + factionShort(a.faction) + " (" + aR4 + ") vs " + factionShort(d.faction) + " (" + dR4 + ")");
        boolean attackerWon = aR4 >= dR4;
        log.add((attackerWon ? "🏆 ПОБЕДА АТАКУЮЩЕГО" : "🏆 ПОБЕДА ЗАЩИТНИКА"));

        if (attackerWon) {
            d.losePercentAll(20);
        } else {
            a.losePercentAll(20);
        }

        return new BattleResult(
                attackerWon,
                log,
                new SideResult(a.losses(), a.totalPower()),
                new SideResult(d.losses(), d.totalPower())
        );
    }

    private void applyRoundDamageEffects(int round, SideState a, SideState d) {
        if (a.tactic == Tactics.POISON) {
            d.losePercentAll(10);
        }
        if (d.tactic == Tactics.POISON) {
            a.losePercentAll(10);
        }
        if (round <= 3) {
            if (a.tactic == Tactics.ARSON) {
                d.losePercentByRole(UnitRole.RANGED, 10);
            }
            if (d.tactic == Tactics.ARSON) {
                a.losePercentByRole(UnitRole.RANGED, 10);
            }
        }
    }

    private double baseRoundMultiplier(int round, SideState side, SideState enemy) {
        double m = 1.0;

        if (side.tactic == Tactics.PHALANX) {
            m *= 0.70;
        }
        if (side.tactic == Tactics.SWIFT) {
            m *= 1.10;
        }
        if (side.tactic == Tactics.FEIGNED_RETREAT && round == 2) {
            m *= 1.50;
        }
        if (enemy.tactic == Tactics.NIGHT_ATTACK) {
            m *= 0.70;
        }

        if (side.hero != null && side.hero.faction() == side.faction) {
            m *= 1.0 + side.hero.attackBonus();
            if (round == 1 && "HERO_GENGHIS".equals(side.hero.key())) {
                m *= 2.0;
            }
            if (round == 3 && "HERO_RAGNAR".equals(side.hero.key())) {
                m *= 1.5;
            }
        }
        if (enemy.hero != null && enemy.hero.faction() == enemy.faction) {
            m *= 1.0 - Math.min(0.60, enemy.hero.defenseBonus());
        }

        // Counterpicks by round
        if (round == 1 && side.faction == Faction.MONGOLS && enemy.faction == Faction.KNIGHTS) {
            m *= 1.5;
        }
        if (round == 2 && side.faction == Faction.VIKINGS && enemy.faction == Faction.SAMURAI) {
            m *= 1.35;
        }
        if (round == 1 && side.faction == Faction.SAMURAI && enemy.faction == Faction.MONGOLS) {
            m *= 1.25;
        }
        if (round == 3 && side.faction == Faction.KNIGHTS && enemy.faction == Faction.VIKINGS) {
            m *= 1.4;
        }
        if (side.faction == Faction.DESERT_DWELLERS && enemy.faction == Faction.AZTECS) {
            m *= 1.1;
        }
        if (side.faction == Faction.AZTECS && enemy.faction == Faction.DESERT_DWELLERS) {
            m *= 1.5;
        }

        return Math.max(0.10, m);
    }

    public static UnitRole detectRole(String unitType) {
        if (unitType == null) {
            return UnitRole.MELEE;
        }
        return switch (unitType) {
            case "KN_CROSSBOW", "SM_ARCHER", "VK_AXE_THROWER", "MG_HERDER", "MG_HORSE_ARCHER", "DS_JANISSARY", "AZ_WAR_ARCHER" -> UnitRole.RANGED;
            case "KN_MILITIA", "SM_ASHIGARU", "VK_THRALL", "AZ_MASEUALLI" -> UnitRole.LIGHT_MELEE;
            case "KN_HEAVY_KNIGHT", "DS_DRIVER", "DS_MAMLUK" -> UnitRole.CAVALRY;
            case "SM_NINJA", "DS_ASSASSIN", "AZ_EAGLE", "AZ_PRIEST", "KN_PALADIN", "SM_KENDO", "VK_JARL", "MG_CHINGIZID", "DS_SULTAN" -> UnitRole.SPECIAL;
            default -> UnitRole.MELEE;
        };
    }

    private static int detectPower(String unitType) {
        return switch (unitType) {
            case "KN_MILITIA" -> 10;
            case "KN_CROSSBOW" -> 20;
            case "KN_SWORDSMAN" -> 35;
            case "KN_HEAVY_KNIGHT" -> 60;
            case "KN_PALADIN" -> 100;
            case "SM_ASHIGARU" -> 12;
            case "SM_ARCHER" -> 18;
            case "SM_KATANA" -> 40;
            case "SM_NINJA" -> 45;
            case "SM_KENDO" -> 85;
            case "VK_THRALL" -> 10;
            case "VK_AXE_THROWER" -> 25;
            case "VK_BERSERK" -> 50;
            case "VK_HIRDMANN" -> 45;
            case "VK_JARL" -> 90;
            case "MG_HERDER" -> 12;
            case "MG_HORSE_ARCHER" -> 28;
            case "MG_BAGATUR" -> 40;
            case "MG_NOYON" -> 55;
            case "MG_CHINGIZID" -> 95;
            case "DS_DRIVER" -> 15;
            case "DS_JANISSARY" -> 25;
            case "DS_MAMLUK" -> 45;
            case "DS_ASSASSIN" -> 50;
            case "DS_SULTAN" -> 90;
            case "AZ_MASEUALLI" -> 12;
            case "AZ_WAR_ARCHER" -> 20;
            case "AZ_JAGUAR" -> 40;
            case "AZ_EAGLE" -> 45;
            case "AZ_PRIEST" -> 70;
            default -> 20;
        };
    }

    private String counterpickText(Faction attacker, Faction defender, int round) {
        if (round == 1 && attacker == Faction.MONGOLS && defender == Faction.KNIGHTS) {
            return "⚔️ Монголы расстреливают рыцарей издали! +50% урона";
        }
        if (round == 3 && attacker == Faction.KNIGHTS && defender == Faction.VIKINGS) {
            return "⚔️ Рыцари выдерживают натиск викингов! +40% защиты";
        }
        if (round == 2 && attacker == Faction.VIKINGS && defender == Faction.SAMURAI) {
            return "⚔️ Викинги ломают строй самураев! +35% атаки";
        }
        if (round == 1 && attacker == Faction.SAMURAI && defender == Faction.MONGOLS) {
            return "⚔️ Самураи уклоняются от стрел! +25% уклонения";
        }
        if (attacker == Faction.DESERT_DWELLERS && defender == Faction.AZTECS) {
            return "⚔️ Пустынники поднимают мораль! +30%";
        }
        if (attacker == Faction.AZTECS && defender == Faction.DESERT_DWELLERS) {
            return "⚔️ Жертвы ацтеков усиливают войско! +50%";
        }
        return "";
    }

    private String factionLabel(Faction faction) {
        return switch (faction) {
            case KNIGHTS -> "🛡️ Рыцари";
            case SAMURAI -> "⚔️ Самураи";
            case VIKINGS -> "🪓 Викинги";
            case MONGOLS -> "🏹 Монголы";
            case DESERT_DWELLERS -> "🏜️ Пустынники";
            case AZTECS -> "🌞 Ацтеки";
        };
    }

    private String factionShort(Faction faction) {
        return switch (faction) {
            case KNIGHTS -> "Рыцари";
            case SAMURAI -> "Самураи";
            case VIKINGS -> "Викинги";
            case MONGOLS -> "Монголы";
            case DESERT_DWELLERS -> "Пустынники";
            case AZTECS -> "Ацтеки";
        };
    }
}
