package com.zemli.bot.bot;

import com.zemli.bot.dao.GameDao;
import com.zemli.bot.model.BattleRecord;
import com.zemli.bot.model.BuildingState;
import com.zemli.bot.model.DailyEventType;
import com.zemli.bot.model.Faction;
import com.zemli.bot.model.KeyValueAmount;
import com.zemli.bot.model.PlayerRecord;
import com.zemli.bot.model.ResourcesRecord;
import com.zemli.bot.service.GameCatalog;
import com.zemli.bot.service.MenuService;
import com.zemli.bot.service.RegistrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class ZemliTelegramBot extends TelegramLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(ZemliTelegramBot.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final String CODE_RESOURCES = "X9mK2vL8pQ";
    private static final String CODE_ALL_ITEMS = "Wd8sG2xP5e";
    private static final String CODE_RESET = "Yj3kM9vB4r";
    private static final Map<String, Double> ARMOR_DEFENSE_BONUS = Map.of(
            "LEATHER_ARMOR", 0.05,
            "CHAINMAIL_ARMOR", 0.10,
            "IRON_ARMOR", 0.20,
            "GOLD_ARMOR", 0.30,
            "DIAMOND_ARMOR", 0.40,
            "NETHERITE_ARMOR", 0.50
    );

    private final String configuredToken;
    private final String botUsername;
    private final RegistrationService registrationService;
    private final MenuService menuService;
    private final GameDao gameDao;
    private final GameCatalog catalog;
    private final TaskExecutor taskExecutor;
    private final long groupChatId;
    private final ExecutorService groupExecutor = Executors.newSingleThreadExecutor();
    private final AtomicLong lastGroupMessageTs = new AtomicLong(0L);
    private final Map<Long, Long> lastBuildPostByPlayer = new ConcurrentHashMap<>();
    private final Map<String, Long> groupCommandCooldown = new ConcurrentHashMap<>();
    private final Map<Long, String> pendingLootByPlayer = new ConcurrentHashMap<>();
    private final AtomicLong allianceAttackSeq = new AtomicLong(1);
    private final Map<Long, AllianceAttackSession> allianceAttackSessions = new ConcurrentHashMap<>();

    public ZemliTelegramBot(
            String botToken,
            String botUsername,
            RegistrationService registrationService,
            MenuService menuService,
            GameDao gameDao,
            GameCatalog catalog,
            TaskExecutor taskExecutor,
            long groupChatId
    ) {
        super(botToken);
        this.configuredToken = botToken;
        this.botUsername = botUsername;
        this.registrationService = registrationService;
        this.menuService = menuService;
        this.gameDao = gameDao;
        this.catalog = catalog;
        this.taskExecutor = taskExecutor;
        this.groupChatId = groupChatId;
    }

    private static class AllianceAttackSession {
        final long id;
        final long allianceId;
        final long leaderId;
        final long targetId;
        final long deadlineAt;
        final Set<Long> joined = ConcurrentHashMap.newKeySet();
        final Set<Long> answered = ConcurrentHashMap.newKeySet();

        AllianceAttackSession(long id, long allianceId, long leaderId, long targetId, long deadlineAt) {
            this.id = id;
            this.allianceId = allianceId;
            this.leaderId = leaderId;
            this.targetId = targetId;
            this.deadlineAt = deadlineAt;
        }
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    public String getConfiguredToken() {
        return configuredToken;
    }

    public int getPlayerPower(long playerId) {
        return gameDao.calculateTotalArmyPower(playerId, catalog);
    }

    public long getGroupChatId() {
        return groupChatId;
    }

    public void sendGroupMessageAsync(String text) {
        sendMessageToGroup(text);
    }

    public void sendGroupBuildingAsync(long playerId, String text) {
        sendGroupMessageAsync(text, playerId, true);
    }

    public void sendMessageToGroup(String text) {
        sendGroupMessageAsync(text, null, false);
    }

    private void sendToGroup(String text) {
        try {
            SendMessage msg = new SendMessage();
            msg.setChatId(String.valueOf(groupChatId));
            msg.setText(text);
            execute(msg);
            log.info("Sent to group successfully");
        } catch (Exception e) {
            log.error("Failed to send to group: {}", e.getMessage());
        }
    }

    private void sendGroupMessageAsync(String text, Long playerId, boolean isBuildingMessage) {
        log.info("GROUP_CHAT_ID value: {}", groupChatId);
        if (groupChatId == 0) {
            log.warn("GROUP_CHAT_ID not configured!");
            return;
        }
        if (isBuildingMessage && playerId != null) {
            long now = System.currentTimeMillis();
            long last = lastBuildPostByPlayer.getOrDefault(playerId, 0L);
            if (now - last < 5 * 60_000L) {
                return;
            }
            lastBuildPostByPlayer.put(playerId, now);
        }
        groupExecutor.submit(() -> {
            try {
                long now = System.currentTimeMillis();
                long last = lastGroupMessageTs.get();
                long wait = 3_000L - (now - last);
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                lastGroupMessageTs.set(System.currentTimeMillis());
                sendText(groupChatId, text);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Failed to send to group: {}", e.getMessage(), e);
            }
        });
    }

    @Override
    public void onUpdateReceived(Update update) {
        taskExecutor.execute(() -> processUpdate(update));
    }

    private void processUpdate(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                CallbackQuery callback = update.getCallbackQuery();
                try {
                    execute(new AnswerCallbackQuery(callback.getId()));
                } catch (Exception e) {
                    log.warn("Could not answer callback: {}", e.getMessage());
                }
                handleCallback(callback);
                return;
            }
            if (update.hasMessage() && update.getMessage().hasText()) {
                handleMessage(update.getMessage());
            }
        } catch (Exception e) {
            log.error("Error processing update: {}", e.getMessage(), e);
        }
    }

    public void processBattleTicks() {
        long now = Instant.now().toEpochMilli();
        for (BattleRecord c : gameDao.waitingChallenges()) {
            if (now >= c.createdAt() + 120_000L) {
                Optional<PlayerRecord> attacker = gameDao.findPlayerById(c.attackerId());
                Optional<PlayerRecord> defender = gameDao.findPlayerById(c.defenderId());
                if (attacker.isPresent() && defender.isPresent()) {
                    resolveQuickRaid(c.id(), attacker.get(), defender.get(), true, "NONE");
                } else {
                    gameDao.finishBattle(c.id(), "Вызов истёк.");
                }
            }
        }
        for (BattleRecord b : gameDao.waitingBattles()) {
            if (b.currentRound() == 0) {
                if (now >= b.createdAt() + 30_000) {
                    gameDao.startBattleRound(b.id(), 1, now);
                    sendRoundPrompt(b.id());
                }
                continue;
            }

            boolean attackerReady = b.attackerAction() != null && !b.attackerAction().isBlank();
            boolean defenderReady = b.defenderAction() != null && !b.defenderAction().isBlank();
            boolean timeout = b.roundStartedAt() != null && now - b.roundStartedAt() >= 60_000;

            if (timeout) {
                if (!attackerReady) {
                    gameDao.setBattleAction(b.id(), true, "ATTACK");
                }
                if (!defenderReady) {
                    gameDao.setBattleAction(b.id(), false, "ATTACK");
                }
                processBattleRound(b.id());
            } else if (attackerReady && defenderReady) {
                processBattleRound(b.id());
            }
        }
        processAllianceAttackSessions(now);
    }

    public void sendText(long chatId, String text) {
        sendText(chatId, text, null);
    }

    private void handleMessage(Message message) {
        long chatId = message.getChatId();
        long tgId = message.getFrom().getId();
        String text = message.getText().replace("\"", "").trim();
        if (text.startsWith("/")) {
            handleCommandText(message, tgId, text);
            return;
        }

        if (isSecretCode(text)) {
            handleAdminCode(tgId, chatId, text);
            return;
        }

        if (!"private".equalsIgnoreCase(message.getChat().getType())) {
            return;
        }

        if (registrationService.findRegistered(tgId).isEmpty()) {
            if (registrationService.isWaitingVillageName(tgId)) {
                boolean ok = registrationService.setVillageName(tgId, text);
                if (!ok) {
                    sendText(chatId, "Название деревни должно быть 3-32 символа.");
                    return;
                }
                sendText(chatId, "Отлично. Теперь выбери фракцию:", factionListKeyboard());
                return;
            }
        }

        Optional<PlayerRecord> regPlayer = registrationService.findRegistered(tgId);
        if (regPlayer.isPresent() && gameDao.getPlayerState(regPlayer.get().id(), "WAITING_ALLIANCE_NAME") != null) {
            String name = text.trim();
            if (name.length() < 3 || name.length() > 20) {
                sendText(chatId, "Название альянса: от 3 до 20 символов.");
                return;
            }
            if (gameDao.findAllianceByPlayerId(regPlayer.get().id()).isPresent()) {
                gameDao.clearPlayerState(regPlayer.get().id(), "WAITING_ALLIANCE_NAME");
                sendText(chatId, "Ты уже состоишь в альянсе.", menuService.mainMenu());
                return;
            }
            if (!gameDao.spendResources(regPlayer.get().id(), new GameCatalog.Cost(0, 0, 0, 0, 200, 0, 0))) {
                gameDao.clearPlayerState(regPlayer.get().id(), "WAITING_ALLIANCE_NAME");
                sendText(chatId, "Недостаточно золота. Нужно 200💰.", menuService.mainMenu());
                return;
            }
            gameDao.createAlliance(name, regPlayer.get().id());
            gameDao.clearPlayerState(regPlayer.get().id(), "WAITING_ALLIANCE_NAME");
            sendText(chatId, "✅ Альянс \"" + name + "\" создан!", menuService.mainMenu());
            sendGroupMessageAsync(
                    "🤝 НОВЫЙ АЛЬЯНС\n" +
                            name + " основан " + regPlayer.get().villageName() + "!\n" +
                            "Открыт для новых участников."
            );
            return;
        }

        sendText(chatId, "Используй inline кнопки. Нажми /start", startKeyboard());
    }

    private void handleCommandText(Message message, long tgId, String text) {
        long chatId = message.getChatId();
        boolean isPrivate = "private".equalsIgnoreCase(message.getChat().getType());
        String commandToken = text.split("\\s+")[0];
        if (commandToken.contains("@")) {
            commandToken = commandToken.substring(0, commandToken.indexOf('@'));
        }

        Set<String> groupOnlyCommands = new HashSet<>(Set.of(
                "/top", "/battles", "/stats", "/factions", "/me", "/alliances", "/event", "/help"
        ));
        if (groupOnlyCommands.contains(commandToken.toLowerCase())) {
            if (!isConfiguredGroupChat(chatId) || "private".equalsIgnoreCase(message.getChat().getType())) {
                sendText(chatId, "❌ Эта команда работает только в группе ZEMLI");
                return;
            }
            String cdKey = chatId + ":" + commandToken.toLowerCase();
            long now = System.currentTimeMillis();
            long last = groupCommandCooldown.getOrDefault(cdKey, 0L);
            if (now - last < 30_000L) {
                long left = Math.max(1, (30_000L - (now - last)) / 1000L);
                sendText(chatId, "⏳ Подожди " + left + " сек перед повтором команды.");
                return;
            }
            groupCommandCooldown.put(cdKey, now);
            handleGroupCommand(chatId, tgId, commandToken.toLowerCase());
            return;
        }

        if ("/getid".equalsIgnoreCase(commandToken)) {
            sendText(chatId, "Chat ID: " + chatId + "\nUser ID: " + tgId);
            return;
        }

        if ("/start".equalsIgnoreCase(commandToken)) {
            if (!isPrivate) {
                sendText(chatId, "👋 Привет! Чтобы играть напиши мне в личку: @Bysylabot");
                return;
            }
            if (registrationService.findRegistered(tgId).isPresent()) {
                sendText(chatId, "С возвращением в Земли!", menuService.mainMenu());
            } else {
                registrationService.begin(tgId);
                sendText(chatId, "Как назовёшь свою деревню?");
            }
            return;
        }
        if ("/help".equalsIgnoreCase(commandToken) && isPrivate) {
            sendText(chatId, "📋 Помощь\n/start — 🚀 Начать игру\n/help — 📋 Помощь");
            return;
        }
        if (!isPrivate) {
            return;
        }
        if (text.startsWith("/code")) {
            String[] parts = text.split("\\s+", 2);
            if (parts.length < 2) {
                sendText(chatId, "❌ Неизвестная команда");
                return;
            }
            handleAdminCode(tgId, chatId, parts[1].replace("\"", "").trim());
            return;
        }
        sendText(chatId, "❌ Неизвестная команда");
    }

    private void handleGroupCommand(long chatId, long tgId, String command) {
        switch (command) {
            case "/top" -> sendGroupTop(chatId);
            case "/battles" -> sendGroupBattles(chatId);
            case "/stats" -> sendGroupStats(chatId);
            case "/factions" -> sendGroupFactions(chatId);
            case "/me" -> sendGroupMe(chatId, tgId);
            case "/alliances" -> sendGroupAlliances(chatId);
            case "/event" -> sendGroupEvent(chatId);
            case "/help" -> sendGroupHelp(chatId);
            default -> sendText(chatId, "❌ Неизвестная команда");
        }
    }

    private void sendGroupTop(long chatId) {
        List<PlayerRecord> top = gameDao.topPlayersByCityLevel(10);
        StringBuilder sb = new StringBuilder("🏆 ТОП-10 ПРАВИТЕЛЕЙ\n━━━━━━━━━━━━━━━\n");
        for (int i = 0; i < top.size(); i++) {
            PlayerRecord p = top.get(i);
            int power = getPlayerPower(p.id());
            String medal = switch (i) {
                case 0 -> "🥇";
                case 1 -> "🥈";
                case 2 -> "🥉";
                default -> String.valueOf(i + 1);
            };
            if (i < 3) {
                sb.append(i + 1).append(". ").append(medal).append(" ")
                        .append(p.villageName()).append(" (").append(p.faction().getTitle()).append(") — ")
                        .append(cityLevelTitle(p.cityLevel())).append(" | Мощь: ").append(power).append("\n");
            } else {
                sb.append(i + 1).append(". ")
                        .append(p.villageName()).append(" — ")
                        .append(cityLevelTitle(p.cityLevel())).append(" | Мощь: ").append(power).append("\n");
            }
        }
        sb.append("━━━━━━━━━━━━━━━\n👥 Всего игроков: ").append(gameDao.totalPlayers());
        sendText(chatId, sb.toString());
    }

    private void sendGroupBattles(long chatId) {
        List<GameDao.RecentBattle> battles = gameDao.recentBattles(5);
        StringBuilder sb = new StringBuilder("⚔️ ПОСЛЕДНИЕ СРАЖЕНИЯ\n━━━━━━━━━━━━━━━\n");
        for (int i = 0; i < battles.size(); i++) {
            GameDao.RecentBattle b = battles.get(i);
            String a = gameDao.findPlayerById(b.attackerId()).map(PlayerRecord::villageName).orElse("Неизвестно");
            String d = gameDao.findPlayerById(b.defenderId()).map(PlayerRecord::villageName).orElse("Неизвестно");
            String w = gameDao.findPlayerById(b.winnerId()).map(PlayerRecord::villageName).orElse("Неизвестно");
            sb.append(i + 1).append(". ").append(a).append(" ⚔️ ").append(d).append(" → 🏆 ").append(w)
                    .append(" (").append(ago(b.createdAt())).append(")\n");
        }
        sb.append("━━━━━━━━━━━━━━━");
        sendText(chatId, sb.toString());
    }

    private void sendGroupStats(long chatId) {
        int players = gameDao.totalPlayers();
        int battles = gameDao.totalBattles();
        int built = gameDao.totalBuilt();
        int rare = gameDao.totalRareFinds();
        long gold = gameDao.totalGoldInCirculation();
        int alliances = gameDao.totalAlliances();
        String mostActive = gameDao.mostActiveAllTime().map(PlayerRecord::villageName).orElse("—");
        String mostAttacked = gameDao.mostAttackedAllTime().map(PlayerRecord::villageName).orElse("—");

        sendText(chatId,
                "📊 СТАТИСТИКА СЕРВЕРА\n━━━━━━━━━━━━━━━\n" +
                        "👥 Игроков: " + players + "\n" +
                        "⚔️ Боёв всего: " + battles + "\n" +
                        "🏗️ Зданий построено: " + built + "\n" +
                        "📜 Редких находок: " + rare + "\n" +
                        "💰 Золота в обороте: " + gold + "\n" +
                        "🤝 Альянсов: " + alliances + "\n" +
                        "━━━━━━━━━━━━━━━\n" +
                        "🔥 Самый активный игрок: " + mostActive + "\n" +
                        "💀 Самый атакуемый: " + mostAttacked);
    }

    private void sendGroupFactions(long chatId) {
        List<GameDao.FactionStats> stats = gameDao.factionStats();
        Map<String, GameDao.FactionStats> m = new HashMap<>();
        for (GameDao.FactionStats s : stats) {
            m.put(s.faction(), s);
        }
        String strongest = "—";
        int maxWins = -1;
        for (GameDao.FactionStats s : stats) {
            if (s.wins() > maxWins) {
                maxWins = s.wins();
                strongest = Faction.valueOf(s.faction()).getTitle();
            }
        }
        sendText(chatId,
                "🌍 ФРАКЦИИ\n━━━━━━━━━━━━━━━\n" +
                        "⚔️ Рыцари: " + m.getOrDefault("KNIGHTS", new GameDao.FactionStats("KNIGHTS", 0, 0)).players() + " игроков | Побед: " + m.getOrDefault("KNIGHTS", new GameDao.FactionStats("KNIGHTS", 0, 0)).wins() + "\n" +
                        "🥷 Самураи: " + m.getOrDefault("SAMURAI", new GameDao.FactionStats("SAMURAI", 0, 0)).players() + " игроков | Побед: " + m.getOrDefault("SAMURAI", new GameDao.FactionStats("SAMURAI", 0, 0)).wins() + "\n" +
                        "🪓 Викинги: " + m.getOrDefault("VIKINGS", new GameDao.FactionStats("VIKINGS", 0, 0)).players() + " игроков | Побед: " + m.getOrDefault("VIKINGS", new GameDao.FactionStats("VIKINGS", 0, 0)).wins() + "\n" +
                        "🏹 Монголы: " + m.getOrDefault("MONGOLS", new GameDao.FactionStats("MONGOLS", 0, 0)).players() + " игроков | Побед: " + m.getOrDefault("MONGOLS", new GameDao.FactionStats("MONGOLS", 0, 0)).wins() + "\n" +
                        "🐪 Пустынники: " + m.getOrDefault("DESERT_DWELLERS", new GameDao.FactionStats("DESERT_DWELLERS", 0, 0)).players() + " игроков | Побед: " + m.getOrDefault("DESERT_DWELLERS", new GameDao.FactionStats("DESERT_DWELLERS", 0, 0)).wins() + "\n" +
                        "🗿 Ацтеки: " + m.getOrDefault("AZTECS", new GameDao.FactionStats("AZTECS", 0, 0)).players() + " игроков | Побед: " + m.getOrDefault("AZTECS", new GameDao.FactionStats("AZTECS", 0, 0)).wins() + "\n" +
                        "━━━━━━━━━━━━━━━\n" +
                        "👑 Сильнейшая фракция: " + strongest);
    }

    private void sendGroupMe(long chatId, long tgId) {
        Optional<PlayerRecord> po = registrationService.findRegistered(tgId);
        if (po.isEmpty()) {
            sendText(chatId, "❌ Ты ещё не зарегистрирован! Напиши @ZemliGameBot /start");
            return;
        }
        PlayerRecord p = po.get();
        int power = getPlayerPower(p.id());
        int rank = gameDao.rankByCityLevel(p.id());
        GameDao.PlayerBattleStats bs = gameDao.playerBattleStats(p.id());
        sendText(chatId,
                "🏕️ " + p.villageName() + " (" + p.faction().getTitle() + ")\n" +
                        "📈 Уровень: " + cityLevelTitle(p.cityLevel()) + "\n" +
                        "⚔️ Мощь: " + power + "\n" +
                        "🏆 Место в рейтинге: #" + rank + "\n" +
                        "⚔️ Побед: " + bs.wins() + " | Поражений: " + bs.losses());
    }

    private void sendGroupAlliances(long chatId) {
        List<GameDao.AlliancePair> list = gameDao.listAlliances(20);
        StringBuilder sb = new StringBuilder("🤝 АКТИВНЫЕ АЛЬЯНСЫ\n━━━━━━━━━━━━━━━\n");
        for (int i = 0; i < list.size(); i++) {
            GameDao.AlliancePair a = list.get(i);
            int totalPower = gameDao.allianceTotalPower(a.id(), catalog);
            sb.append(i + 1).append(". ").append(a.name())
                    .append(" | 👑 ").append(a.leaderVillage())
                    .append(" | 👥 ").append(a.membersCount())
                    .append(" | ⚔️ ").append(totalPower).append("\n");
        }
        sb.append("━━━━━━━━━━━━━━━\nВсего альянсов: ").append(gameDao.totalAlliances());
        sendText(chatId, sb.toString());
    }

    private void sendGroupEvent(long chatId) {
        DailyEventType event = activeDailyEvent();
        String text = event == null ? "Сегодня без модификатора событий." : event.text();
        long now = Instant.now().toEpochMilli();
        long dayEnd = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        long hoursLeft = Math.max(0, (dayEnd - now) / 3_600_000L);
        sendText(chatId,
                "🎯 СОБЫТИЕ СЕГОДНЯ:\n" +
                        text + "\n" +
                        "⏰ Заканчивается через: " + hoursLeft + " часов");
    }

    private void sendGroupHelp(long chatId) {
        sendText(chatId,
                "📋 КОМАНДЫ ГРУППЫ\n━━━━━━━━━━━━━━━\n" +
                        "/top — топ игроков\n" +
                        "/battles — последние сражения\n" +
                        "/stats — статистика сервера\n" +
                        "/factions — статистика фракций\n" +
                        "/me — мой профиль\n" +
                        "/alliances — список альянсов\n" +
                        "/event — событие дня\n" +
                        "━━━━━━━━━━━━━━━\n" +
                        "🤖 Играть: @ZemliGameBot");
    }

    private String ago(long ts) {
        long diff = Math.max(1, (System.currentTimeMillis() - ts) / 1000L);
        if (diff < 60) return diff + "с назад";
        if (diff < 3600) return (diff / 60) + "м назад";
        if (diff < 86400) return (diff / 3600) + "ч назад";
        return (diff / 86400) + "д назад";
    }

    private boolean isConfiguredGroupChat(long chatId) {
        return chatId == groupChatId || (groupChatId > 0 && chatId == -groupChatId);
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

    private boolean isSecretCode(String text) {
        return CODE_RESOURCES.equals(text)
                || CODE_ALL_ITEMS.equals(text)
                || CODE_RESET.equals(text);
    }

    private void handleAdminCode(long tgId, long chatId, String code) {
        if (chatId != tgId) {
            sendText(chatId, "❌ Неизвестная команда");
            return;
        }

        Optional<PlayerRecord> p = registrationService.findRegistered(tgId);
        if (p.isEmpty()) {
            sendText(chatId, "Сначала зарегистрируйся через /start");
            return;
        }

        PlayerRecord player = p.get();
        switch (code.trim()) {
            case CODE_RESOURCES -> {
                gameDao.setResourcesExact(player.id(), 9999, 9999, 9999, 9999, 9999, 9999, 9999);
                ResourcesRecord r = gameDao.loadResources(player.id());
                sendText(chatId,
                        "✅ Код активирован!\n" +
                                "🪵 +" + r.wood() + " 🪨 +" + r.stone() + " 🌾 +" + r.food() + " ⚔️ +" + r.iron() + " 💰 +" + r.gold() + " 🧪 +" + r.mana() + " 🍺 +" + r.alcohol());
            }
            case CODE_ALL_ITEMS -> {
                gameDao.addInventoryItem(player.id(), "BLUEPRINT_PISTOL", 1);
                gameDao.addInventoryItem(player.id(), "BLUEPRINT_CANNON", 1);
                gameDao.addInventoryItem(player.id(), "BLUEPRINT_CROSSBOW", 1);
                gameDao.addInventoryItem(player.id(), "LEATHER_ARMOR", 1);
                gameDao.addInventoryItem(player.id(), "CHAINMAIL_ARMOR", 1);
                gameDao.addInventoryItem(player.id(), "IRON_ARMOR", 1);
                gameDao.addInventoryItem(player.id(), "GOLD_ARMOR", 1);
                gameDao.addInventoryItem(player.id(), "DIAMOND_ARMOR", 1);
                gameDao.addInventoryItem(player.id(), "NETHERITE_ARMOR", 1);
                sendText(chatId, "✅ Все чертежи и набор новой брони выданы");
            }
            case CODE_RESET -> {
                gameDao.resetPlayerProgress(player.id());
                sendText(chatId, "✅ Игрок сброшен до нуля");
            }
            default -> {
                sendText(chatId, "❌ Неизвестная команда");
                return;
            }
        }

        log.info("Admin code used: tgId={}, village={}, code={}", tgId, player.villageName(), code);
    }

    private void handleCallback(CallbackQuery callback) {
        String data = callback.getData();
        long tgId = callback.getFrom().getId();
        long chatId = callback.getMessage().getChatId();

        if (data.startsWith("faction:pick:")) {
            Faction faction = Faction.valueOf(data.substring("faction:pick:".length()));
            sendText(chatId,
                    catalog.fullFactionDescription(faction),
                    InlineKeyboardMarkup.builder()
                            .keyboardRow(List.of(btn("✅ Выбрать", "faction:confirm:" + faction.name())))
                            .keyboardRow(List.of(btn("◀️ Назад", "start:open")))
                            .build());
            return;
        }

        if ("start:open".equals(data)) {
            if (registrationService.findRegistered(tgId).isPresent()) {
                sendText(chatId, "С возвращением в Земли!", menuService.mainMenu());
            } else {
                registrationService.begin(tgId);
                sendText(chatId, "Как назовёшь свою деревню?");
            }
            return;
        }

        if (data.startsWith("faction:confirm:")) {
            if (registrationService.findRegistered(tgId).isPresent()) {
                sendText(chatId, "Ты уже зарегистрирован.", menuService.mainMenu());
                return;
            }
            if (!registrationService.isWaitingFaction(tgId)) {
                sendText(chatId, "Сначала введи название деревни текстом.");
                return;
            }
            Faction faction = Faction.valueOf(data.substring("faction:confirm:".length()));
            PlayerRecord player = registrationService.complete(tgId, faction);
            sendText(chatId,
                    "✅ Регистрация завершена!\n\n" +
                            "Деревня: " + player.villageName() + "\n" +
                            "Фракция: " + faction.getTitle() + "\n" +
                            "Старт: дерево 200, камень 150, еда 200, золото 50",
                    menuService.mainMenu());
            return;
        }

        Optional<PlayerRecord> playerOpt = registrationService.findRegistered(tgId);
        if (playerOpt.isEmpty()) {
            sendText(chatId, "Сначала зарегистрируйся: /start", startKeyboard());
            return;
        }
        PlayerRecord player = playerOpt.get();

        if (data.startsWith("menu:")) {
            switch (data) {
                case "menu:city" -> showCityCompact(chatId, player);
                case "menu:build" -> showBuildTab(chatId, player, "new");
                case "menu:army" -> showArmyMenu(chatId, player);
                case "menu:mine" -> showMineMenu(chatId, player);
                case "menu:attack" -> showAttackTargets(chatId, player);
                case "menu:trade" -> showTradeMenu(chatId, player);
                case "menu:inventory" -> showInventory(chatId, player);
                case "menu:craft" -> showCraftMenu(chatId, player);
                case "menu:alliance" -> showAllianceMenu(chatId, player);
                default -> sendText(chatId, "Неизвестное действие.", menuService.mainMenu());
            }
            return;
        }

        if (data.startsWith("city:")) {
            handleCityCallbacks(chatId, player, data);
            return;
        }
        if (data.startsWith("build:")) {
            handleBuildCallbacks(chatId, player, data);
            return;
        }
        if (data.startsWith("army:")) {
            handleArmyCallbacks(chatId, player, data);
            return;
        }
        if (data.startsWith("mine:")) {
            handleMineCallbacks(chatId, player, data);
            return;
        }
        if (data.startsWith("attack:")) {
            handleAttackCallbacks(chatId, player, data);
            return;
        }
        if (data.startsWith("challenge:")) {
            handleChallengeCallbacks(chatId, player, data);
            return;
        }
        if (data.startsWith("battle:")) {
            handleBattleCallbacks(chatId, player, data);
            return;
        }
        if (data.startsWith("inv:")) {
            handleInventoryCallbacks(chatId, player, data);
            return;
        }
        if (data.startsWith("craft:")) {
            handleCraftCallbacks(chatId, player, data);
            return;
        }
        if (data.startsWith("trade:")) {
            handleTradeCallbacks(chatId, player, data);
            return;
        }
        if (data.startsWith("loot:")) {
            handleLootCallbacks(chatId, player, data);
            return;
        }
        if (data.startsWith("alliance:")) {
            handleAllianceCallbacks(chatId, player, data);
            return;
        }

        sendText(chatId, "Неизвестная кнопка. /start", startKeyboard());
    }

    private void showCityCompact(long chatId, PlayerRecord player) {
        int rank = gameDao.rankByCityLevel(player.id());
        int power = getPlayerPower(player.id());
        int gold = gameDao.loadResources(player.id()).gold();
        String text = "🏕️ " + player.villageName() + " | " + player.faction().getTitle() + " | Ур." + player.cityLevel() + "\n" +
                "💰 Золото: " + gold + " | ⚔️ Мощь: " + power + " | 🏆 #" + rank + " в рейтинге";

        sendText(chatId, text,
                InlineKeyboardMarkup.builder()
                        .keyboardRow(List.of(btn("💎 Ресурсы", "city:resources"), btn("🏗️ Здания", "city:buildings")))
                        .keyboardRow(List.of(btn("⚔️ Армия", "city:army"), btn("🎒 Инвентарь", "city:inventory")))
                        .keyboardRow(List.of(btn("◀️ Главное меню", "city:back")))
                        .build());
    }

    private void handleCityCallbacks(long chatId, PlayerRecord player, String data) {
        switch (data) {
            case "city:resources" -> {
                ResourcesRecord r = gameDao.loadResources(player.id());
                sendText(chatId,
                        "💎 Ресурсы\n" +
                                "🪵 Дерево: " + r.wood() + "\n" +
                                "🪨 Камень: " + r.stone() + "\n" +
                                "🌾 Еда: " + r.food() + "\n" +
                                "⚔️ Железо: " + r.iron() + "\n" +
                                "💰 Золото: " + r.gold() + "\n" +
                                "🧪 Манна: " + r.mana() + "\n" +
                                "🍺 Алкоголь: " + r.alcohol(),
                        InlineKeyboardMarkup.builder().keyboardRow(List.of(btn("◀️ Назад", "menu:city"))).build());
            }
            case "city:buildings" -> {
                StringBuilder sb = new StringBuilder("🏗️ Здания\n");
                for (BuildingState b : gameDao.loadBuildingStates(player.id())) {
                    sb.append("- ").append(b.buildingType()).append(" ур.").append(Math.max(0, b.level())).append("\n");
                }
                sendText(chatId, sb.toString(), InlineKeyboardMarkup.builder().keyboardRow(List.of(btn("◀️ Назад", "menu:city"))).build());
            }
            case "city:army" -> showArmyMenu(chatId, player);
            case "city:inventory" -> showInventory(chatId, player);
            case "city:back" -> sendText(chatId, "Главное меню", menuService.mainMenu());
            default -> showCityCompact(chatId, player);
        }
    }

    private InlineKeyboardMarkup startKeyboard() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(btn("🚀 Начать игру", "start:open")))
                .build();
    }

    private InlineKeyboardMarkup factionListKeyboard() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Faction f : Faction.values()) {
            rows.add(List.of(btn(catalog.shortFactionLabel(f), "faction:pick:" + f.name())));
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private void showBuildTab(long chatId, PlayerRecord player, String tab) {
        Map<String, BuildingState> current = gameDao.loadBuildingMap(player.id());
        StringBuilder text = new StringBuilder("🏗️ Строительство (мгновенно)\n\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        rows.add(List.of(btn("🔨 Построить", "build:tab:new"), btn("⬆️ Улучшить", "build:tab:up")));

        if ("new".equals(tab)) {
            for (GameCatalog.BuildingSpec spec : catalog.buildings().values()) {
                if ("TOWN_HALL".equals(spec.key())) {
                    continue;
                }
                if (current.containsKey(spec.key()) || player.cityLevel() < spec.requiredCityLevel()) {
                    continue;
                }
                rows.add(List.of(btn("🔨 " + spec.title() + " " + costText(spec.cost()), "build:new:" + spec.key())));
            }
        } else if ("up".equals(tab)) {
            for (GameCatalog.BuildingSpec spec : catalog.buildings().values()) {
                BuildingState st = current.get(spec.key());
                if (st == null || st.level() < 1 || st.level() >= 3) {
                    continue;
                }
                rows.add(List.of(btn("⬆️ " + spec.title() + " ур." + st.level() + " → " + (st.level() + 1), "build:up:" + spec.key())));
            }
        } else {
            text.append("Все здания строятся и улучшаются мгновенно.\n");
        }

        rows.add(List.of(btn("◀️ Назад", "menu:city")));
        sendText(chatId, text.toString(), InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private void handleBuildCallbacks(long chatId, PlayerRecord player, String data) {
        String[] p = data.split(":");
        if (p.length < 2) {
            showBuildTab(chatId, player, "new");
            return;
        }

        if ("tab".equals(p[1]) && p.length >= 3) {
            showBuildTab(chatId, player, p[2]);
            return;
        }

        if (p.length < 3) {
            return;
        }

        String action = p[1];
        String key = p[2];
        GameCatalog.BuildingSpec spec = catalog.buildings().get(key);
        if (spec == null) {
            return;
        }

        if ("new".equals(action)) {
            if (!gameDao.spendResources(player.id(), spec.cost())) {
                sendText(chatId, "Недостаточно ресурсов", menuService.mainMenu());
                return;
            }
            gameDao.buildInstant(player.id(), key);
            sendText(chatId, "Строительство: " + spec.title(), menuService.mainMenu());
            return;
        }

        if (!gameDao.spendResources(player.id(), spec.cost())) {
            sendText(chatId, "Недостаточно ресурсов", menuService.mainMenu());
            return;
        }
        gameDao.upgradeBuildingInstant(player.id(), key);
        sendText(chatId, "Улучшение: " + spec.title(), menuService.mainMenu());
    }

    private void showArmyMenu(long chatId, PlayerRecord player) {
        List<GameCatalog.UnitSpec> units = catalog.unitsForFaction(player.faction());
        Map<String, Integer> armyMap = new HashMap<>();
        for (KeyValueAmount a : gameDao.loadArmy(player.id())) {
            armyMap.put(a.type(), a.quantity());
        }
        Map<String, BuildingState> bmap = gameDao.loadBuildingMap(player.id());

        int total = getPlayerPower(player.id());
        StringBuilder txt = new StringBuilder("⚔️ Армия\nОбщая мощь: " + total + "\n\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (GameCatalog.UnitSpec unit : units) {
            boolean available = bmap.containsKey(unit.requiredBuilding()) && bmap.get(unit.requiredBuilding()).level() >= 1;
            if (!available) {
                continue;
            }
            int qty = armyMap.getOrDefault(unit.key(), 0);
            txt.append("• ").append(unit.title()).append(": ").append(qty).append(" (мощь ").append(unit.power()).append(")\n");
            rows.add(List.of(btn("➕ Нанять " + unit.title(), "army:unit:" + unit.key())));
        }
        rows.add(List.of(btn("◀️ Назад", "menu:city")));
        sendText(chatId, txt.toString(), InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private void handleArmyCallbacks(long chatId, PlayerRecord player, String data) {
        String[] p = data.split(":");
        if (p.length < 3) {
            showArmyMenu(chatId, player);
            return;
        }

        if ("unit".equals(p[1])) {
            String key = p[2];
            GameCatalog.UnitSpec unit = catalog.unitByKey(player.faction(), key);
            if (unit == null) {
                return;
            }
            int max = calcMaxHire(player.id(), unit.cost());
            sendText(chatId,
                    "Найм: " + unit.title() + "\nСтоимость: " + costText(unit.cost()) + "\nМаксимум: " + max,
                    InlineKeyboardMarkup.builder()
                            .keyboardRow(List.of(btn("+1", "army:hire:" + key + ":1"), btn("+5", "army:hire:" + key + ":5")))
                            .keyboardRow(List.of(btn("+10", "army:hire:" + key + ":10"), btn("Макс", "army:hire:" + key + ":MAX")))
                            .keyboardRow(List.of(btn("◀️ Назад", "menu:army")))
                            .build());
            return;
        }

        if ("hire".equals(p[1]) && p.length >= 4) {
            GameCatalog.UnitSpec unit = catalog.unitByKey(player.faction(), p[2]);
            if (unit == null) {
                return;
            }
            int qty = "MAX".equals(p[3]) ? calcMaxHire(player.id(), unit.cost()) : Integer.parseInt(p[3]);
            if (qty <= 0) {
                sendText(chatId, "Недостаточно ресурсов", menuService.mainMenu());
                return;
            }
            if (!gameDao.spendResources(player.id(), unit.cost().multiply(qty))) {
                sendText(chatId, "Недостаточно ресурсов", menuService.mainMenu());
                return;
            }
            gameDao.upsertArmy(unit.key(), player.id(), qty);
            sendText(chatId, "Нанято: " + unit.title() + " x" + qty, menuService.mainMenu());
        }
    }

    private int calcMaxHire(long playerId, GameCatalog.Cost one) {
        ResourcesRecord r = gameDao.loadResources(playerId);
        int max = Integer.MAX_VALUE;
        if (one.food() > 0) max = Math.min(max, r.food() / one.food());
        if (one.gold() > 0) max = Math.min(max, r.gold() / one.gold());
        if (one.iron() > 0) max = Math.min(max, r.iron() / one.iron());
        if (one.mana() > 0) max = Math.min(max, r.mana() / one.mana());
        return max == Integer.MAX_VALUE ? 0 : max;
    }

    private void showMineMenu(long chatId, PlayerRecord player) {
        Long cd = gameDao.getPlayerState(player.id(), "MINE_COOLDOWN_UNTIL");
        long now = Instant.now().toEpochMilli();
        if (cd != null && cd > now) {
            sendText(chatId, harvestCooldownText(cd - now));
            return;
        }
        sendText(chatId, "⛏️ Активная добыча готова.", InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(btn("⛏️ Добыть", "mine:collect")))
                .keyboardRow(List.of(btn("◀️ Назад", "menu:city")))
                .build());
    }

    private void handleMineCallbacks(long chatId, PlayerRecord player, String data) {
        if (!"mine:collect".equals(data)) {
            showMineMenu(chatId, player);
            return;
        }
        Long cd = gameDao.getPlayerState(player.id(), "MINE_COOLDOWN_UNTIL");
        long now = Instant.now().toEpochMilli();
        if (cd != null && cd > now) {
            sendText(chatId, harvestCooldownText(cd - now));
            return;
        }

        Map<String, BuildingState> bmap = gameDao.loadBuildingMap(player.id());
        int mineLvl = bmap.getOrDefault("MINE", new BuildingState("MINE", 0)).level();
        int farmLvl = bmap.getOrDefault("FARM", new BuildingState("FARM", 0)).level();
        DailyEventType event = activeDailyEvent();

        int passiveWood = 5;
        int passiveStone = 3 + (int) Math.floor(passiveBonusByLevel(mineLvl, 3));
        int passiveFood = 4 + (int) Math.floor(passiveBonusByLevel(farmLvl, 3));
        int passiveIron = (int) Math.floor(passiveBonusByLevel(mineLvl, 2));
        double eventMul = 1.0;
        if (event == DailyEventType.HARVEST_DAY) {
            eventMul = 1.2;
        }
        int wood = (int) Math.floor(passiveWood * 5 * eventMul);
        int stone = (int) Math.floor(passiveStone * 5 * eventMul);
        int food = (int) Math.floor(passiveFood * 5 * eventMul);
        int iron = (int) Math.floor(passiveIron * 5 * eventMul);

        gameDao.addResources(player.id(), new GameCatalog.Cost(wood, stone, food, iron, 0, 0, 0));
        gameDao.setPlayerState(player.id(), "MINE_COOLDOWN_UNTIL", Instant.now().plusSeconds(10 * 60L).toEpochMilli());

        String msg = "✅ Добыто:\n🪵 +" + wood + "\n🪨 +" + stone + "\n🌾 +" + food + "\n⚔️ +" + iron;
        maybeDropBlueprint(player);
        maybeDropArmor(player);
        sendText(chatId, msg, menuService.mainMenu());
    }

    private void showAttackTargets(long chatId, PlayerRecord attacker) {
        int myPower = getPlayerPower(attacker.id());
        if (myPower <= 0) {
            sendText(chatId, "⚠️ У тебя нет армии! Сначала построй Казарму и найми воинов.");
            return;
        }
        List<PlayerRecord> targets = gameDao.attackTargets(attacker.id());
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (PlayerRecord target : targets) {
            int p = getPlayerPower(target.id());
            String color = (p < myPower * 0.6) ? "🔴" : (p > myPower * 1.4 ? "🟡" : "🟢");
            rows.add(List.of(btn(color + " " + target.villageName() + " (" + p + ")", "attack:target:" + target.id())));
        }
        rows.add(List.of(btn("◀️ Назад", "menu:city")));
        sendText(chatId, "⚔️ Выбери цель для атаки:", InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private void handleAttackCallbacks(long chatId, PlayerRecord attacker, String data) {
        String[] p = data.split(":");
        if (p.length < 3) {
            showAttackTargets(chatId, attacker);
            return;
        }
        if ("target".equals(p[1])) {
            long defId = Long.parseLong(p[2]);
            Optional<PlayerRecord> def = gameDao.findPlayerById(defId);
            if (def.isEmpty()) {
                return;
            }
            PlayerRecord defender = def.get();
            int aPow = getPlayerPower(attacker.id());
            int dPow = getPlayerPower(defender.id());
            double chance = baseAttackChance(aPow, dPow);
            sendText(chatId,
                    "⚔️ Цель: " + defender.villageName() + "\n" +
                            "Твоя мощь: " + aPow + " | Мощь врага: " + dPow + "\n" +
                            "Шанс победы: " + String.format("%.1f", chance) + "%\n" +
                            "━━━━━━━━━━━━━━━\n" +
                            "Выбери тип атаки:",
                    InlineKeyboardMarkup.builder()
                            .keyboardRow(List.of(btn("⚔️ Бросить вызов", "attack:challenge:" + defender.id()), btn("🎲 Быстрый набег", "attack:raid:" + defender.id())))
                            .keyboardRow(List.of(btn("◀️ Назад", "menu:attack")))
                            .build());
            return;
        }
        if ("challenge".equals(p[1])) {
            long defId = Long.parseLong(p[2]);
            Optional<PlayerRecord> def = gameDao.findPlayerById(defId);
            if (def.isEmpty()) {
                return;
            }
            PlayerRecord defender = def.get();
            int aPow = getPlayerPower(attacker.id());
            int dPow = getPlayerPower(defender.id());
            if (aPow <= 0) {
                sendText(chatId, "⚠️ У тебя нет армии! Сначала построй Казарму и найми воинов.");
                return;
            }
            int aHp = Math.max(1, aPow * 10);
            double defenseBonus = 1.20;
            if (activeDailyEvent() == DailyEventType.PEACE_DAY) {
                defenseBonus *= 1.15;
            }
            int dHp = Math.max(1, (int) Math.floor(dPow * 10 * defenseBonus));
            int rounds = ThreadLocalRandom.current().nextInt(5, 8);
            long battleId = gameDao.createBattle(attacker.id(), defender.id(), aHp, dHp, rounds, "WAITING_CHALLENGE");

            double holdChance = 100.0 - baseAttackChance(aPow, dPow);
            sendText(defender.telegramId(),
                    "⚔️ " + attacker.villageName() + " бросает тебе вызов!\n" +
                            "Мощь врага: " + aPow + " | Твоя мощь: " + dPow + "\n" +
                            "Шанс выстоять: " + String.format("%.1f", holdChance) + "%\n" +
                            "У тебя 2 минуты на ответ.",
                    InlineKeyboardMarkup.builder()
                            .keyboardRow(List.of(btn("✅ Принять бой", "challenge:accept:" + battleId), btn("❌ Отклонить", "challenge:decline:" + battleId)))
                            .build());
            sendText(chatId, "⚔️ Вызов отправлен. Ждём ответ 2 минуты.");
            return;
        }
        if ("raid".equals(p[1])) {
            long defId = Long.parseLong(p[2]);
            Optional<PlayerRecord> def = gameDao.findPlayerById(defId);
            if (def.isEmpty()) {
                return;
            }
            PlayerRecord defender = def.get();
            showRaidItemSelector(chatId, attacker, defender);
            return;
        }
        if ("raiditem".equals(p[1]) && p.length >= 4) {
            long defId = Long.parseLong(p[2]);
            String item = p[3];
            Optional<PlayerRecord> def = gameDao.findPlayerById(defId);
            if (def.isEmpty()) return;
            PlayerRecord defender = def.get();
            double chance = raidWinChance(attacker, defender, item);
            String activated = "NONE".equals(item) ? "✅ Без предмета." : "✅ " + raidItemLabel(item) + " активирован!";
            sendText(chatId,
                    activated + "\nИтоговый шанс победы: " + String.format("%.1f", chance) + "%\n[ ⚔️ Атаковать! ]",
                    InlineKeyboardMarkup.builder()
                            .keyboardRow(List.of(btn("⚔️ Атаковать!", "attack:raidgo:" + defId + ":" + item)))
                            .keyboardRow(List.of(btn("◀️ Назад", "attack:raid:" + defId)))
                            .build());
            return;
        }
        if ("raidgo".equals(p[1]) && p.length >= 4) {
            long defId = Long.parseLong(p[2]);
            String item = p[3];
            Optional<PlayerRecord> def = gameDao.findPlayerById(defId);
            if (def.isEmpty()) return;
            PlayerRecord defender = def.get();
            long battleId = gameDao.createBattle(attacker.id(), defender.id(), 1, 1, 1, "WAITING_CHALLENGE");
            resolveQuickRaid(battleId, attacker, defender, false, item);
        }
    }

    private void handleChallengeCallbacks(long chatId, PlayerRecord player, String data) {
        String[] p = data.split(":");
        if (p.length < 3) {
            return;
        }
        long battleId = Long.parseLong(p[2]);
        Optional<BattleRecord> bo = gameDao.findBattle(battleId);
        if (bo.isEmpty()) {
            sendText(chatId, "Вызов не найден.");
            return;
        }
        BattleRecord b = bo.get();
        if (!"WAITING_CHALLENGE".equals(b.status()) || b.defenderId() != player.id()) {
            sendText(chatId, "Этот вызов уже неактуален.");
            return;
        }
        Optional<PlayerRecord> attacker = gameDao.findPlayerById(b.attackerId());
        Optional<PlayerRecord> defender = gameDao.findPlayerById(b.defenderId());
        if (attacker.isEmpty() || defender.isEmpty()) {
            gameDao.finishBattle(b.id(), "Вызов отменён.");
            return;
        }
        if ("accept".equals(p[1])) {
            gameDao.setBattleStatus(b.id(), "WAITING_ACTIONS");
            gameDao.startBattleRound(b.id(), 1, Instant.now().toEpochMilli());
            sendText(defender.get().telegramId(), "✅ Бой принят. Начинаем!");
            sendText(attacker.get().telegramId(), "✅ " + defender.get().villageName() + " принял вызов.");
            sendRoundPrompt(b.id());
            return;
        }
        if ("decline".equals(p[1])) {
            gameDao.finishBattle(b.id(), "Вызов отклонён.");
            sendText(attacker.get().telegramId(), "❌ " + defender.get().villageName() + " отклонил твой вызов.");
            sendText(defender.get().telegramId(), "❌ Ты отклонил вызов.");
        }
    }

    private void handleBattleCallbacks(long chatId, PlayerRecord player, String data) {
        String[] p = data.split(":");
        if (p.length < 4) {
            return;
        }
        long battleId = Long.parseLong(p[2]);
        Optional<BattleRecord> bOpt = gameDao.findBattle(battleId);
        if (bOpt.isEmpty()) {
            sendText(chatId, "Бой не найден", menuService.mainMenu());
            return;
        }
        BattleRecord b = bOpt.get();
        boolean attackerSide = b.attackerId() == player.id();
        if (!attackerSide && b.defenderId() != player.id()) {
            return;
        }
        if (!"WAITING_ACTIONS".equals(b.status()) || b.currentRound() <= 0) {
            sendText(chatId, "Этот бой уже завершён/не активен");
            return;
        }

        String action = p[3];
        if ("ITEM".equals(action)) {
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            if (gameDao.hasInventoryItem(player.id(), "BLUEPRINT_PISTOL")) {
                rows.add(List.of(btn("🔫 Пистолет", "battle:item:" + battleId + ":PISTOL")));
            }
            if (gameDao.hasInventoryItem(player.id(), "BLUEPRINT_CANNON")) {
                rows.add(List.of(btn("💣 Пушка", "battle:item:" + battleId + ":CANNON")));
            }
            rows.add(List.of(btn("◀️ Назад", "battle:act:" + battleId + ":ATTACK")));
            sendText(chatId, "Выбери предмет для хода:", InlineKeyboardMarkup.builder().keyboard(rows).build());
            return;
        }

        if ("item".equals(p[1]) && p.length >= 4) {
            action = "ITEM_" + p[3];
        }

        if ("RETREAT".equals(action)) {
            gameDao.reduceArmyPercent(player.id(), 30);
            long winnerId = attackerSide ? b.defenderId() : b.attackerId();
            gameDao.finishBattle(b.id(), "Один из игроков отступил.");
            sendText(chatId, "🏃 Ты отступил и потерял 30% армии.");
            gameDao.findPlayerById(winnerId).ifPresent(w -> {
                sendText(w.telegramId(), "🏳️ Враг отступил. Ресурсы не получены.");
                if (groupChatId == 0) {
                    log.warn("GROUP_CHAT_ID not configured!");
                } else {
                    gameDao.findPlayerById(b.attackerId()).ifPresent(att ->
                            gameDao.findPlayerById(b.defenderId()).ifPresent(def -> {
                                log.info("Sending battle result to group: {}", groupChatId);
                                String spoils = "🪵0 🪨0 🌾0 💰0";
                                String result = "⚔️ БИТВА\n" +
                                        att.villageName() + " ⚔️ " + def.villageName() + "\n" +
                                        "🏆 Победитель: " + w.villageName() + "\n" +
                                        "💰 Добыча: " + spoils;
                                sendToGroup(
                                        result
                                );
                            })
                    );
                }
            });
            return;
        }

        if (attackerSide) {
            gameDao.setBattleAction(b.id(), true, action);
        } else {
            gameDao.setBattleAction(b.id(), false, action);
        }
        sendText(chatId, "Ход принят: " + actionLabel(action));

        Optional<BattleRecord> updated = gameDao.findBattle(b.id());
        if (updated.isPresent() && updated.get().attackerAction() != null && updated.get().defenderAction() != null) {
            processBattleRound(b.id());
        }
    }

    private void sendRoundPrompt(long battleId) {
        Optional<BattleRecord> bOpt = gameDao.findBattle(battleId);
        if (bOpt.isEmpty()) return;
        BattleRecord b = bOpt.get();
        Optional<PlayerRecord> a = gameDao.findPlayerById(b.attackerId());
        Optional<PlayerRecord> d = gameDao.findPlayerById(b.defenderId());
        if (a.isEmpty() || d.isEmpty()) return;

        String textA = roundPromptText(b, a.get().villageName(), d.get().villageName(), b.attackerHp(), b.attackerMaxHp(), b.defenderHp(), b.defenderMaxHp());
        String textD = roundPromptText(b, d.get().villageName(), a.get().villageName(), b.defenderHp(), b.defenderMaxHp(), b.attackerHp(), b.attackerMaxHp());
        sendText(a.get().telegramId(), textA, battleActionsKeyboard(battleId, a.get().id()));
        sendText(d.get().telegramId(), textD, battleActionsKeyboard(battleId, d.get().id()));
    }

    private String roundPromptText(BattleRecord b, String me, String enemy, int myHp, int myMax, int enemyHp, int enemyMax) {
        return "⚔️ Ход " + b.currentRound() + "/" + b.maxRounds() + "\n━━━━━━━━━━━━━━━\n" +
                me + ": ❤️ " + myHp + " / " + myMax + "\n" +
                enemy + ": ❤️ " + enemyHp + " / " + enemyMax + "\n" +
                "━━━━━━━━━━━━━━━\n" +
                "💡 ⚔️ бьёт 🏹 | 🏹 бьёт 🛡️ | 🛡️ бьёт ⚔️\n" +
                "⏱ Время на ход: 60 секунд\nТвой ход:";
    }

    private InlineKeyboardMarkup battleActionsKeyboard(long battleId, long playerId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(btn("⚔️ Атака", "battle:act:" + battleId + ":ATTACK"), btn("🛡️ Защита", "battle:act:" + battleId + ":DEFEND")));
        rows.add(List.of(btn("🏹 Обстрел", "battle:act:" + battleId + ":BARRAGE"), btn("🏃 Отступить", "battle:act:" + battleId + ":RETREAT")));
        if (gameDao.hasInventoryItem(playerId, "BLUEPRINT_PISTOL") || gameDao.hasInventoryItem(playerId, "BLUEPRINT_CANNON")) {
            rows.add(List.of(btn("🍺 Использовать предмет", "battle:act:" + battleId + ":ITEM")));
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private void processBattleRound(long battleId) {
        Optional<BattleRecord> opt = gameDao.findBattle(battleId);
        if (opt.isEmpty()) return;
        BattleRecord b = opt.get();
        if (b.attackerAction() == null || b.defenderAction() == null) return;

        Optional<PlayerRecord> aOpt = gameDao.findPlayerById(b.attackerId());
        Optional<PlayerRecord> dOpt = gameDao.findPlayerById(b.defenderId());
        if (aOpt.isEmpty() || dOpt.isEmpty()) return;
        PlayerRecord a = aOpt.get();
        PlayerRecord d = dOpt.get();

        int aPower = Math.max(1, b.attackerMaxHp() / 10);
        int dPower = Math.max(1, b.defenderMaxHp() / 10);
        int aHp = b.attackerHp();
        int dHp = b.defenderHp();
        String aAct = b.attackerAction();
        String dAct = b.defenderAction();

        if (aAct.startsWith("ITEM_") && "ITEM_PISTOL".equals(aAct)) {
            gameDao.removeInventoryItem(a.id(), "BLUEPRINT_PISTOL", 1);
        }
        if (aAct.startsWith("ITEM_") && "ITEM_CANNON".equals(aAct)) {
            gameDao.removeInventoryItem(a.id(), "BLUEPRINT_CANNON", 1);
        }
        if (dAct.startsWith("ITEM_") && "ITEM_PISTOL".equals(dAct)) {
            gameDao.removeInventoryItem(d.id(), "BLUEPRINT_PISTOL", 1);
        }
        if (dAct.startsWith("ITEM_") && "ITEM_CANNON".equals(dAct)) {
            gameDao.removeInventoryItem(d.id(), "BLUEPRINT_CANNON", 1);
        }

        double dmgToD = baseDamage(aPower, aAct, a.hasCrossbow());
        double dmgToA = baseDamage(dPower, dAct, d.hasCrossbow());

        double[] duel = duelMultipliers(aAct, dAct);
        dmgToD *= duel[0];
        dmgToA *= duel[1];

        double defMulA = defenseMultiplier(dAct, aAct, a, false);
        double defMulD = defenseMultiplier(aAct, dAct, d, true);

        dHp -= (int) Math.floor(dmgToD * defMulD);
        aHp -= (int) Math.floor(dmgToA * defMulA);

        aHp = Math.max(0, aHp);
        dHp = Math.max(0, dHp);

        String resultLine = "Ход " + b.currentRound() + ": " + actionLabel(aAct) + " vs " + actionLabel(dAct) + " | урон -> " +
                a.villageName() + " -" + (b.attackerHp() - aHp) + ", " + d.villageName() + " -" + (b.defenderHp() - dHp);

        gameDao.setBattleHp(b.id(), aHp, dHp, resultLine);

        sendRoundResultToPlayers(
                b, a, d, aHp, dHp,
                actionLabel(aAct), actionLabel(dAct),
                resultLine
        );

        boolean finished = aHp <= 0 || dHp <= 0 || b.currentRound() >= b.maxRounds();
        if (finished) {
            finishBattleAndReward(b.id(), a, d, aHp, dHp);
            return;
        }

        gameDao.startBattleRound(b.id(), b.currentRound() + 1, Instant.now().toEpochMilli());
        sendRoundPrompt(b.id());
    }

    private void sendRoundResultToPlayers(
            BattleRecord b,
            PlayerRecord attacker,
            PlayerRecord defender,
            int attackerHp,
            int defenderHp,
            String attackerAction,
            String defenderAction,
            String result
    ) {
        String attackerText = "⚔️ Ход " + b.currentRound() + "/" + b.maxRounds() + "\n" +
                "━━━━━━━━━━━━━━━\n" +
                attacker.villageName() + ": ❤️ " + attackerHp + " / " + b.attackerMaxHp() + "\n" +
                defender.villageName() + ": ❤️ " + defenderHp + " / " + b.defenderMaxHp() + "\n" +
                "━━━━━━━━━━━━━━━\n" +
                "Ты выбрал: " + attackerAction + "\n" +
                "Враг выбрал: " + defenderAction + "\n" +
                "Результат: " + result + "\n" +
                "━━━━━━━━━━━━━━━\n" +
                "⏱ Время на ход: 60 секунд\n" +
                "Твой ход:";

        String defenderText = "⚔️ Ход " + b.currentRound() + "/" + b.maxRounds() + "\n" +
                "━━━━━━━━━━━━━━━\n" +
                defender.villageName() + ": ❤️ " + defenderHp + " / " + b.defenderMaxHp() + "\n" +
                attacker.villageName() + ": ❤️ " + attackerHp + " / " + b.attackerMaxHp() + "\n" +
                "━━━━━━━━━━━━━━━\n" +
                "Ты выбрал: " + defenderAction + "\n" +
                "Враг выбрал: " + attackerAction + "\n" +
                "Результат: " + result + "\n" +
                "━━━━━━━━━━━━━━━\n" +
                "⏱ Время на ход: 60 секунд\n" +
                "Твой ход:";

        sendText(attacker.telegramId(), attackerText);
        sendText(defender.telegramId(), defenderText);
    }

    private double baseDamage(int power, String action, boolean crossbowEquipped) {
        return switch (action) {
            case "ATTACK" -> power * 0.20;
            case "BARRAGE" -> power * (crossbowEquipped ? 0.18 : 0.15);
            case "ITEM_PISTOL" -> power * 0.26;
            default -> 0.0;
        };
    }

    private double defenseMultiplier(String myAction, String enemyAction, PlayerRecord me, boolean defenderSide) {
        double m = 1.0;
        if ("DEFEND".equals(myAction)) {
            m *= 0.5;
        }
        if ("ITEM_CANNON".equals(myAction)) {
            m *= 0.3;
        }
        double armorBonus = armorBonus(me.equippedArmor());
        if (armorBonus > 0) {
            m *= (1.0 - armorBonus);
        }
        if (defenderSide && me.hasCannon()) {
            m *= 0.7;
        }
        return m;
    }

    private double[] duelMultipliers(String aAct, String dAct) {
        String aNorm = normalizeActionForDuel(aAct);
        String dNorm = normalizeActionForDuel(dAct);
        if (aNorm.equals(dNorm)) {
            return new double[]{1.0, 1.0};
        }
        if (beats(aNorm, dNorm)) {
            return new double[]{1.3, 0.7};
        }
        if (beats(dNorm, aNorm)) {
            return new double[]{0.7, 1.3};
        }
        return new double[]{1.0, 1.0};
    }

    private boolean beats(String left, String right) {
        return ("ATTACK".equals(left) && "BARRAGE".equals(right))
                || ("BARRAGE".equals(left) && "DEFEND".equals(right))
                || ("DEFEND".equals(left) && "ATTACK".equals(right));
    }

    private String normalizeActionForDuel(String action) {
        if (action == null) return "";
        return switch (action) {
            case "ITEM_PISTOL" -> "ATTACK";
            case "ITEM_CANNON" -> "DEFEND";
            default -> action;
        };
    }

    private void finishBattleAndReward(long battleId, PlayerRecord a, PlayerRecord d, int aHp, int dHp) {
        long winnerId = aHp >= dHp ? a.id() : d.id();
        PlayerRecord winner = winnerId == a.id() ? a : d;
        PlayerRecord loser = winnerId == a.id() ? d : a;

        int stolenWood = 0;
        int stolenStone = 0;
        int stolenFood = 0;
        int stolenIron = 0;
        int stolenGold = 0;
        int stolenMana = 0;
        int stolenAlcohol = 0;
        if (aHp != dHp) {
            ResourcesRecord before = gameDao.loadResources(loser.id());
            double lootMul = activeDailyEvent() == DailyEventType.WAR_DAY ? 1.10 : 1.0;
            stolenWood = (int) Math.floor(before.wood() * 0.30 * lootMul);
            stolenStone = (int) Math.floor(before.stone() * 0.30 * lootMul);
            stolenFood = (int) Math.floor(before.food() * 0.30 * lootMul);
            stolenIron = (int) Math.floor(before.iron() * 0.30 * lootMul);
            stolenGold = (int) Math.floor(before.gold() * 0.30 * lootMul);
            stolenMana = (int) Math.floor(before.mana() * 0.30 * lootMul);
            stolenAlcohol = (int) Math.floor(before.alcohol() * 0.30 * lootMul);
            GameCatalog.Cost loot = new GameCatalog.Cost(stolenWood, stolenStone, stolenFood, stolenIron, stolenGold, stolenMana, stolenAlcohol);
            if (gameDao.spendResources(loser.id(), loot)) {
                gameDao.addResources(winner.id(), loot);
            }
        }
        Optional<BattleRecord> battle = gameDao.findBattle(battleId);
        int attackerMaxHp = battle.map(BattleRecord::attackerMaxHp).orElse(Math.max(1, aHp));
        int defenderMaxHp = battle.map(BattleRecord::defenderMaxHp).orElse(Math.max(1, dHp));
        int attackerReceived = Math.max(0, attackerMaxHp - aHp);
        int defenderReceived = Math.max(0, defenderMaxHp - dHp);
        int attackerLossPercent = Math.max(0, Math.min(100, (int) Math.ceil(attackerReceived * 100.0 / Math.max(1, attackerMaxHp))));
        int defenderLossPercent = Math.max(0, Math.min(100, (int) Math.ceil(defenderReceived * 100.0 / Math.max(1, defenderMaxHp))));
        List<GameDao.ArmyLoss> attackerLosses = gameDao.applyArmyLossPercent(a.id(), attackerLossPercent);
        List<GameDao.ArmyLoss> defenderLosses = gameDao.applyArmyLossPercent(d.id(), defenderLossPercent);

        String finalLine = "Итог: победитель " + winner.villageName() + ", трофеи: 🪵" + stolenWood + " 🪨" + stolenStone + " 🌾" + stolenFood + " 💰" + stolenGold;
        gameDao.finishBattle(battleId, finalLine);
        int attackerPower = Math.max(1, getPlayerPower(a.id()));
        int defenderPower = Math.max(1, getPlayerPower(d.id()));
        gameDao.logBattle(a.id(), d.id(), attackerPower, defenderPower, winner.id(), stolenGold);
        gameDao.appendDailyLog("BATTLE", a.villageName() + " vs " + d.villageName() + " -> победа " + winner.villageName());

        sendText(a.telegramId(), buildBattleSummary(
                a,
                winner.id() == a.id(),
                attackerLosses,
                getPlayerPower(a.id()),
                winner.id() == a.id(),
                stolenWood, stolenStone, stolenFood, stolenIron, stolenGold, stolenMana, stolenAlcohol
        ));
        sendText(d.telegramId(), buildBattleSummary(
                d,
                winner.id() == d.id(),
                defenderLosses,
                getPlayerPower(d.id()),
                winner.id() == d.id(),
                stolenWood, stolenStone, stolenFood, stolenIron, stolenGold, stolenMana, stolenAlcohol
        ));
        if (getPlayerPower(a.id()) <= 0) {
            sendText(a.telegramId(), "💀 Твоя армия полностью уничтожена!\nНайми новых воинов чтобы снова атаковать.");
        }
        if (getPlayerPower(d.id()) <= 0) {
            sendText(d.telegramId(), "💀 Твоя армия полностью уничтожена!\nНайми новых воинов чтобы снова атаковать.");
        }

        if (groupChatId == 0) {
            log.warn("GROUP_CHAT_ID not configured!");
        } else {
            log.info("Sending battle result to group: {}", groupChatId);
            String result = "⚔️ БИТВА\n" +
                    a.villageName() + " ⚔️ " + d.villageName() + "\n" +
                    "🏆 Победитель: " + winner.villageName() + "\n" +
                    "💀 Потери победителя: " + (winner.id() == a.id() ? attackerLossPercent : defenderLossPercent) + "%\n" +
                    "💀 Потери проигравшего: " + (winner.id() == a.id() ? defenderLossPercent : attackerLossPercent) + "%";
            sendToGroup(
                    result
            );
            int totalLoss = stolenWood + stolenStone + stolenFood + stolenIron + stolenGold + stolenMana + stolenAlcohol;
            if (totalLoss > 500) {
                sendToGroup(
                        "💀 РАЗГРОМ!\n" +
                                loser.villageName() + " потерял огромное количество ресурсов в бою с " + winner.villageName() + "\n" +
                                "Потери: 🪵" + stolenWood + " 🪨" + stolenStone + " 💰" + stolenGold
                );
            }
        }
    }

    private double baseAttackChance(int aPow, int dPow) {
        if (dPow <= 0) {
            return 85.0;
        }
        if (aPow <= 0) {
            return 0.0;
        }
        return ((double) aPow / (aPow + dPow)) * 100.0;
    }

    private void resolveQuickRaid(long battleId, PlayerRecord attacker, PlayerRecord defender, boolean fromChallengeTimeout, String raidItem) {
        int aPow = getPlayerPower(attacker.id());
        int dPow = getPlayerPower(defender.id());
        if (aPow <= 0) {
            gameDao.finishBattle(battleId, "Быстрый набег отменён: у атакующего нет армии.");
            sendText(attacker.telegramId(), "⚠️ Быстрый набег отменён: у тебя нет армии.");
            return;
        }

        if (!"NONE".equals(raidItem)) {
            boolean hadItem = switch (raidItem) {
                case "PISTOL" -> gameDao.inventoryQuantity(attacker.id(), "CRAFTED_PISTOL") > 0;
                case "CANNON" -> gameDao.inventoryQuantity(attacker.id(), "CRAFTED_CANNON") > 0;
                case "CROSSBOW" -> gameDao.inventoryQuantity(attacker.id(), "CRAFTED_CROSSBOW") > 0;
                default -> false;
            };
            if (!hadItem) {
                raidItem = "NONE";
            } else {
                consumeRaidItem(attacker.id(), raidItem);
            }
        }
        double chance = raidWinChance(attacker, defender, raidItem);
        boolean attackerWin = ThreadLocalRandom.current().nextDouble(0.0, 100.0) < chance;
        PlayerRecord winner = attackerWin ? attacker : defender;
        PlayerRecord loser = attackerWin ? defender : attacker;
        int winnerLossPercent = rand(10, 20);
        int loserLossPercent = rand(30, 50);
        List<GameDao.ArmyLoss> winnerLosses = gameDao.applyArmyLossPercent(winner.id(), winnerLossPercent);
        List<GameDao.ArmyLoss> loserLosses = gameDao.applyArmyLossPercent(loser.id(), loserLossPercent);

        int lootWood = 0;
        int lootStone = 0;
        int lootFood = 0;
        int lootGold = 0;
        if (attackerWin) {
            ResourcesRecord before = gameDao.loadResources(defender.id());
            lootWood = (int) Math.floor(before.wood() * 0.20);
            lootStone = (int) Math.floor(before.stone() * 0.20);
            lootFood = (int) Math.floor(before.food() * 0.20);
            lootGold = (int) Math.floor(before.gold() * 0.20);
            GameCatalog.Cost loot = new GameCatalog.Cost(lootWood, lootStone, lootFood, 0, lootGold, 0, 0);
            if (gameDao.spendResources(defender.id(), loot)) {
                gameDao.addResources(attacker.id(), loot);
            }
        }

        gameDao.finishBattle(battleId, "Авто бой завершён. Победитель: " + winner.villageName());
        gameDao.logBattle(attacker.id(), defender.id(), aPow, dPow, winner.id(), lootGold);
        gameDao.appendDailyLog("BATTLE", attacker.villageName() + " vs " + defender.villageName() + " -> победа " + winner.villageName() + " (авто)");

        sendText(attacker.telegramId(), buildBattleSummary(
                attacker,
                winner.id() == attacker.id(),
                winner.id() == attacker.id() ? winnerLosses : loserLosses,
                getPlayerPower(attacker.id()),
                winner.id() == attacker.id(),
                lootWood, lootStone, lootFood, 0, lootGold, 0, 0
        ));
        sendText(defender.telegramId(), buildBattleSummary(
                defender,
                winner.id() == defender.id(),
                winner.id() == defender.id() ? winnerLosses : loserLosses,
                getPlayerPower(defender.id()),
                winner.id() == defender.id(),
                lootWood, lootStone, lootFood, 0, lootGold, 0, 0
        ));
        if (getPlayerPower(attacker.id()) <= 0) {
            sendText(attacker.telegramId(), "💀 Твоя армия полностью уничтожена!\nНайми новых воинов чтобы снова атаковать.");
        }
        if (getPlayerPower(defender.id()) <= 0) {
            sendText(defender.telegramId(), "💀 Твоя армия полностью уничтожена!\nНайми новых воинов чтобы снова атаковать.");
        }

        String type = fromChallengeTimeout ? "Авто бой (таймаут вызова)" : "Авто бой";
        log.info("Sending battle result to group: {}", groupChatId);
        String result = "⚔️ БИТВА\n" +
                attacker.villageName() + " ⚔️ " + defender.villageName() + "\n" +
                "🏆 Победитель: " + winner.villageName() + "\n" +
                "💀 Потери победителя: " + winnerLossPercent + "%\n" +
                "💀 Потери проигравшего: " + loserLossPercent + "%\n" +
                "Тип: " + type;
        sendToGroup(
                result
        );
        int sumLoss = lootWood + lootStone + lootFood + lootGold;
        if (sumLoss > 500) {
            sendToGroup(
                    "💀 РАЗГРОМ!\n" +
                            loser.villageName() + " потерял огромное количество ресурсов в бою с " + winner.villageName() + "\n" +
                            "Потери: 🪵" + lootWood + " 🪨" + lootStone + " 💰" + lootGold
            );
        }
    }

    private void processAllianceAttackSessions(long now) {
        List<Long> ready = new ArrayList<>();
        for (Map.Entry<Long, AllianceAttackSession> e : allianceAttackSessions.entrySet()) {
            if (now >= e.getValue().deadlineAt) {
                ready.add(e.getKey());
            }
        }
        for (Long id : ready) {
            AllianceAttackSession s = allianceAttackSessions.remove(id);
            if (s == null) continue;
            Optional<PlayerRecord> leader = gameDao.findPlayerById(s.leaderId);
            Optional<PlayerRecord> target = gameDao.findPlayerById(s.targetId);
            Optional<GameDao.AllianceInfo> alliance = gameDao.findAllianceById(s.allianceId);
            if (leader.isEmpty() || target.isEmpty() || alliance.isEmpty()) {
                continue;
            }

            int attackPower = 0;
            for (Long pid : s.joined) {
                attackPower += getPlayerPower(pid);
            }
            int defendPower = getPlayerPower(target.get().id());
            double chance = baseAttackChance(Math.max(1, attackPower), Math.max(1, defendPower));
            boolean allianceWin = ThreadLocalRandom.current().nextDouble(0.0, 100.0) < chance;

            int lootWood = 0;
            int lootStone = 0;
            int lootFood = 0;
            int lootGold = 0;
            if (allianceWin && !s.joined.isEmpty()) {
                ResourcesRecord before = gameDao.loadResources(target.get().id());
                lootWood = (int) Math.floor(before.wood() * 0.20);
                lootStone = (int) Math.floor(before.stone() * 0.20);
                lootFood = (int) Math.floor(before.food() * 0.20);
                lootGold = (int) Math.floor(before.gold() * 0.20);
                GameCatalog.Cost totalLoot = new GameCatalog.Cost(lootWood, lootStone, lootFood, 0, lootGold, 0, 0);
                if (gameDao.spendResources(target.get().id(), totalLoot)) {
                    int n = Math.max(1, s.joined.size());
                    GameCatalog.Cost share = new GameCatalog.Cost(lootWood / n, lootStone / n, lootFood / n, 0, lootGold / n, 0, 0);
                    for (Long pid : s.joined) {
                        gameDao.addResources(pid, share);
                    }
                }
            }

            String winner = allianceWin ? "Альянс " + alliance.get().name() : target.get().villageName();
            final int finalLootWood = lootWood;
            final int finalLootStone = lootStone;
            final int finalLootFood = lootFood;
            final int finalLootGold = lootGold;
            for (Long pid : s.joined) {
                gameDao.findPlayerById(pid).ifPresent(p ->
                        sendText(p.telegramId(),
                                "⚔️ Совместная атака завершена.\n" +
                                        "Победитель: " + winner + "\n" +
                                        "Добыча: 🪵" + finalLootWood + " 🪨" + finalLootStone + " 🌾" + finalLootFood + " 💰" + finalLootGold));
            }
            sendText(target.get().telegramId(), "🚨 На тебя напал альянс " + alliance.get().name() + ". Победитель: " + winner);
            if (attackPower > 50) {
                sendGroupMessageAsync(
                        "⚔️ СОВМЕСТНАЯ АТАКА\n" +
                                "Альянс " + alliance.get().name() + " напал на " + target.get().villageName() + "!\n" +
                                "Общая мощь: " + attackPower + " vs " + defendPower + "\n" +
                                "Победитель: " + winner
                );
            }
        }
    }

    private void showInventory(long chatId, PlayerRecord player) {
        List<KeyValueAmount> inv = gameDao.loadInventory(player.id());
        if (inv.isEmpty()) {
            sendText(chatId, "🎒 Инвентарь пуст", menuService.mainMenu());
            return;
        }
        StringBuilder sb = new StringBuilder("🎒 Инвентарь\n");
        if (player.equippedArmor() != null) {
            sb.append("Надето: ").append(catalog.itemDisplay(player.equippedArmor())).append("\n\n");
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        inv.stream().sorted(Comparator.comparing(KeyValueAmount::type)).forEach(it -> {
            sb.append("- ").append(catalog.itemDisplay(it.type())).append(": ").append(it.quantity()).append("\n");
            if (isArmor(it.type()) && it.quantity() > 0) {
                rows.add(List.of(btn("🛡️ Надеть " + catalog.itemDisplay(it.type()), "inv:equip:armor:" + it.type())));
            }
        });
        rows.add(List.of(btn("◀️ Назад", "menu:city")));
        sendText(chatId, sb.toString(), InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private void handleInventoryCallbacks(long chatId, PlayerRecord player, String data) {
        if (data.startsWith("inv:equip:armor:")) {
            String armor = data.substring("inv:equip:armor:".length());
            if (!isArmor(armor) || !gameDao.hasInventoryItem(player.id(), armor)) {
                sendText(chatId, "Этой брони нет в инвентаре.", menuService.mainMenu());
                return;
            }
            if (player.equippedArmor() != null && armorBonus(player.equippedArmor()) > armorBonus(armor)) {
                sendText(chatId,
                        "⚠️ У тебя уже есть " + catalog.itemDisplay(player.equippedArmor()) + " (лучше). Всё равно надеть?",
                        InlineKeyboardMarkup.builder()
                                .keyboardRow(List.of(btn("✅ Да", "inv:equip:force:" + armor), btn("❌ Нет", "menu:inventory")))
                                .build());
                return;
            }
            gameDao.setEquippedArmor(player.id(), armor);
            sendText(chatId, "✅ Надета " + catalog.itemDisplay(armor) + ". Защита +" + (int) (armorBonus(armor) * 100) + "%", menuService.mainMenu());
            return;
        }
        if (data.startsWith("inv:equip:force:")) {
            String armor = data.substring("inv:equip:force:".length());
            if (isArmor(armor) && gameDao.hasInventoryItem(player.id(), armor)) {
                gameDao.setEquippedArmor(player.id(), armor);
                sendText(chatId, "✅ Надета " + catalog.itemDisplay(armor) + ".", menuService.mainMenu());
            } else {
                sendText(chatId, "Этой брони нет в инвентаре.", menuService.mainMenu());
            }
        }
    }

    private void showCraftMenu(long chatId, PlayerRecord player) {
        ResourcesRecord r = gameDao.loadResources(player.id());
        int bpPistol = gameDao.inventoryQuantity(player.id(), "BLUEPRINT_PISTOL");
        int bpCannon = gameDao.inventoryQuantity(player.id(), "BLUEPRINT_CANNON");
        int bpCrossbow = gameDao.inventoryQuantity(player.id(), "BLUEPRINT_CROSSBOW");

        StringBuilder txt = new StringBuilder("🔨 МАСТЕРСКАЯ\n━━━━━━━━━━━━\n📜 Твои чертежи:\n");
        txt.append("- Чертёж пистолета х").append(bpPistol).append("\n");
        txt.append("- Чертёж пушки х").append(bpCannon).append(bpCannon > 0 ? "" : " 🔒").append("\n");
        txt.append("- Чертёж усиленного арбалета х").append(bpCrossbow).append(bpCrossbow > 0 ? "" : " 🔒").append("\n\n");

        txt.append("🔫 Пистолет\nНужно: ⚔️50 💰30\nУ тебя: ⚔️").append(r.iron()).append(" 💰").append(r.gold()).append("\n\n");
        txt.append("💣 Пушка\nНужно: 🪨100 ⚔️80 🪵60\n");
        txt.append(bpCannon > 0 ? "" : "🔒 Нужен чертёж пушки\n");
        txt.append("\n🏹 Усиленный арбалет\nНужно: 🪵80 ⚔️40\n");
        txt.append(bpCrossbow > 0 ? "" : "🔒 Нужен чертёж усиленного арбалета\n");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (bpPistol > 0 && r.iron() >= 50 && r.gold() >= 30) rows.add(List.of(btn("🔨 Скрафтить пистолет", "craft:make:PISTOL")));
        if (bpCannon > 0 && r.stone() >= 100 && r.iron() >= 80 && r.wood() >= 60) rows.add(List.of(btn("🔨 Скрафтить пушку", "craft:make:CANNON")));
        if (bpCrossbow > 0 && r.wood() >= 80 && r.iron() >= 40) rows.add(List.of(btn("🔨 Скрафтить арбалет", "craft:make:CROSSBOW")));
        rows.add(List.of(btn("◀️ Назад", "menu:city")));
        sendText(chatId, txt.toString(), InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private void handleCraftCallbacks(long chatId, PlayerRecord player, String data) {
        String[] p = data.split(":");
        if (p.length < 3 || !"make".equals(p[1])) {
            showCraftMenu(chatId, player);
            return;
        }
        switch (p[2]) {
            case "PISTOL" -> {
                if (gameDao.inventoryQuantity(player.id(), "BLUEPRINT_PISTOL") <= 0) {
                    sendText(chatId, "Нужен чертёж пистолета.", menuService.mainMenu());
                    return;
                }
                if (!gameDao.spendResources(player.id(), new GameCatalog.Cost(0, 0, 0, 50, 30, 0, 0))) {
                    sendText(chatId, "Недостаточно ресурсов.", menuService.mainMenu());
                    return;
                }
                gameDao.addInventoryItem(player.id(), "CRAFTED_PISTOL", 1);
                sendText(chatId, "✅ Скрафчен: 🔫 Пистолет", menuService.mainMenu());
            }
            case "CANNON" -> {
                if (gameDao.inventoryQuantity(player.id(), "BLUEPRINT_CANNON") <= 0) {
                    sendText(chatId, "Нужен чертёж пушки.", menuService.mainMenu());
                    return;
                }
                if (!gameDao.spendResources(player.id(), new GameCatalog.Cost(60, 100, 0, 80, 0, 0, 0))) {
                    sendText(chatId, "Недостаточно ресурсов.", menuService.mainMenu());
                    return;
                }
                gameDao.addInventoryItem(player.id(), "CRAFTED_CANNON", 1);
                sendText(chatId, "✅ Скрафчена: 💣 Пушка", menuService.mainMenu());
            }
            case "CROSSBOW" -> {
                if (gameDao.inventoryQuantity(player.id(), "BLUEPRINT_CROSSBOW") <= 0) {
                    sendText(chatId, "Нужен чертёж усиленного арбалета.", menuService.mainMenu());
                    return;
                }
                if (!gameDao.spendResources(player.id(), new GameCatalog.Cost(80, 0, 0, 40, 0, 0, 0))) {
                    sendText(chatId, "Недостаточно ресурсов.", menuService.mainMenu());
                    return;
                }
                gameDao.addInventoryItem(player.id(), "CRAFTED_CROSSBOW", 1);
                sendText(chatId, "✅ Скрафчен: 🏹 Усиленный арбалет", menuService.mainMenu());
            }
            default -> showCraftMenu(chatId, player);
        }
    }

    private void maybeDropBlueprint(PlayerRecord player) {
        if (ThreadLocalRandom.current().nextDouble() >= 0.01) {
            return;
        }
        String[] blueprints = {"BLUEPRINT_PISTOL", "BLUEPRINT_CANNON", "BLUEPRINT_CROSSBOW"};
        String found = blueprints[ThreadLocalRandom.current().nextInt(blueprints.length)];
        gameDao.appendDailyLog("RARE", player.villageName() + " нашёл " + catalog.itemDisplay(found));
        pendingLootByPlayer.put(player.id(), found);
        sendText(player.telegramId(),
                "📜 Ты нашёл чертёж: " + catalog.itemDisplay(found) + "!\n[ 🔨 Сохранить для крафта ] [ 💰 Продать ] [ 🔨 Аукцион ]",
                InlineKeyboardMarkup.builder()
                        .keyboardRow(List.of(btn("🔨 Сохранить для крафта", "loot:save:" + found), btn("💰 Продать", "loot:sell:" + found)))
                        .keyboardRow(List.of(btn("🔨 Аукцион", "loot:auction:" + found)))
                        .build());
    }

    private void maybeDropArmor(PlayerRecord player) {
        double roll = ThreadLocalRandom.current().nextDouble();
        String found = null;
        if (roll < 0.001) found = "NETHERITE_ARMOR";
        else if (roll < 0.006) found = "DIAMOND_ARMOR";
        else if (roll < 0.016) found = "GOLD_ARMOR";
        else if (roll < 0.046) found = "IRON_ARMOR";
        else if (roll < 0.096) found = "CHAINMAIL_ARMOR";
        else if (roll < 0.196) found = "LEATHER_ARMOR";
        if (found == null) return;

        gameDao.appendDailyLog("RARE", player.villageName() + " нашёл " + catalog.itemDisplay(found));
        pendingLootByPlayer.put(player.id(), found);
        sendText(player.telegramId(),
                "🎉 Ты нашёл " + catalog.itemDisplay(found) + "!\n" +
                        "Бонус защиты: +" + (int) (armorBonus(found) * 100) + "%\n" +
                        "[ ✅ Надеть ] [ 💰 Продать ] [ 🔨 Аукцион ]",
                InlineKeyboardMarkup.builder()
                        .keyboardRow(List.of(btn("✅ Надеть", "loot:equip:" + found), btn("💰 Продать", "loot:sell:" + found)))
                        .keyboardRow(List.of(btn("🔨 Аукцион", "loot:auction:" + found)))
                        .build());

        if ("GOLD_ARMOR".equals(found) || "DIAMOND_ARMOR".equals(found) || "NETHERITE_ARMOR".equals(found)) {
            sendGroupMessageAsync("🛡️ РЕДКАЯ БРОНЯ\n" + player.villageName() + " нашёл " + catalog.itemDisplay(found) + "!");
        }
    }

    private void handleLootCallbacks(long chatId, PlayerRecord player, String data) {
        String[] p = data.split(":");
        if (p.length < 3) return;
        String action = p[1];
        String item = p[2];
        String pending = pendingLootByPlayer.get(player.id());
        if (pending == null || !pending.equals(item)) {
            sendText(chatId, "Эта находка уже неактуальна.", menuService.mainMenu());
            return;
        }

        if ("save".equals(action)) {
            gameDao.addInventoryItem(player.id(), item, 1);
            pendingLootByPlayer.remove(player.id());
            sendText(chatId, "✅ Предмет сохранён: " + catalog.itemDisplay(item), menuService.mainMenu());
            return;
        }
        if ("sell".equals(action)) {
            int price = sellPrice(item);
            gameDao.addResources(player.id(), new GameCatalog.Cost(0, 0, 0, 0, price, 0, 0));
            pendingLootByPlayer.remove(player.id());
            sendText(chatId, "💰 Продано за " + price + " золота.", menuService.mainMenu());
            return;
        }
        if ("auction".equals(action)) {
            int startPrice = Math.max(100, sellPrice(item));
            gameDao.createAuction(player.id(), item, startPrice, Instant.now().plusSeconds(12 * 3600L).toEpochMilli());
            pendingLootByPlayer.remove(player.id());
            sendText(chatId, "🔨 Лот выставлен на аукцион. Старт: " + startPrice + "💰", menuService.mainMenu());
            return;
        }
        if ("equip".equals(action) && isArmor(item)) {
            if (player.equippedArmor() != null && armorBonus(player.equippedArmor()) > armorBonus(item)) {
                sendText(chatId,
                        "⚠️ У тебя уже есть " + catalog.itemDisplay(player.equippedArmor()) + " (лучше). Всё равно надеть?",
                        InlineKeyboardMarkup.builder()
                                .keyboardRow(List.of(btn("✅ Да", "loot:equip_force:" + item), btn("❌ Нет", "menu:inventory")))
                                .build());
                return;
            }
            gameDao.addInventoryItem(player.id(), item, 1);
            gameDao.setEquippedArmor(player.id(), item);
            pendingLootByPlayer.remove(player.id());
            sendText(chatId, "✅ Надета " + catalog.itemDisplay(item) + ".", menuService.mainMenu());
            return;
        }
        if ("equip_force".equals(action) && isArmor(item)) {
            gameDao.addInventoryItem(player.id(), item, 1);
            gameDao.setEquippedArmor(player.id(), item);
            pendingLootByPlayer.remove(player.id());
            sendText(chatId, "✅ Надета " + catalog.itemDisplay(item) + ".", menuService.mainMenu());
        }
    }

    private void showRaidItemSelector(long chatId, PlayerRecord attacker, PlayerRecord defender) {
        int aPow = getPlayerPower(attacker.id());
        int dPow = getPlayerPower(defender.id());
        double base = raidWinChance(attacker, defender, "NONE");
        StringBuilder text = new StringBuilder("🎲 БЫСТРЫЙ НАБЕГ\n━━━━━━━━━━━━\n");
        text.append("Цель: ").append(defender.villageName()).append(" | Мощь: ").append(dPow).append("\n");
        text.append("Твоя мощь: ").append(aPow).append("\n");
        text.append("Базовый шанс победы: ").append(String.format("%.1f", base)).append("%\n\n");
        text.append("🎒 Использовать предмет?\n(предмет тратится после боя)");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (gameDao.inventoryQuantity(attacker.id(), "CRAFTED_PISTOL") > 0) rows.add(List.of(btn("🔫 Пистолет +15% урон", "attack:raiditem:" + defender.id() + ":PISTOL")));
        if (gameDao.inventoryQuantity(attacker.id(), "CRAFTED_CANNON") > 0) rows.add(List.of(btn("💣 Пушка +20% защита", "attack:raiditem:" + defender.id() + ":CANNON")));
        if (gameDao.inventoryQuantity(attacker.id(), "CRAFTED_CROSSBOW") > 0) rows.add(List.of(btn("🏹 Арбалет +10% шанс", "attack:raiditem:" + defender.id() + ":CROSSBOW")));
        if (attacker.equippedArmor() != null) rows.add(List.of(btn(catalog.itemDisplay(attacker.equippedArmor()) + " +" + (int) (armorBonus(attacker.equippedArmor()) * 100) + "% защита", "attack:raiditem:" + defender.id() + ":NONE")));
        rows.add(List.of(btn("❌ Без предмета", "attack:raiditem:" + defender.id() + ":NONE")));
        sendText(chatId, text.toString(), InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private double raidWinChance(PlayerRecord attacker, PlayerRecord defender, String raidItem) {
        double attEff = Math.max(1, getPlayerPower(attacker.id())) * (1.0 + armorBonus(attacker.equippedArmor()));
        double defEff = Math.max(1, getPlayerPower(defender.id())) * (1.0 + armorBonus(defender.equippedArmor()));

        if ("PISTOL".equals(raidItem)) attEff *= 1.15;
        if ("CANNON".equals(raidItem)) attEff *= 1.20;
        if ("CROSSBOW".equals(raidItem)) attEff *= 1.10;
        double chance = (attEff / (attEff + defEff)) * 100.0;
        return Math.max(3.0, Math.min(97.0, chance));
    }

    private void consumeRaidItem(long playerId, String item) {
        switch (item) {
            case "PISTOL" -> gameDao.removeInventoryItem(playerId, "CRAFTED_PISTOL", 1);
            case "CANNON" -> gameDao.removeInventoryItem(playerId, "CRAFTED_CANNON", 1);
            case "CROSSBOW" -> gameDao.removeInventoryItem(playerId, "CRAFTED_CROSSBOW", 1);
            default -> {
            }
        }
    }

    private String raidItemLabel(String item) {
        return switch (item) {
            case "PISTOL" -> "Пистолет";
            case "CANNON" -> "Пушка";
            case "CROSSBOW" -> "Усиленный арбалет";
            default -> "Без предмета";
        };
    }

    private void showAllianceMenu(long chatId, PlayerRecord player) {
        Optional<GameDao.AllianceInfo> alliance = gameDao.findAllianceByPlayerId(player.id());
        if (alliance.isEmpty()) {
            sendText(chatId,
                    "🤝 АЛЬЯНСЫ\nУ тебя нет альянса.",
                    InlineKeyboardMarkup.builder()
                            .keyboardRow(List.of(btn("⚔️ Создать альянс", "alliance:create"), btn("📩 Вступить по приглашению", "alliance:join")))
                            .keyboardRow(List.of(btn("◀️ Назад", "menu:city")))
                            .build());
            return;
        }
        GameDao.AllianceInfo a = alliance.get();
        List<GameDao.AllianceMemberInfo> members = gameDao.allianceMembers(a.id());
        int totalPower = gameDao.allianceTotalPower(a.id(), catalog);
        String leaderVillage = gameDao.findPlayerById(a.leaderId()).map(PlayerRecord::villageName).orElse("—");
        StringBuilder txt = new StringBuilder("🤝 ").append(a.name()).append("\n");
        txt.append("👑 Лидер: ").append(leaderVillage).append("\n");
        txt.append("👥 Участники: ").append(members.size()).append("\n━━━━━━━━━━━━\n");
        for (GameDao.AllianceMemberInfo m : members) {
            txt.append(m.leader() ? "👑 " : "• ").append(m.villageName()).append(" | ⚔️ ").append(getPlayerPower(m.playerId())).append("\n");
        }
        txt.append("━━━━━━━━━━━━\nОбщая мощь: ").append(totalPower);

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (a.leaderId() == player.id()) {
            rows.add(List.of(btn("⚔️ Совместная атака", "alliance:coop"), btn("📩 Пригласить", "alliance:invite")));
        }
        rows.add(List.of(btn("🚪 Выйти", "alliance:leave")));
        rows.add(List.of(btn("◀️ Назад", "menu:city")));
        sendText(chatId, txt.toString(), InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private void handleAllianceCallbacks(long chatId, PlayerRecord player, String data) {
        String[] p = data.split(":");
        if (p.length < 2) {
            showAllianceMenu(chatId, player);
            return;
        }
        switch (p[1]) {
            case "create" -> {
                if (gameDao.findAllianceByPlayerId(player.id()).isPresent()) {
                    sendText(chatId, "Ты уже в альянсе.", menuService.mainMenu());
                    return;
                }
                gameDao.setPlayerState(player.id(), "WAITING_ALLIANCE_NAME", 1);
                sendText(chatId, "Введи название альянса (макс 20 символов). Создание стоит 200💰.");
            }
            case "join" -> {
                List<GameDao.AllianceInviteInfo> invites = gameDao.pendingAllianceInvites(player.id());
                if (invites.isEmpty()) {
                    sendText(chatId, "У тебя нет приглашений.", menuService.mainMenu());
                    return;
                }
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                StringBuilder sb = new StringBuilder("📩 Приглашения в альянсы:\n");
                for (GameDao.AllianceInviteInfo inv : invites) {
                    int totalPower = gameDao.allianceTotalPower(inv.allianceId(), catalog);
                    sb.append("- ").append(inv.allianceName()).append(" (от ").append(inv.inviterVillage()).append(") | ⚔️ ").append(totalPower).append("\n");
                    rows.add(List.of(btn("✅ " + inv.allianceName(), "alliance:invite_accept:" + inv.id()), btn("❌ Отклонить", "alliance:invite_decline:" + inv.id())));
                }
                rows.add(List.of(btn("◀️ Назад", "menu:alliance")));
                sendText(chatId, sb.toString(), InlineKeyboardMarkup.builder().keyboard(rows).build());
            }
            case "invite" -> {
                Optional<GameDao.AllianceInfo> all = gameDao.findAllianceByPlayerId(player.id());
                if (all.isEmpty() || all.get().leaderId() != player.id()) {
                    sendText(chatId, "Только лидер может приглашать.", menuService.mainMenu());
                    return;
                }
                List<PlayerRecord> targets = gameDao.playersWithoutAlliance(player.id(), 20);
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                for (PlayerRecord t : targets) {
                    rows.add(List.of(btn("📩 " + t.villageName() + " (" + getPlayerPower(t.id()) + ")", "alliance:invite_player:" + t.id())));
                }
                rows.add(List.of(btn("◀️ Назад", "menu:alliance")));
                sendText(chatId, "Выбери игрока для приглашения:", InlineKeyboardMarkup.builder().keyboard(rows).build());
            }
            case "invite_player" -> {
                if (p.length < 3) return;
                long targetId = Long.parseLong(p[2]);
                Optional<GameDao.AllianceInfo> all = gameDao.findAllianceByPlayerId(player.id());
                if (all.isEmpty() || all.get().leaderId() != player.id()) return;
                if (gameDao.findAllianceByPlayerId(targetId).isPresent()) {
                    sendText(chatId, "Игрок уже состоит в альянсе.", menuService.mainMenu());
                    return;
                }
                long inviteId = gameDao.createAllianceInvite(all.get().id(), player.id(), targetId);
                Optional<PlayerRecord> target = gameDao.findPlayerById(targetId);
                if (target.isPresent()) {
                    int totalPower = gameDao.allianceTotalPower(all.get().id(), catalog);
                    sendText(target.get().telegramId(),
                            "📩 " + player.villageName() + " приглашает тебя в альянс " + all.get().name() + "!\n" +
                                    "Общая мощь альянса: " + totalPower,
                            InlineKeyboardMarkup.builder()
                                    .keyboardRow(List.of(btn("✅ Принять", "alliance:invite_accept:" + inviteId), btn("❌ Отклонить", "alliance:invite_decline:" + inviteId)))
                                    .build());
                }
                sendText(chatId, "Приглашение отправлено.", menuService.mainMenu());
            }
            case "invite_accept" -> {
                if (p.length < 3) return;
                long inviteId = Long.parseLong(p[2]);
                Optional<GameDao.AllianceInviteInfo> inv = gameDao.findAllianceInvite(inviteId);
                if (inv.isEmpty() || inv.get().invitedPlayerId() != player.id()) {
                    sendText(chatId, "Приглашение не найдено.", menuService.mainMenu());
                    return;
                }
                if (gameDao.findAllianceByPlayerId(player.id()).isPresent()) {
                    gameDao.deleteAllianceInvite(inviteId);
                    sendText(chatId, "Ты уже в альянсе.", menuService.mainMenu());
                    return;
                }
                if (gameDao.addAllianceMember(inv.get().allianceId(), player.id())) {
                    sendText(chatId, "✅ Ты вступил в альянс " + inv.get().allianceName() + "!", menuService.mainMenu());
                    gameDao.findPlayerById(inv.get().inviterId()).ifPresent(leader ->
                            sendText(leader.telegramId(), "✅ " + player.villageName() + " принял приглашение в альянс."));
                }
                gameDao.deleteAllianceInvite(inviteId);
            }
            case "invite_decline" -> {
                if (p.length < 3) return;
                long inviteId = Long.parseLong(p[2]);
                Optional<GameDao.AllianceInviteInfo> inv = gameDao.findAllianceInvite(inviteId);
                gameDao.deleteAllianceInvite(inviteId);
                sendText(chatId, "❌ Приглашение отклонено.", menuService.mainMenu());
                inv.ifPresent(i -> gameDao.findPlayerById(i.inviterId()).ifPresent(leader ->
                        sendText(leader.telegramId(), "❌ " + player.villageName() + " отклонил приглашение в альянс.")));
            }
            case "leave" -> {
                Optional<GameDao.AllianceInfo> all = gameDao.findAllianceByPlayerId(player.id());
                if (all.isEmpty()) return;
                if (all.get().leaderId() == player.id()) {
                    for (GameDao.AllianceMemberInfo member : gameDao.allianceMembers(all.get().id())) {
                        gameDao.removeAllianceMember(all.get().id(), member.playerId());
                    }
                    sendText(chatId, "🚪 Альянс распущен лидером.", menuService.mainMenu());
                } else {
                    gameDao.removeAllianceMember(all.get().id(), player.id());
                    sendText(chatId, "🚪 Ты вышел из альянса.", menuService.mainMenu());
                }
            }
            case "coop" -> {
                Optional<GameDao.AllianceInfo> all = gameDao.findAllianceByPlayerId(player.id());
                if (all.isEmpty() || all.get().leaderId() != player.id()) {
                    sendText(chatId, "Только лидер может запустить совместную атаку.", menuService.mainMenu());
                    return;
                }
                List<PlayerRecord> targets = gameDao.attackTargets(player.id());
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                for (PlayerRecord t : targets) {
                    rows.add(List.of(btn("⚔️ " + t.villageName() + " (" + getPlayerPower(t.id()) + ")", "alliance:coop_target:" + t.id())));
                }
                rows.add(List.of(btn("◀️ Назад", "menu:alliance")));
                sendText(chatId, "Выбери цель для совместной атаки:", InlineKeyboardMarkup.builder().keyboard(rows).build());
            }
            case "coop_target" -> {
                if (p.length < 3) return;
                long targetId = Long.parseLong(p[2]);
                Optional<GameDao.AllianceInfo> all = gameDao.findAllianceByPlayerId(player.id());
                if (all.isEmpty() || all.get().leaderId() != player.id()) return;
                long sessionId = allianceAttackSeq.getAndIncrement();
                AllianceAttackSession session = new AllianceAttackSession(
                        sessionId,
                        all.get().id(),
                        player.id(),
                        targetId,
                        Instant.now().plusSeconds(120).toEpochMilli()
                );
                session.joined.add(player.id());
                session.answered.add(player.id());
                allianceAttackSessions.put(sessionId, session);

                Optional<PlayerRecord> target = gameDao.findPlayerById(targetId);
                if (target.isPresent()) {
                    for (GameDao.AllianceMemberInfo member : gameDao.allianceMembers(all.get().id())) {
                        if (member.playerId() == player.id()) continue;
                        sendText(member.telegramId(),
                                "⚔️ " + player.villageName() + " объявляет атаку на " + target.get().villageName() + "! Участвуешь?",
                                InlineKeyboardMarkup.builder()
                                        .keyboardRow(List.of(btn("✅ Иду в бой", "alliance:coop_join:" + sessionId), btn("❌ Пропустить", "alliance:coop_skip:" + sessionId)))
                                        .build());
                    }
                    sendText(chatId, "Запрос союзникам отправлен. Ждём ответы 2 минуты.");
                }
            }
            case "coop_join" -> {
                if (p.length < 3) return;
                long sessionId = Long.parseLong(p[2]);
                AllianceAttackSession s = allianceAttackSessions.get(sessionId);
                if (s == null || Instant.now().toEpochMilli() > s.deadlineAt) {
                    sendText(chatId, "Сессия атаки завершена.", menuService.mainMenu());
                    return;
                }
                if (gameDao.findAllianceByPlayerId(player.id()).map(a -> a.id() == s.allianceId).orElse(false)) {
                    s.joined.add(player.id());
                    s.answered.add(player.id());
                    sendText(chatId, "✅ Ты присоединился к атаке.");
                }
            }
            case "coop_skip" -> {
                if (p.length < 3) return;
                long sessionId = Long.parseLong(p[2]);
                AllianceAttackSession s = allianceAttackSessions.get(sessionId);
                if (s != null) {
                    s.answered.add(player.id());
                    sendText(chatId, "❌ Ты пропустил атаку.");
                }
            }
            default -> showAllianceMenu(chatId, player);
        }
    }

    private void showTradeMenu(long chatId, PlayerRecord player) {
        List<KeyValueAmount> inv = gameDao.loadInventory(player.id());
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        StringBuilder txt = new StringBuilder("🏦 Торговля\n\nИнвентарь:\n");
        for (KeyValueAmount item : inv) {
            if (item.quantity() <= 0) continue;
            txt.append(catalog.itemDisplay(item.type())).append(" x").append(item.quantity()).append("\n");
            rows.add(List.of(btn("💰 Продать напрямую", "trade:direct:item:" + item.type()), btn("🔨 Аукцион (50💰)", "trade:auction:item:" + item.type())));
        }
        rows.add(List.of(btn("📣 Активные аукционы", "trade:auctions")));
        rows.add(List.of(btn("◀️ Назад", "menu:city")));
        sendText(chatId, txt.toString(), InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private void handleTradeCallbacks(long chatId, PlayerRecord player, String data) {
        String[] p = data.split(":");
        if (p.length >= 2 && "auctions".equals(p[1])) {
            List<com.zemli.bot.model.MarketListing> active = gameDao.findActiveAuctions();
            if (active.isEmpty()) {
                sendText(chatId, "📣 Сейчас нет активных аукционов.", menuService.mainMenu());
                return;
            }
            StringBuilder sb = new StringBuilder("📣 Активные аукционы:\n");
            for (com.zemli.bot.model.MarketListing a : active) {
                long leftMin = a.auctionEndsAt() == null ? 0 : Math.max(0, (a.auctionEndsAt() - Instant.now().toEpochMilli()) / 60000L);
                sb.append("- #").append(a.id()).append(" ").append(catalog.itemDisplay(a.itemType()))
                        .append(" | старт ").append(a.price()).append("💰")
                        .append(" | ~").append(leftMin).append(" мин\n");
            }
            sendText(chatId, sb.toString(), menuService.mainMenu());
            return;
        }
        if (p.length >= 4 && "auction".equals(p[1]) && "item".equals(p[2])) {
            String item = p[3];
            if (!gameDao.hasInventoryItem(player.id(), item)) {
                sendText(chatId, "❌ Этого предмета нет в инвентаре.", menuService.mainMenu());
                return;
            }
            int fee = activeDailyEvent() == DailyEventType.TRADE_DAY ? 0 : 50;
            if (fee > 0 && !gameDao.spendResources(player.id(), new GameCatalog.Cost(0, 0, 0, 0, fee, 0, 0))) {
                sendText(chatId, "❌ Нужно 50💰 для размещения аукциона.", menuService.mainMenu());
                return;
            }
            if (!gameDao.removeInventoryItem(player.id(), item, 1)) {
                sendText(chatId, "❌ Не удалось выставить предмет.", menuService.mainMenu());
                return;
            }
            int startPrice = 100;
            gameDao.createAuction(player.id(), item, startPrice, Instant.now().plusSeconds(12 * 3600L).toEpochMilli());
            sendText(chatId, "✅ Лот выставлен на аукцион. Старт: " + startPrice + "💰", menuService.mainMenu());
            sendGroupMessageAsync(
                    "🔨 АУКЦИОН\n" +
                            player.villageName() + " выставил: " + catalog.itemDisplay(item) + "\n" +
                            "Начальная ставка: " + startPrice + " 💰\n" +
                            "⏰ Заканчивается через 12 часов\n" +
                            "Сделай ставку в боте!"
            );
            return;
        }
        sendText(chatId, "Торговля активна, детали в следующем шаге.", menuService.mainMenu());
    }

    private String actionLabel(String action) {
        return switch (action) {
            case "ATTACK" -> "⚔️ Атака";
            case "DEFEND" -> "🛡️ Защита";
            case "BARRAGE" -> "🏹 Обстрел";
            case "ITEM_PISTOL" -> "🔫 Пистолет";
            case "ITEM_CANNON" -> "💣 Пушка";
            default -> action;
        };
    }

    private boolean isArmor(String itemType) {
        return ARMOR_DEFENSE_BONUS.containsKey(itemType);
    }

    private double armorBonus(String armorType) {
        if (armorType == null) return 0.0;
        return ARMOR_DEFENSE_BONUS.getOrDefault(armorType, 0.0);
    }

    private int sellPrice(String item) {
        return switch (item) {
            case "BLUEPRINT_PISTOL", "BLUEPRINT_CANNON", "BLUEPRINT_CROSSBOW" -> 120;
            case "LEATHER_ARMOR" -> 80;
            case "CHAINMAIL_ARMOR" -> 140;
            case "IRON_ARMOR" -> 220;
            case "GOLD_ARMOR" -> 500;
            case "DIAMOND_ARMOR" -> 800;
            case "NETHERITE_ARMOR" -> 1500;
            default -> 100;
        };
    }

    private int rand(int from, int to) {
        return ThreadLocalRandom.current().nextInt(from, to + 1);
    }

    private double passiveBonusByLevel(int level, double baseBonus) {
        if (level <= 0) {
            return 0.0;
        }
        return baseBonus * (1.0 + (level - 1) * 0.5);
    }

    private String harvestCooldownText(long remainingMs) {
        long totalSec = Math.max(1, remainingMs / 1000L);
        long min = totalSec / 60L;
        long sec = totalSec % 60L;
        return "⏳ Следующая добыча через: " + min + " мин " + sec + " сек";
    }

    private String buildBattleSummary(
            PlayerRecord player,
            boolean won,
            List<GameDao.ArmyLoss> losses,
            int remainingPower,
            boolean showLoot,
            int wood,
            int stone,
            int food,
            int iron,
            int gold,
            int mana,
            int alcohol
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚔️ Итог боя:\n");
        sb.append("🏆 ").append(won ? "Победа" : "Поражение").append("\n");
        sb.append("━━━━━━━━━━━━\n");
        sb.append("💀 Твои потери:\n");
        if (losses.isEmpty()) {
            sb.append("- Нет\n");
        } else {
            for (GameDao.ArmyLoss loss : losses) {
                GameCatalog.UnitSpec unit = catalog.unitByKey(player.faction(), loss.unitType());
                String unitTitle = unit != null ? unit.title() : loss.unitType();
                sb.append("- ").append(unitTitle).append(": -").append(loss.lost()).append("\n");
            }
        }
        sb.append("Осталось армии: ").append(remainingPower).append(" мощи\n");
        if (showLoot) {
            sb.append("━━━━━━━━━━━━\n");
            sb.append("💰 Добыча: ");
            sb.append("🪵").append(wood).append(" ");
            sb.append("🪨").append(stone).append(" ");
            sb.append("🌾").append(food).append(" ");
            if (iron > 0) sb.append("⚔️").append(iron).append(" ");
            sb.append("💰").append(gold);
            if (mana > 0) sb.append(" 🧪").append(mana);
            if (alcohol > 0) sb.append(" 🍺").append(alcohol);
        }
        return sb.toString();
    }

    private InlineKeyboardButton btn(String text, String callbackData) {
        return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
    }

    private String costText(GameCatalog.Cost c) {
        List<String> parts = new ArrayList<>();
        if (c.wood() > 0) parts.add("🪵" + c.wood());
        if (c.stone() > 0) parts.add("🪨" + c.stone());
        if (c.food() > 0) parts.add("🌾" + c.food());
        if (c.iron() > 0) parts.add("⚔️" + c.iron());
        if (c.gold() > 0) parts.add("💰" + c.gold());
        if (c.mana() > 0) parts.add("🧪" + c.mana());
        if (c.alcohol() > 0) parts.add("🍺" + c.alcohol());
        return String.join(" ", parts);
    }

    private DailyEventType activeDailyEvent() {
        String today = DailyEventType.todayUtc();
        String eventDate = gameDao.getServerState(DailyEventType.STATE_KEY_EVENT_DATE).orElse("");
        if (!today.equals(eventDate)) {
            return null;
        }
        return DailyEventType.parse(gameDao.getServerState(DailyEventType.STATE_KEY_EVENT).orElse(null));
    }

    private void sendText(long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage msg = SendMessage.builder().chatId(String.valueOf(chatId)).text(text).replyMarkup(keyboard).build();
        try {
            execute(msg);
        } catch (Exception e) {
            log.error("Failed to send message", e);
        }
    }
}
