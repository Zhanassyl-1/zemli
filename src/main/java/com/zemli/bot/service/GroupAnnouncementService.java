package com.zemli.bot.service;

import com.zemli.bot.bot.ZemliTelegramBot;
import com.zemli.bot.dao.GameDao;
import com.zemli.bot.model.DailyEventType;
import com.zemli.bot.model.PlayerRecord;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class GroupAnnouncementService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final ZemliTelegramBot bot;
    private final GameDao gameDao;
    private final GameCatalog gameCatalog;

    public GroupAnnouncementService(ZemliTelegramBot bot, GameDao gameDao, GameCatalog gameCatalog) {
        this.bot = bot;
        this.gameDao = gameDao;
        this.gameCatalog = gameCatalog;
    }

    public void announceBuildingCompleted(long playerId, String villageName, String buildingTitle) {
        bot.sendGroupBuildingAsync(playerId, "🏗️ " + villageName + " построил " + buildingTitle + "!");
    }

    public void announceCityLevelUp(String village, int level) {
        bot.sendGroupMessageAsync("🏙️ " + village + " выросла до " + cityLevelTitle(level) + "!");
        if (gameDao.countPlayersAtLeastCityLevel(level) == 1) {
            bot.sendGroupMessageAsync(
                    "🎖️ ВПЕРВЫЕ НА СЕРВЕРЕ!\n" +
                            village + " первым достиг уровня " + cityLevelTitle(level) + "!\n" +
                            "Войдёт в историю как великий строитель."
            );
        }
    }

    public void announceRareFind(String villageName, String itemTitle) {
        bot.sendGroupMessageAsync(
                "📜 РЕДКАЯ НАХОДКА!\n" +
                        "🎉 " + villageName + " обнаружил: " + itemTitle + "\n" +
                        "Удача улыбнулась этому правителю..."
        );
    }

    public void announceAuctionStarted(String village, String item, int price) {
        bot.sendGroupMessageAsync(
                "🔨 АУКЦИОН\n" +
                        village + " выставил: " + item + "\n" +
                        "Начальная ставка: " + price + " 💰\n" +
                        "⏰ Заканчивается через 12 часов\n" +
                        "Сделай ставку в боте!"
        );
    }

    public void announceBigDeal(String sellerVillage, String buyerVillage, String item, int amount) {
        if (amount <= 500) {
            return;
        }
        bot.sendGroupMessageAsync(
                "💰 КРУПНАЯ СДЕЛКА\n" +
                        sellerVillage + " продал " + item + " игроку " + buyerVillage + "\n" +
                        "Сумма: " + amount + " 💰"
        );
    }

    public void announceAlliance(String a, String b) {
        bot.sendGroupMessageAsync("🤝 СОЮЗ ЗАКЛЮЧЁН\n" + a + " и " + b + " объединились!");
    }

    public void announceVassal(String loser, String lord) {
        bot.sendGroupMessageAsync(
                "🏳️ КАПИТУЛЯЦИЯ\n" +
                        loser + " склонил голову перед " + lord + "\n" +
                        "Теперь " + loser + " — вассал великого правителя"
        );
    }

    public void sendDailyDigest() {
        long since = Instant.now().minusSeconds(24 * 3600L).toEpochMilli();
        int battles = gameDao.battlesCountSince(since);
        int rare = gameDao.rareFindsCountSince(since);
        int built = gameDao.builtCountSince(since);
        Optional<GameDao.WinnerStat> topWinner = gameDao.topWinnerSince(since);
        Optional<PlayerRecord> mostAttacked = gameDao.mostAttackedSince(since);
        int mostAttackedCount = gameDao.mostAttackedCountSince(since);

        String bestWarrior = topWinner.flatMap(s ->
                gameDao.findPlayerById(s.winnerId()).map(p -> p.villageName() + " (" + s.wins() + " побед)")
        ).orElse("—");
        String attackedVillage = mostAttacked.map(PlayerRecord::villageName).orElse("—");

        List<PlayerRecord> top10 = gameDao.topPlayersByCityLevel(10);
        StringBuilder top = new StringBuilder();
        for (int i = 0; i < top10.size(); i++) {
            PlayerRecord p = top10.get(i);
            int power = gameDao.calculateTotalArmyPower(p.id(), gameCatalog, p.faction());
            String medal = switch (i) {
                case 0 -> "🥇";
                case 1 -> "🥈";
                case 2 -> "🥉";
                default -> (i + 1) + ".";
            };
            top.append(medal).append(" ").append(p.villageName())
                    .append(" — ").append(cityLevelTitle(p.cityLevel()))
                    .append(" | Мощь: ").append(power).append("\n");
        }

        String epic = gameDao.epicBattleSince(since).flatMap(ep ->
                gameDao.findPlayerById(ep.attackerId()).flatMap(att ->
                        gameDao.findPlayerById(ep.defenderId()).flatMap(def ->
                                gameDao.findPlayerById(ep.winnerId())
                                        .map(win -> att.villageName() + " vs " + def.villageName() + " — " + win.villageName() + " победил!")
                        )
                )
        ).orElse("—");

        String date = LocalDate.now(ZoneOffset.UTC).format(DATE_FMT);
        String msg = "📰 ИТОГИ ДНЯ — " + date + "\n" +
                "━━━━━━━━━━━━━━━\n" +
                "⚔️ Боёв сыграно: " + battles + "\n" +
                "🏆 Лучший воин: " + bestWarrior + "\n" +
                "💀 Самый атакуемый: " + attackedVillage + " (" + mostAttackedCount + " раз)\n" +
                "📜 Редких находок: " + rare + "\n" +
                "🏗️ Построено зданий: " + built + "\n" +
                "━━━━━━━━━━━━━━━\n" +
                "🏆 ТОП-10 ГОРОДОВ:\n" +
                top +
                "━━━━━━━━━━━━━━━\n" +
                "🔥 Самая эпичная битва дня:\n" + epic;
        bot.sendGroupMessageAsync(msg);
    }

    public void sendMorningEvent() {
        DailyEventType event = DailyEventType.random();
        gameDao.setServerState(DailyEventType.STATE_KEY_EVENT, event.name());
        gameDao.setServerState(DailyEventType.STATE_KEY_EVENT_DATE, DailyEventType.todayUtc());

        long since = Instant.now().minusSeconds(24 * 3600L).toEpochMilli();
        int active = gameDao.activePlayersSince(since);
        int battles = gameDao.battlesCountSince(since);

        String msg = "🌅 ДОБРОЕ УТРО, ПРАВИТЕЛИ!\n" +
                "━━━━━━━━━━━━━━━\n" +
                "🎯 СОБЫТИЕ ДНЯ:\n" +
                event.text() + "\n" +
                "━━━━━━━━━━━━━━━\n" +
                "Активных игроков вчера: " + active + "\n" +
                "Боёв вчера: " + battles + "\n" +
                "Удачного дня! ⚔️";
        bot.sendGroupMessageAsync(msg);
    }

    private String cityLevelTitle(int level) {
        return switch (level) {
            case 1 -> "Деревня";
            case 2 -> "Посёлок";
            case 3 -> "Город";
            case 4 -> "Замок";
            case 5 -> "Королевство";
            case 6 -> "Империя";
            case 7 -> "Мировая держава";
            default -> "Уровень " + level;
        };
    }
}
