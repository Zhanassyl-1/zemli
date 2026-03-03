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
import java.util.Arrays;
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
            "LEATHER_VEST_ARMOR", 0.03,
            "LEATHER_ARMOR", 0.05,
            "CHAINMAIL_ARMOR", 0.10,
            "IRON_ARMOR", 0.20,
            "GOLD_ARMOR", 0.30,
            "DIAMOND_ARMOR", 0.40,
            "MITHRIL_ARMOR", 0.45,
            "NETHERITE_ARMOR", 0.50
    );
    private static final List<String> RESOURCES = List.of("WOOD", "STONE", "FOOD", "IRON", "GOLD", "MANA", "ALCOHOL");
    // ВОТ СЮДА ВСТАВЬ СВОЙ ЮЗЕРНЕЙМ:
    public static final List<String> ADMIN_USERNAMES = new ArrayList<>(Arrays.asList(
            "твой_юзернейм",
            "резервный_админ"
    ));
    private static final Map<String, String> HERO_ALIASES = Map.of(
            "ланселот", "HERO_LANCELOT",
            "артур", "HERO_ARTHUR",
            "мусаси", "HERO_MUSASHI",
            "рагнар", "HERO_RAGNAR",
            "чингисхан", "HERO_GENGHIS",
            "токугава", "HERO_TOKUGAWA",
            "ривард", "HERO_RICHARD",
            "ричард", "HERO_RICHARD"
    );
    private static final List<Faction> FACTION_ORDER = List.of(
            Faction.KNIGHTS, Faction.SAMURAI, Faction.VIKINGS, Faction.MONGOLS, Faction.DESERT_DWELLERS, Faction.AZTECS
    );

    private final String configuredToken;
    private final String botUsername;
    private final RegistrationService registrationService;
    private final MenuService menuService;
    private final GameDao gameDao;
    private final GameCatalog catalog;
    private final TaskExecutor taskExecutor;
    private final long groupChatId;
    private final Set<Long> adminUserIds;
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
            long groupChatId,
            String adminIdsRaw
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
        this.adminUserIds = parseAdminIds(adminIdsRaw);
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

    private record NpcTarget(
            String key,
            String title,
            double baseChance,
            int goldMin,
            int goldMax,
            int woodMin,
            int woodMax,
            int ironMin,
            int ironMax,
            int manaMin,
            int manaMax,
            int lossMin,
            int lossMax
    ) {
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
        touchPlayerActivity(tgId);
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
                sendText(chatId, "Отлично. Теперь выбери фракцию:");
                showFactionCard(chatId, 0);
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

        if (isPrivate && isAdminCommand(commandToken)) {
            if (!isAdmin(message)) {
                sendText(chatId, "У вас нет прав");
                return;
            }
            if ("/admin".equalsIgnoreCase(commandToken)) {
                showAdminMenu(
                        chatId,
                        message.getFrom() == null ? null : message.getFrom().getUserName(),
                        message.getFrom() == null ? null : message.getFrom().getId()
                );
                return;
            }
            if (handleAdminCommand(message, tgId, chatId, text)) {
                return;
            }
        }

        if ("/start".equalsIgnoreCase(commandToken)) {
            if (!isPrivate) {
                sendText(chatId, "👋 Привет! Чтобы играть напиши мне в личку: @" + botUsername);
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
        if ("/город".equalsIgnoreCase(commandToken) && isPrivate) {
            Optional<PlayerRecord> p = registrationService.findRegistered(tgId);
            if (p.isEmpty()) {
                sendText(chatId, "Сначала зарегистрируйся через /start");
                return;
            }
            showCityCompact(chatId, p.get());
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

    private boolean handleAdminCommand(Message message, long tgId, long chatId, String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length == 0) {
            return false;
        }
        String cmd = parts[0].toLowerCase();
        Optional<PlayerRecord> p = registrationService.findRegistered(tgId);
        String username = message.getFrom() != null ? message.getFrom().getUserName() : "unknown";

        switch (cmd) {
            case "/on" -> {
                if (p.isEmpty()) {
                    sendText(chatId, "Сначала зарегистрируйся через /start");
                    return true;
                }
                gameDao.setPlayerState(p.get().id(), "TESTER_MODE", 1);
                log.info("[ADMIN] @{} включил /on", username);
                sendText(chatId, "✅ Режим тестировщика включен");
                return true;
            }
            case "/off" -> {
                if (p.isEmpty()) {
                    sendText(chatId, "Сначала зарегистрируйся через /start");
                    return true;
                }
                gameDao.clearPlayerState(p.get().id(), "TESTER_MODE");
                log.info("[ADMIN] @{} включил /off", username);
                sendText(chatId, "✅ Режим тестировщика выключен");
                return true;
            }
            case "/status" -> {
                if (p.isEmpty()) {
                    sendText(chatId, "Сначала зарегистрируйся через /start");
                    return true;
                }
                boolean tester = gameDao.getPlayerState(p.get().id(), "TESTER_MODE") != null;
                boolean god = gameDao.getPlayerState(p.get().id(), "GOD_MODE") != null;
                log.info("[ADMIN] @{} использовал /status", username);
                sendText(chatId, "🔧 Статус\nТестер: " + (tester ? "ON" : "OFF") + "\nGod: " + (god ? "ON" : "OFF"));
                return true;
            }
            case "/god" -> {
                if (p.isEmpty()) {
                    sendText(chatId, "Сначала зарегистрируйся через /start");
                    return true;
                }
                if (!isTesterModeEnabled(p.get().id())) {
                    sendText(chatId, "Сначала включи режим тестировщика: /on");
                    return true;
                }
                Long state = gameDao.getPlayerState(p.get().id(), "GOD_MODE");
                if (state == null) {
                    gameDao.setPlayerState(p.get().id(), "GOD_MODE", 1);
                    log.info("[ADMIN] @{} включил /god режим", username);
                    sendText(chatId, "✅ GOD режим включен");
                } else {
                    gameDao.clearPlayerState(p.get().id(), "GOD_MODE");
                    log.info("[ADMIN] @{} выключил /god режим", username);
                    sendText(chatId, "✅ GOD режим выключен");
                }
                return true;
            }
            case "/add_all" -> {
                if (p.isEmpty()) {
                    sendText(chatId, "Сначала зарегистрируйся через /start");
                    return true;
                }
                if (!isTesterModeEnabled(p.get().id())) {
                    sendText(chatId, "Сначала включи режим тестировщика: /on");
                    return true;
                }
                int amount = parts.length >= 2 ? safeParseInt(parts[1], 0) : 0;
                if (amount <= 0) {
                    sendText(chatId, "Использование: /add_all 10000");
                    return true;
                }
                gameDao.addResources(p.get().id(), new GameCatalog.Cost(amount, amount, amount, amount, amount, amount, amount));
                log.info("[ADMIN] @{} использовал /add_all {}", username, amount);
                sendText(chatId, "✅ Добавлено всех ресурсов: " + amount);
                return true;
            }
            case "/add" -> {
                if (p.isEmpty()) {
                    sendText(chatId, "Сначала зарегистрируйся через /start");
                    return true;
                }
                if (!isTesterModeEnabled(p.get().id())) {
                    sendText(chatId, "Сначала включи режим тестировщика: /on");
                    return true;
                }
                if (parts.length < 3) {
                    sendText(chatId, "Использование: /add дерево 1000");
                    return true;
                }
                String key = mapResourceAlias(parts[1]);
                int amount = safeParseInt(parts[2], 0);
                if (key == null || amount <= 0) {
                    sendText(chatId, "Ресурс: дерево/камень/еда/железо/золото/манна/алкоголь");
                    return true;
                }
                gameDao.addSingleResource(p.get().id(), key, amount);
                log.info("[ADMIN] @{} использовал /add {} {}", username, parts[1], amount);
                sendText(chatId, "✅ Добавлено: " + parts[1] + " +" + amount);
                return true;
            }
            case "/max_res" -> {
                if (p.isEmpty()) {
                    sendText(chatId, "Сначала зарегистрируйся через /start");
                    return true;
                }
                if (!isTesterModeEnabled(p.get().id())) {
                    sendText(chatId, "Сначала включи режим тестировщика: /on");
                    return true;
                }
                gameDao.setResourcesExact(p.get().id(), 1_000_000, 1_000_000, 1_000_000, 1_000_000, 1_000_000, 1_000_000, 1_000_000);
                log.info("[ADMIN] @{} использовал /max_res", username);
                sendText(chatId, "✅ Ресурсы установлены на максимум");
                return true;
            }
            case "/all_units" -> {
                if (p.isEmpty()) {
                    sendText(chatId, "Сначала зарегистрируйся через /start");
                    return true;
                }
                for (GameCatalog.UnitSpec unit : catalog.unitsForFaction(p.get().faction())) {
                    gameDao.upsertArmy(unit.key(), unit.power(), p.get().id(), 200);
                }
                log.info("[ADMIN] @{} использовал /all_units", username);
                sendText(chatId, "✅ Выданы все юниты текущей расы (по 200)");
                return true;
            }
            case "/all_heroes" -> {
                if (p.isEmpty()) {
                    sendText(chatId, "Сначала зарегистрируйся через /start");
                    return true;
                }
                for (String heroKey : HERO_ALIASES.values()) {
                    gameDao.addInventoryItem(p.get().id(), heroKey, 1);
                }
                log.info("[ADMIN] @{} использовал /all_heroes", username);
                sendText(chatId, "✅ Выданы все тестовые герои");
                return true;
            }
            case "/hero" -> {
                if (p.isEmpty()) {
                    sendText(chatId, "Сначала зарегистрируйся через /start");
                    return true;
                }
                if (parts.length < 2) {
                    sendText(chatId, "Использование: /hero мусаси");
                    return true;
                }
                String hero = HERO_ALIASES.get(parts[1].toLowerCase());
                if (hero == null) {
                    sendText(chatId, "Неизвестный герой.");
                    return true;
                }
                gameDao.addInventoryItem(p.get().id(), hero, 1);
                log.info("[ADMIN] @{} использовал /hero {}", username, parts[1]);
                sendText(chatId, "✅ Герой выдан: " + parts[1]);
                return true;
            }
            case "/upgrade_hero" -> {
                if (p.isEmpty()) {
                    sendText(chatId, "Сначала зарегистрируйся через /start");
                    return true;
                }
                if (parts.length < 2) {
                    sendText(chatId, "Использование: /upgrade_hero мусаси");
                    return true;
                }
                String hero = HERO_ALIASES.get(parts[1].toLowerCase());
                if (hero == null) {
                    sendText(chatId, "Неизвестный герой.");
                    return true;
                }
                gameDao.addInventoryItem(p.get().id(), hero + "_MAX", 1);
                log.info("[ADMIN] @{} использовал /upgrade_hero {}", username, parts[1]);
                sendText(chatId, "✅ Герой прокачан до MAX: " + parts[1]);
                return true;
            }
            case "/max_town" -> {
                if (p.isEmpty()) {
                    sendText(chatId, "Сначала зарегистрируйся через /start");
                    return true;
                }
                gameDao.setCityLevel(p.get().id(), 10);
                log.info("[ADMIN] @{} использовал /max_town", username);
                sendText(chatId, "✅ Уровень города установлен на 10");
                return true;
            }
            case "/build_all" -> {
                if (p.isEmpty()) {
                    sendText(chatId, "Сначала зарегистрируйся через /start");
                    return true;
                }
                Map<String, BuildingState> current = gameDao.loadBuildingMap(p.get().id());
                for (GameCatalog.BuildingSpec spec : catalog.buildings().values()) {
                    if (!current.containsKey(spec.key())) {
                        gameDao.buildInstant(p.get().id(), spec.key());
                    }
                    int target = catalog.maxBuildingLevel(spec.key());
                    for (int i = 1; i < target; i++) {
                        gameDao.upgradeBuildingInstant(p.get().id(), spec.key());
                    }
                }
                log.info("[ADMIN] @{} использовал /build_all", username);
                sendText(chatId, "✅ Все постройки открыты");
                return true;
            }
            case "/reset" -> {
                if (p.isEmpty()) {
                    sendText(chatId, "Сначала зарегистрируйся через /start");
                    return true;
                }
                gameDao.resetPlayerProgress(p.get().id());
                log.info("[ADMIN] @{} использовал /reset", username);
                sendText(chatId, "✅ Прогресс сброшен");
                return true;
            }
            case "/win" -> {
                if (p.isEmpty()) {
                    sendText(chatId, "Сначала зарегистрируйся через /start");
                    return true;
                }
                gameDao.addSingleResource(p.get().id(), "GOLD", 500);
                gameDao.addSingleResource(p.get().id(), "MANA", 50);
                log.info("[ADMIN] @{} использовал /win", username);
                sendText(chatId, "✅ Мгновенная победа (тест): +500 золота, +50 манны");
                return true;
            }
            case "/test_fight" -> {
                if (p.isEmpty()) {
                    sendText(chatId, "Сначала зарегистрируйся через /start");
                    return true;
                }
                int enemyPower = parts.length >= 2 ? safeParseInt(parts[1], 100) : 100;
                int myPower = getPlayerPower(p.get().id());
                boolean win = myPower >= enemyPower || ThreadLocalRandom.current().nextBoolean();
                if (win) {
                    gameDao.addSingleResource(p.get().id(), "GOLD", 300);
                    sendText(chatId, "✅ Тестовый бой выигран\nТвоя сила: " + myPower + "\nСила врага: " + enemyPower + "\nНаграда: +300 золота");
                } else {
                    sendText(chatId, "❌ Тестовый бой проигран\nТвоя сила: " + myPower + "\nСила врага: " + enemyPower);
                }
                log.info("[ADMIN] @{} использовал /test_fight {}", username, enemyPower);
                return true;
            }
            case "/no_cooldown" -> {
                if (p.isEmpty()) {
                    sendText(chatId, "Сначала зарегистрируйся через /start");
                    return true;
                }
                Long state = gameDao.getPlayerState(p.get().id(), "NO_COOLDOWN");
                if (state == null) {
                    gameDao.setPlayerState(p.get().id(), "NO_COOLDOWN", 1);
                    sendText(chatId, "✅ Кулдауны отключены");
                } else {
                    gameDao.clearPlayerState(p.get().id(), "NO_COOLDOWN");
                    sendText(chatId, "✅ Кулдауны включены");
                }
                log.info("[ADMIN] @{} использовал /no_cooldown", username);
                return true;
            }
            case "/listadmins" -> {
                log.info("[ADMIN] @{} использовал /listadmins", username);
                sendText(chatId, "Админы: " + String.join(", ", ADMIN_USERNAMES));
                return true;
            }
            case "/addadmin" -> {
                if (parts.length < 2) {
                    sendText(chatId, "Использование: /addadmin @username");
                    return true;
                }
                ADMIN_USERNAMES.add(parts[1].replace("@", "").toLowerCase());
                log.info("[ADMIN] @{} добавил админа {}", username, parts[1]);
                sendText(chatId, "✅ Админ добавлен: " + parts[1]);
                return true;
            }
            case "/removeadmin" -> {
                if (parts.length < 2) {
                    sendText(chatId, "Использование: /removeadmin @username");
                    return true;
                }
                ADMIN_USERNAMES.remove(parts[1].replace("@", "").toLowerCase());
                log.info("[ADMIN] @{} удалил админа {}", username, parts[1]);
                sendText(chatId, "✅ Админ удален: " + parts[1]);
                return true;
            }
            default -> {
                return false;
            }
        }
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
            sendText(chatId, "❌ Ты ещё не зарегистрирован! Напиши @" + botUsername + " /start");
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
                        "🤖 Играть: @" + botUsername);
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

    private boolean isAdmin(Message message) {
        if (message == null || message.getFrom() == null) {
            return false;
        }
        String username = message.getFrom().getUserName();
        long userId = message.getFrom().getId();
        boolean byId = adminUserIds.contains(userId);
        if (username == null || username.isBlank()) {
            return byId;
        }
        return byId || ADMIN_USERNAMES.contains(username.replace("@", "").toLowerCase());
    }

    private boolean isAdminCommand(String commandToken) {
        if (commandToken == null) {
            return false;
        }
        String cmd = commandToken.toLowerCase();
        return Set.of(
                "/admin",
                "/on", "/off", "/status",
                "/god", "/add", "/add_all", "/max_res",
                "/all_units", "/all_heroes", "/hero", "/upgrade_hero",
                "/max_town", "/build_all", "/reset",
                "/win", "/test_fight", "/no_cooldown",
                "/addadmin", "/removeadmin", "/listadmins"
        ).contains(cmd);
    }

    private boolean isTesterModeEnabled(long playerId) {
        return gameDao.getPlayerState(playerId, "TESTER_MODE") != null;
    }

    private Set<Long> parseAdminIds(String raw) {
        Set<Long> ids = new HashSet<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        for (String part : raw.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(token));
            } catch (NumberFormatException ex) {
                log.warn("Invalid ADMIN_IDS entry: {}", token);
            }
        }
        return ids;
    }

    private int safeParseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String mapResourceAlias(String token) {
        if (token == null) {
            return null;
        }
        return switch (token.toLowerCase()) {
            case "wood", "дерево", "древо" -> "WOOD";
            case "stone", "камень" -> "STONE";
            case "food", "еда" -> "FOOD";
            case "iron", "железо" -> "IRON";
            case "gold", "золото" -> "GOLD";
            case "mana", "манна", "мана" -> "MANA";
            case "alcohol", "алкоголь", "алко" -> "ALCOHOL";
            default -> null;
        };
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
            case 8 -> "Королевство Света";
            case 9 -> "Великая Империя";
            case 10 -> "Держава";
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
        touchPlayerActivity(tgId);
        long chatId = callback.getMessage().getChatId();

        if (data.startsWith("faction:view:")) {
            if (!registrationService.isWaitingFaction(tgId)) {
                sendText(chatId, "Сначала введи название деревни текстом.");
                return;
            }
            int index = Integer.parseInt(data.substring("faction:view:".length()));
            showFactionCard(chatId, index);
            return;
        }

        if (data.startsWith("faction:choose:")) {
            if (!registrationService.isWaitingFaction(tgId)) {
                sendText(chatId, "Сначала введи название деревни текстом.");
                return;
            }
            Faction faction = Faction.valueOf(data.substring("faction:choose:".length()));
            showFactionConfirm(chatId, faction);
            return;
        }

        if (data.startsWith("faction:back:")) {
            if (!registrationService.isWaitingFaction(tgId)) {
                sendText(chatId, "Сначала введи название деревни текстом.");
                return;
            }
            int index = Integer.parseInt(data.substring("faction:back:".length()));
            showFactionCard(chatId, index);
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
                            "\nДОСТУПНЫЕ РЕСУРСЫ:\n" +
                            "🪵 Дерево: 200\n" +
                            "🪨 Камень: 150\n" +
                            "🌾 Еда: 200\n" +
                            "⚔️ Железо: 0\n" +
                            "💰 Золото: 50\n" +
                            "🧪 Манна: 0\n" +
                            "🍺 Алкоголь: 0\n\n" +
                            "Используй /город чтобы строить и развиваться\n\n" +
                            factionFinalMessage(faction),
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
                case "menu:heroes" -> showHeroesMenu(chatId, player);
                case "menu:profile" -> showProfileMenu(chatId, player);
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
        int morale = gameDao.loadMorale(player.id());
        String text = "🏕️ " + player.villageName() + " | " + player.faction().getTitle() + " | Ур." + player.cityLevel() + "\n" +
                "💰 Золото: " + gold + " | ⚔️ Мощь: " + power + " | 🏆 #" + rank + " в рейтинге\n" +
                moraleLine(morale);

        sendText(chatId, text,
                InlineKeyboardMarkup.builder()
                        .keyboardRow(List.of(btn("🏗️ СТРОИТЬ", "menu:build"), btn("📈 РЕСУРСЫ", "city:resources")))
                        .keyboardRow(List.of(btn("🏛️ КАЗАРМЫ", "menu:army"), btn("🍺 ТАВЕРНА", "menu:heroes")))
                        .keyboardRow(List.of(btn("◀️ Главное меню", "city:back")))
                        .build());
    }

    private void showProfileMenu(long chatId, PlayerRecord player) {
        int rank = gameDao.rankByCityLevel(player.id());
        int power = getPlayerPower(player.id());
        GameDao.PlayerBattleStats stats = gameDao.playerBattleStats(player.id());
        sendText(chatId,
                "📊 ПРОФИЛЬ\n" +
                        "🏕️ " + player.villageName() + "\n" +
                        "🌍 Фракция: " + player.faction().getTitle() + "\n" +
                        "🏰 Уровень города: " + cityLevelTitle(player.cityLevel()) + "\n" +
                        "⚔️ Мощь: " + power + "\n" +
                        "🏆 Ранг: #" + rank + "\n" +
                        "🥇 Побед: " + stats.wins() + " | 💀 Поражений: " + stats.losses(),
                InlineKeyboardMarkup.builder().keyboardRow(List.of(btn("◀️ Назад", "city:back"))).build());
    }

    private void showHeroesMenu(long chatId, PlayerRecord player) {
        List<KeyValueAmount> inv = gameDao.loadInventory(player.id());
        long heroCount = inv.stream().filter(i -> i.type().startsWith("HERO_")).mapToInt(KeyValueAmount::quantity).sum();
        sendText(chatId,
                "👑 ГЕРОИ\n" +
                        "Текущих героев в инвентаре: " + heroCount + "\n\n" +
                        "Редкости:\n" +
                        "🟢 Обычный 60%\n" +
                        "🔵 Редкий 30%\n" +
                        "🟣 Легендарный 9%\n" +
                        "🟡 Мифический 1%\n\n" +
                        "Героев можно получать через таверну, победы и ивенты.",
                InlineKeyboardMarkup.builder().keyboardRow(List.of(btn("◀️ Назад", "city:back"))).build());
    }

    private void showAdminMenu(long chatId, String username, Long userId) {
        boolean isAdminByUsername = username != null && ADMIN_USERNAMES.contains(username.replace("@", "").toLowerCase());
        boolean isAdminById = userId != null && adminUserIds.contains(userId);
        if (!isAdminByUsername && !isAdminById) {
            sendText(chatId, "У вас нет прав");
            return;
        }
        sendText(chatId,
                "🔧 АДМИН-ПАНЕЛЬ\n" +
                        "\n🎮 РЕЖИМЫ:\n" +
                        "/on - включить режим тестировщика\n" +
                        "/off - выключить (обычный игрок)\n" +
                        "/status - проверить режим\n" +
                        "\n💰 РЕСУРСЫ:\n" +
                        "/god - бесконечные ресурсы (вкл/выкл)\n" +
                        "/add [ресурс] [кол-во] - добавить (дерево, камень, еда, железо, золото, манна, алкоголь)\n" +
                        "/add_all [кол-во] - добавить всё\n" +
                        "/max_res - максимум всех ресурсов\n" +
                        "\n⚔️ ЮНИТЫ И ГЕРОИ:\n" +
                        "/all_units - получить всех юнитов 5 уровня\n" +
                        "/all_heroes - получить всех героев\n" +
                        "/hero [имя] - получить героя\n" +
                        "\n🏰 ГОРОД:\n" +
                        "/max_town - макс уровень города\n" +
                        "/build_all - построить всё\n" +
                        "/reset - сбросить прогресс\n" +
                        "\n⚡ БОЙ:\n" +
                        "/win - мгновенная победа\n" +
                        "/test_fight [сила] - тестовый бой\n" +
                        "/no_cooldown - отключить кулдауны\n" +
                        "\n👑 УПРАВЛЕНИЕ:\n" +
                        "/addadmin @username - добавить админа\n" +
                        "/removeadmin @username - убрать\n" +
                        "/listadmins - список админов");
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

    private void showFactionCard(long chatId, int index) {
        int safeIndex = Math.floorMod(index, FACTION_ORDER.size());
        Faction faction = FACTION_ORDER.get(safeIndex);
        String text = factionCardText(faction);
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(
                        btn("◀️", "faction:view:" + Math.floorMod(safeIndex - 1, FACTION_ORDER.size())),
                        btn("✅ Выбрать " + faction.getTitle(), "faction:choose:" + faction.name()),
                        btn("▶️", "faction:view:" + Math.floorMod(safeIndex + 1, FACTION_ORDER.size()))
                ))
                .build();
        sendTextRaw(chatId, text, keyboard);
    }

    private void showFactionConfirm(long chatId, Faction faction) {
        int index = FACTION_ORDER.indexOf(faction);
        sendText(chatId,
                "Ты выбрал: " + factionEmoji(faction) + " " + faction.getTitle() + "\n" +
                        "Это постоянный выбор — изменить нельзя!",
                InlineKeyboardMarkup.builder()
                        .keyboardRow(List.of(
                                btn("✅ Да, я " + faction.getTitle() + "!", "faction:confirm:" + faction.name()),
                                btn("◀️ Выбрать другую", "faction:back:" + Math.max(0, index))
                        ))
                        .build());
    }

    private String factionCardText(Faction faction) {
        return switch (faction) {
            case KNIGHTS -> "=== РЫЦАРИ ЗАПАДА ===\n" +
                    "Благородные воины в тяжёлых доспехах. Медленные но непробиваемые.\n\n" +
                    "СИЛЬНЫЕ СТОРОНЫ:\n" +
                    "• Лучшая броня на сервере (+30% защиты в раунде 2)\n" +
                    "• Паладины - мощнейшие юниты\n" +
                    "• Бонус против Викингов (+40% защиты)\n" +
                    "• Идеальны для обороны\n\n" +
                    "СЛАБЫЕ СТОРОНЫ:\n" +
                    "• Очень медленные (кулдаун 12ч)\n" +
                    "• Уязвимы для обстрела монголов (-30%)\n" +
                    "• Дорогие юниты (требуют много железа)\n" +
                    "• Долгое восстановление\n\n" +
                    "ПУТЬ ВОИНА:\n" +
                    "Ополченец → Арбалетчик → Мечник → Тяжелый рыцарь → Паладин";
            case SAMURAI -> "=== САМУРАИ ВОСТОКА ===\n" +
                    "Дисциплина и мастерство клинка. Быстрые и смертельные.\n\n" +
                    "СИЛЬНЫЕ СТОРОНЫ:\n" +
                    "• Клинок смерти - 20% шанс убить врага мгновенно\n" +
                    "• Высокая скорость (6-7)\n" +
                    "• Бонус против Монголов (+25% уклонение от стрел)\n" +
                    "• Ниндзя - неуловимы\n\n" +
                    "СЛАБЫЕ СТОРОНЫ:\n" +
                    "• Слабая броня\n" +
                    "• Уязвимы для Викингов (-20% защиты)\n" +
                    "• Требуют много еды\n" +
                    "• Нет тяжелых юнитов\n\n" +
                    "ПУТЬ ВОИНА:\n" +
                    "Асигару → Лучник → Самурай с катаной → Ниндзя → Кендо-мастер";
            case VIKINGS -> "=== ВИКИНГИ СЕВЕРА ===\n" +
                    "Ярость и сила. Берсерки не знают страха.\n\n" +
                    "СИЛЬНЫЕ СТОРОНЫ:\n" +
                    "• Берсерк - чем меньше HP, тем сильнее атака (до +70%)\n" +
                    "• Вампиризм (восстанавливаются в бою)\n" +
                    "• Бонус против Самураев (+35% атаки)\n" +
                    "• Дешевые юниты\n\n" +
                    "СЛАБЫЕ СТОРОНЫ:\n" +
                    "• Нет тяжелой брони\n" +
                    "• Уязвимы для Рыцарей (-30% атаки)\n" +
                    "• Требуют много алкоголя\n" +
                    "• Недисциплинированные\n\n" +
                    "ПУТЬ ВОИНА:\n" +
                    "Трэлл → Метатель топора → Берсерк → Хирдманн → Ярл";
            case MONGOLS -> "=== МОНГОЛЫ СТЕПИ ===\n" +
                    "Конные лучники. Быстрее ветра.\n\n" +
                    "СИЛЬНЫЕ СТОРОНЫ:\n" +
                    "• Подвижность - могут убежать без потерь 1 раз в день\n" +
                    "• Самая высокая скорость (7)\n" +
                    "• Бонус против Рыцарей (+50% урона в обстреле)\n" +
                    "• Обстрел перед боем\n\n" +
                    "СЛАБЫЕ СТОРОНЫ:\n" +
                    "• Слабы в ближнем бою\n" +
                    "• Уязвимы для Самураев (-25% точности)\n" +
                    "• Требуют много дерева для стрел\n" +
                    "• Мало брони\n\n" +
                    "ПУТЬ ВОИНА:\n" +
                    "Скотовод → Конный лучник → Багатур → Нойон → Чингизид";
            case DESERT_DWELLERS -> "=== ПУСТЫННИКИ ВОСТОКА ===\n" +
                    "Таинственные воины песков. Торговцы и убийцы.\n\n" +
                    "СИЛЬНЫЕ СТОРОНЫ:\n" +
                    "• Мираж - 30% шанс увернуться от атаки\n" +
                    "• Ассасины убивают командиров\n" +
                    "• Бонус против Ацтеков (+30% морали)\n" +
                    "• Лучшие торговцы (+20% золота)\n\n" +
                    "СЛАБЫЕ СТОРОНЫ:\n" +
                    "• Медленные вне пустыни\n" +
                    "• Уязвимы для Викингов\n" +
                    "• Дорогие постройки\n" +
                    "• Требуют манну\n\n" +
                    "ПУТЬ ВОИНА:\n" +
                    "Погонщик → Янычар → Мамлюк → Ассасин → Султан";
            case AZTECS -> "=== АЦТЕКИ ДЖУНГЛЕЙ ===\n" +
                    "Кровавые жертвы древним богам.\n\n" +
                    "СИЛЬНЫЕ СТОРОНЫ:\n" +
                    "• Жертва - убивают слабого, усиливая сильного (+50%)\n" +
                    "• Жрецы используют магию\n" +
                    "• Бонус против Пустынников (+50% за жертвы)\n" +
                    "• Получают силу от каждой смерти\n\n" +
                    "СЛАБЫЕ СТОРОНЫ:\n" +
                    "• Без жертв - штраф -20%\n" +
                    "• Уязвимы для Рыцарей (сталь против дерева)\n" +
                    "• Требуют много манны\n" +
                    "• Медленное развитие\n\n" +
                    "ПУТЬ ВОИНА:\n" +
                    "Масеуалли → Лучник-воин → Ягуар → Орел → Жрец";
        };
    }

    private String factionFinalMessage(Faction faction) {
        String tip = switch (faction) {
            case KNIGHTS -> "Держи оборону и накапливай тяжёлую армию перед рывком.";
            case SAMURAI -> "Ищи ранние дуэли: твой ближний бой раскрывается быстрее других.";
            case VIKINGS -> "Играй агрессивно и чаще ходи в набеги, пока враги слабы.";
            case MONGOLS -> "Твоя сила в темпе: атакуй чаще и не затягивай войны.";
            case DESERT_DWELLERS -> "Дави количеством: дешёвые юниты дают стабильный перевес.";
            case AZTECS -> "Разгоняй манну раньше остальных и усиливай магические отряды.";
        };
        return "🎉 Отличный выбор!\n\n" +
                "Ты — " + faction.getTitle() + "\n" +
                "Твои воины уже ждут приказа.\n\n" +
                "Помни:\n" +
                tip + "\n\n" +
                "Удачи в завоеваниях, правитель! ⚔️";
    }

    private String factionEmoji(Faction faction) {
        return switch (faction) {
            case KNIGHTS -> "🛡️";
            case SAMURAI -> "⚔️";
            case VIKINGS -> "🪓";
            case MONGOLS -> "🏹";
            case DESERT_DWELLERS -> "🏜️";
            case AZTECS -> "🌞";
        };
    }

    private void showBuildTab(long chatId, PlayerRecord player, String tab) {
        Map<String, BuildingState> current = gameDao.loadBuildingMap(player.id());
        ResourcesRecord resources = gameDao.loadResources(player.id());
        int morale = gameDao.loadMorale(player.id());
        StringBuilder text = new StringBuilder("🏗️ Строительство (мгновенно)\n\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        rows.add(List.of(btn("🔨 Построить", "build:tab:new"), btn("⬆️ Улучшить", "build:tab:up")));
        if (morale < 50) {
            text.append(moraleRestrictionText(morale)).append("\n\n");
        }

        if ("new".equals(tab)) {
            for (GameCatalog.BuildingSpec spec : catalog.buildings().values()) {
                if ("TOWN_HALL".equals(spec.key())) {
                    continue;
                }
                if (current.containsKey(spec.key()) || player.cityLevel() < spec.requiredCityLevel()) {
                    continue;
                }
                text.append(spec.emoji()).append(" ").append(spec.title()).append(" ур.1\n");
                text.append("Цена постройки: ").append(costText(spec.cost())).append("\n");
                text.append(playerResourcesLine(resources, spec.cost())).append("\n\n");
                boolean enough = hasEnough(resources, spec.cost());
                rows.add(List.of(btn((enough ? "🔨 " : "⛔ ") + spec.title(), enough ? "build:new:" + spec.key() : "build:blocked:new:" + spec.key())));
            }
        } else if ("up".equals(tab)) {
            for (GameCatalog.BuildingSpec spec : catalog.buildings().values()) {
                BuildingState st = current.get(spec.key());
                if (st == null || st.level() < 1) {
                    continue;
                }
                int toLevel = st.level() + 1;
                if (toLevel > catalog.maxBuildingLevel(spec.key())) {
                    continue;
                }
                GameCatalog.Cost upCost = catalog.upgradeCost(spec.key(), toLevel);
                if (upCost == null) {
                    continue;
                }
                if ("TOWN_HALL".equals(spec.key())) {
                    text.append("🏠 ").append(spec.title()).append(" ур.").append(st.level()).append(" → ур.").append(toLevel).append("\n");
                    text.append("Нужно: ").append(costText(upCost)).append("\n");
                    text.append("Откроет: ").append(catalog.townHallUnlocks(toLevel)).append("\n");
                    text.append(playerResourcesLine(resources, upCost)).append("\n\n");
                } else {
                    text.append(spec.emoji()).append(" ").append(spec.title()).append(" ур.").append(st.level()).append("\n");
                    text.append("⬆️ До ур.").append(toLevel).append(" стоит: ").append(costText(upCost)).append("\n");
                    text.append(playerResourcesLine(resources, upCost)).append("\n\n");
                }
                boolean enough = hasEnough(resources, upCost);
                rows.add(List.of(btn((enough ? "⬆️ " : "⛔ ") + spec.title() + " → ур." + toLevel, enough ? "build:up:" + spec.key() : "build:blocked:up:" + spec.key())));
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

        int morale = gameDao.loadMorale(player.id());
        if (morale < 50) {
            sendText(chatId, moraleRestrictionText(morale));
            return;
        }

        if ("blocked".equals(p[1]) && p.length >= 4) {
            String key = p[3];
            GameCatalog.BuildingSpec spec = catalog.buildings().get(key);
            if (spec == null) {
                return;
            }
            GameCatalog.Cost cost;
            if ("new".equals(p[2])) {
                cost = spec.cost();
            } else {
                BuildingState st = gameDao.loadBuilding(player.id(), key).orElse(null);
                if (st == null) return;
                cost = catalog.upgradeCost(key, st.level() + 1);
            }
            if (cost == null) return;
            sendText(chatId, "❌ Не хватает: " + missingResourcesLine(gameDao.loadResources(player.id()), cost));
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
                sendText(chatId, "❌ Не хватает: " + missingResourcesLine(gameDao.loadResources(player.id()), spec.cost()), menuService.mainMenu());
                return;
            }
            gameDao.buildInstant(player.id(), key);
            gameDao.changeMorale(player.id(), 5);
            sendText(chatId, "Строительство: " + spec.title(), menuService.mainMenu());
            return;
        }

        BuildingState current = gameDao.loadBuilding(player.id(), key).orElse(null);
        if (current == null) {
            sendText(chatId, "Сначала построй здание.", menuService.mainMenu());
            return;
        }
        GameCatalog.Cost upCost = catalog.upgradeCost(key, current.level() + 1);
        if (upCost == null) {
            sendText(chatId, "Здание уже максимального уровня.", menuService.mainMenu());
            return;
        }
        if (!gameDao.spendResources(player.id(), upCost)) {
            sendText(chatId, "❌ Не хватает: " + missingResourcesLine(gameDao.loadResources(player.id()), upCost), menuService.mainMenu());
            return;
        }
        gameDao.upgradeBuildingInstant(player.id(), key);
        if ("TOWN_HALL".equals(key)) {
            int newCityLevel = Math.min(7, current.level() + 1);
            gameDao.setCityLevel(player.id(), newCityLevel);
            sendCityLevelUpMedia(chatId, newCityLevel);
        }
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
            gameDao.upsertArmy(unit.key(), unit.power(), player.id(), qty);
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
        Map<String, BuildingState> bmap = gameDao.loadBuildingMap(player.id());
        int mineLvl = bmap.getOrDefault("MINE", new BuildingState("MINE", 0)).level();
        int farmLvl = bmap.getOrDefault("FARM", new BuildingState("FARM", 0)).level();
        int lumberLvl = bmap.getOrDefault("LUMBERMILL", new BuildingState("LUMBERMILL", 0)).level();
        int tavernLvl = bmap.getOrDefault("TAVERN", new BuildingState("TAVERN", 0)).level();
        int templeLvl = bmap.getOrDefault("TEMPLE", new BuildingState("TEMPLE", 0)).level();
        int marketLvl = bmap.getOrDefault("MARKET", new BuildingState("MARKET", 0)).level();
        boolean mineActive = gameDao.isPassiveBuildingActive(player.id(), "MINE");
        boolean farmActive = gameDao.isPassiveBuildingActive(player.id(), "FARM");
        boolean lumberActive = gameDao.isPassiveBuildingActive(player.id(), "LUMBERMILL");
        long now = Instant.now().toEpochMilli();
        long nextTickMs = nextPassiveTickEpoch(now);

        int woodTick = 5 + (lumberActive ? lumbermillBonus(lumberLvl) : 0);
        int stoneTick = 3 + (mineActive ? mineStoneBonus(mineLvl) : 0);
        int foodTick = 4 + (farmActive ? farmBonus(farmLvl) : 0);
        int ironTick = 2 + (mineActive ? mineIronBonus(mineLvl) : 0);
        int goldTick = 1 + marketBonus(marketLvl);
        int manaTick = templeBonus(templeLvl);
        int alcoholTick = tavernBonus(tavernLvl);

        StringBuilder text = new StringBuilder();
        text.append("Что добываешь?\n");
        text.append("[ 🪵 Ресурсы ] [ 💰 Золото ]\n\n");
        text.append("Базовый пассив (каждые 10 мин): 🪵+5 🪨+3 🌾+4 ⚔️+2 💰+1\n");
        text.append("💰 Золото за активную добычу: 30-80");
        if (marketLvl >= 2) {
            text.append(" (+50% от рынка ур.2+)");
        }
        text.append("\n\n");

        text.append("⛏️ Шахта ур.").append(mineLvl).append("\n");
        text.append("Статус: ").append(mineActive ? "✅ Активна" : "❌ Не активна").append("\n");
        if (mineActive) {
            text.append("Добывает каждые 10 мин:\n");
            text.append("⚔️ Железо: +").append(ironTick).append("\n");
            text.append("🪨 Камень: +").append(stoneTick).append("\n");
        }

        text.append("\n🌾 Ферма ур.").append(farmLvl).append("\n");
        text.append("Статус: ").append(farmActive ? "✅ Активна" : "❌ Не активна").append("\n");
        if (farmActive) {
            text.append("Добывает каждые 10 мин:\n");
            text.append("🌾 Еда: +").append(foodTick).append("\n");
        }
        text.append("\n🪵 Лесопилка ур.").append(lumberLvl).append("\n");
        text.append("Статус: ").append(lumberActive ? "✅ Активна" : "❌ Не активна").append("\n");
        if (lumberActive) {
            text.append("Добывает каждые 10 мин:\n");
            text.append("🪵 Дерево: +").append(woodTick).append("\n");
        }
        text.append("\n🍺 Таверна ур.").append(tavernLvl).append(" → +").append(alcoholTick).append(" алкоголя / 10 мин");
        text.append("\n🧪 Храм ур.").append(templeLvl).append(" → +").append(manaTick).append(" манны / 10 мин");
        text.append("\n🏦 Рынок ур.").append(marketLvl).append(" → +").append(goldTick).append(" золота / 10 мин");
        text.append("Следующая добыча через: ").append(harvestCooldownText(Math.max(0L, nextTickMs - now)).replace("⏳ Следующая добыча через: ", "")).append("\n");

        Long cd = gameDao.getPlayerState(player.id(), "MINE_COOLDOWN_UNTIL");
        if (cd != null && cd > now) {
            text.append("\n").append(harvestCooldownText(cd - now)).append("\n");
        }

        sendText(chatId, text.toString(), InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(btn(mineActive ? "⚙️ Деактивировать шахту" : "✅ Активировать шахту", "mine:toggle:MINE")))
                .keyboardRow(List.of(btn(farmActive ? "⚙️ Деактивировать ферму" : "✅ Активировать ферму", "mine:toggle:FARM")))
                .keyboardRow(List.of(btn(lumberActive ? "⚙️ Деактивировать лесопилку" : "✅ Активировать лесопилку", "mine:toggle:LUMBERMILL")))
                .keyboardRow(List.of(btn("🪵 Ресурсы", "mine:collect:RESOURCES"), btn("💰 Золото", "mine:collect:GOLD")))
                .keyboardRow(List.of(btn("◀️ Назад", "menu:city")))
                .build());
    }

    private void handleMineCallbacks(long chatId, PlayerRecord player, String data) {
        if (data.startsWith("mine:toggle:")) {
            String building = data.substring("mine:toggle:".length());
            boolean active = gameDao.isPassiveBuildingActive(player.id(), building);
            gameDao.setPassiveBuildingActive(player.id(), building, !active);
            showMineMenu(chatId, player);
            return;
        }
        if (!data.startsWith("mine:collect")) {
            showMineMenu(chatId, player);
            return;
        }
        Long cd = gameDao.getPlayerState(player.id(), "MINE_COOLDOWN_UNTIL");
        long now = Instant.now().toEpochMilli();
        if (cd != null && cd > now) {
            sendText(chatId, harvestCooldownText(cd - now));
            return;
        }

        String mode = "RESOURCES";
        String[] parts = data.split(":");
        if (parts.length >= 3) {
            mode = parts[2];
        }

        Map<String, BuildingState> bmap = gameDao.loadBuildingMap(player.id());
        int marketLvl = bmap.getOrDefault("MARKET", new BuildingState("MARKET", 0)).level();
        String msg;
        if ("GOLD".equals(mode)) {
            int gold = rand(30, 80);
            if (marketLvl >= 2) {
                gold = (int) Math.floor(gold * 1.5);
            }
            gameDao.addResources(player.id(), new GameCatalog.Cost(0, 0, 0, 0, gold, 0, 0));
            msg = "✅ Добыто золото:\n💰 +" + gold;
        } else {
            int mineLvl = bmap.getOrDefault("MINE", new BuildingState("MINE", 0)).level();
            int farmLvl = bmap.getOrDefault("FARM", new BuildingState("FARM", 0)).level();
            int lumberLvl = bmap.getOrDefault("LUMBERMILL", new BuildingState("LUMBERMILL", 0)).level();
            boolean lumberActive = gameDao.isPassiveBuildingActive(player.id(), "LUMBERMILL");
            boolean mineActive = gameDao.isPassiveBuildingActive(player.id(), "MINE");
            boolean farmActive = gameDao.isPassiveBuildingActive(player.id(), "FARM");
            DailyEventType event = activeDailyEvent();

            int passiveWood = 5 + (lumberActive ? lumbermillBonus(lumberLvl) : 0);
            int passiveStone = 3 + (mineActive ? mineStoneBonus(mineLvl) : 0);
            int passiveFood = 4 + (farmActive ? farmBonus(farmLvl) : 0);
            int passiveIron = 2 + (mineActive ? mineIronBonus(mineLvl) : 0);
            double eventMul = 1.0;
            if (event == DailyEventType.HARVEST_DAY) {
                eventMul = 1.2;
            }
            int wood = (int) Math.floor(passiveWood * 5 * eventMul);
            int stone = (int) Math.floor(passiveStone * 5 * eventMul);
            int food = (int) Math.floor(passiveFood * 5 * eventMul);
            int iron = (int) Math.floor(passiveIron * 5 * eventMul);

            gameDao.addResources(player.id(), new GameCatalog.Cost(wood, stone, food, iron, 0, 0, 0));
            msg = "✅ Добыто:\n🪵 +" + wood + "\n🪨 +" + stone + "\n🌾 +" + food + "\n⚔️ +" + iron;
        }
        gameDao.setPlayerState(player.id(), "MINE_COOLDOWN_UNTIL", Instant.now().plusSeconds(10 * 60L).toEpochMilli());
        gameDao.changeMorale(player.id(), 5);

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
        rows.add(List.of(btn("🗺️ Атаковать NPC страну", "attack:npcmenu")));
        rows.add(List.of(btn("◀️ Назад", "menu:city")));
        sendText(chatId, "⚔️ Выбери цель для атаки:", InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private void handleAttackCallbacks(long chatId, PlayerRecord attacker, String data) {
        if ("attack:npcmenu".equals(data)) {
            showNpcTargets(chatId, attacker);
            return;
        }
        if (data.startsWith("attack:npcgo:")) {
            String key = data.substring("attack:npcgo:".length());
            resolveNpcAttack(chatId, attacker, key);
            return;
        }
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
            double chance = baseAttackChance(attacker, defender);
            sendText(chatId,
                    "⚔️ Выбери тип атаки на " + defender.villageName() + ":\n" +
                            "━━━━━━━━━━━━\n" +
                            "⚔️ БРОСИТЬ ВЫЗОВ\n" +
                            "Пошаговый бой, враг может принять или отклонить.\n" +
                            "Победа: забираешь 30% ресурсов\n" +
                            "Риск: враг может отбиться тактикой\n\n" +
                            "🎲 БЫСТРЫЙ НАБЕГ\n" +
                            "Мгновенный авто бой по шансам, враг не знает.\n" +
                            "Победа: забираешь 20% ресурсов\n" +
                            "Твой шанс: " + String.format("%.1f", chance) + "%\n" +
                            "━━━━━━━━━━━━",
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

            double holdChance = 100.0 - baseAttackChance(attacker, defender);
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
        gameDao.changeMorale(winner.id(), 10);
        gameDao.changeMorale(loser.id(), -15);

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

        sendBattleSummaryMedia(a.telegramId(), winner.id() == a.id(), buildBattleSummary(
                a,
                winner.id() == a.id(),
                attackerLosses,
                getPlayerPower(a.id()),
                winner.id() == a.id(),
                stolenWood, stolenStone, stolenFood, stolenIron, stolenGold, stolenMana, stolenAlcohol
        ));
        sendBattleSummaryMedia(d.telegramId(), winner.id() == d.id(), buildBattleSummary(
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

    private double baseAttackChance(PlayerRecord attacker, PlayerRecord defender) {
        double chance = baseAttackChance(getPlayerPower(attacker.id()), getPlayerPower(defender.id()));
        int aMorale = gameDao.loadMorale(attacker.id());
        int dMorale = gameDao.loadMorale(defender.id());
        if (aMorale >= 80) {
            chance += 5.0;
        }
        if (dMorale >= 80) {
            chance -= 5.0;
        }
        return Math.max(3.0, Math.min(97.0, chance));
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
                case "FLAMETHROWER" -> gameDao.inventoryQuantity(attacker.id(), "CRAFTED_FLAMETHROWER") > 0;
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
        gameDao.changeMorale(winner.id(), 10);
        gameDao.changeMorale(loser.id(), -15);
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

        sendBattleSummaryMedia(attacker.telegramId(), winner.id() == attacker.id(), buildBattleSummary(
                attacker,
                winner.id() == attacker.id(),
                winner.id() == attacker.id() ? winnerLosses : loserLosses,
                getPlayerPower(attacker.id()),
                winner.id() == attacker.id(),
                lootWood, lootStone, lootFood, 0, lootGold, 0, 0
        ));
        sendBattleSummaryMedia(defender.telegramId(), winner.id() == defender.id(), buildBattleSummary(
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
        ResourcesRecord res = gameDao.loadResources(player.id());
        if (inv.isEmpty()) {
            sendText(chatId,
                    "🎒 ИНВЕНТАРЬ\n" +
                            "Пусто. Как получить предметы:\n" +
                            "━━━━━━━━━━━━\n" +
                            "⛏️ Добывай ресурсы — шанс найти чертёж\n" +
                            "📜 Чертёж → иди в 🔨 Крафт → создай предмет\n" +
                            "⚔️ Предметы используются в бою для бонусов:\n" +
                            "🔫 Пистолет — +15% урон в авто бою\n" +
                            "💣 Пушка — +20% защита в авто бою\n" +
                            "🏹 Арбалет — +10% шанс победы\n" +
                            "🛡️ Броня — надевается и даёт постоянную защиту",
                    InlineKeyboardMarkup.builder()
                            .keyboardRow(List.of(btn("🍺 Поднять мораль (+20)", "inv:morale:drink")))
                            .keyboardRow(List.of(btn("◀️ Назад", "menu:city")))
                            .build());
            return;
        }
        StringBuilder sb = new StringBuilder("🎒 ИНВЕНТАРЬ\n");
        if (player.equippedArmor() != null) {
            sb.append("Надето: ").append(catalog.itemDisplay(player.equippedArmor())).append("\n");
        }
        sb.append("🍺 Алкоголь в ресурсах: ").append(res.alcohol()).append("\n\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        inv.stream().sorted(Comparator.comparing(KeyValueAmount::type)).forEach(it -> {
            sb.append(catalog.itemDisplay(it.type())).append(" x").append(it.quantity()).append("\n");
            sb.append(catalog.itemDescription(it.type())).append("\n");
            if (isArmor(it.type()) && it.quantity() > 0) {
                boolean equipped = it.type().equals(player.equippedArmor());
                sb.append("Статус: ").append(equipped ? "✅ Надета" : "❌ Не надета").append("\n");
                if (!equipped) {
                    rows.add(List.of(btn("✅ Надеть", "inv:equip:armor:" + it.type()), btn("💰 Продать", "inv:sell:" + it.type())));
                } else {
                    rows.add(List.of(btn("💰 Продать", "inv:sell:" + it.type())));
                }
            } else {
                rows.add(List.of(btn("🎲 Использовать в набеге", "inv:raid:" + it.type()), btn("💰 Продать", "inv:sell:" + it.type())));
            }
            rows.add(List.of(btn("🔨 Аукцион", "inv:auction:" + it.type())));
            sb.append("\n");
        });
        rows.add(List.of(btn("🍺 Поднять мораль (+20)", "inv:morale:drink")));
        rows.add(List.of(btn("◀️ Назад", "menu:city")));
        sendText(chatId, sb.toString(), InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private void handleInventoryCallbacks(long chatId, PlayerRecord player, String data) {
        if (data.startsWith("inv:morale:drink")) {
            long todayKey = Long.parseLong(DailyEventType.todayUtc().replace("-", ""));
            Long usedDay = gameDao.getPlayerState(player.id(), "ALCOHOL_USED_DAY");
            if (usedDay == null || usedDay != todayKey) {
                gameDao.setPlayerState(player.id(), "ALCOHOL_USED_DAY", todayKey);
                gameDao.setPlayerState(player.id(), "ALCOHOL_USED_COUNT", 0);
            }
            long used = gameDao.getPlayerState(player.id(), "ALCOHOL_USED_COUNT") == null ? 0 : gameDao.getPlayerState(player.id(), "ALCOHOL_USED_COUNT");
            ResourcesRecord r = gameDao.loadResources(player.id());
            if (used >= 3) {
                sendText(chatId, "Сегодня лимит выпивки исчерпан (3/3).", menuService.mainMenu());
                return;
            }
            if (r.alcohol() <= 0) {
                sendText(chatId, "Нужно минимум 1 🍺 алкоголя.", menuService.mainMenu());
                return;
            }
            gameDao.spendResources(player.id(), new GameCatalog.Cost(0, 0, 0, 0, 0, 0, 1));
            int morale = gameDao.changeMorale(player.id(), 20);
            gameDao.setPlayerState(player.id(), "ALCOHOL_USED_COUNT", used + 1);
            gameDao.touchActivity(player.id());
            sendText(chatId, "🍺 Боевой дух поднят! Мораль: " + morale + "/100", menuService.mainMenu());
            return;
        }
        if (data.startsWith("inv:sell:")) {
            String item = data.substring("inv:sell:".length());
            if (!gameDao.removeInventoryItem(player.id(), item, 1)) {
                sendText(chatId, "Этого предмета нет в инвентаре.", menuService.mainMenu());
                return;
            }
            int price = sellPrice(item);
            gameDao.addResources(player.id(), new GameCatalog.Cost(0, 0, 0, 0, price, 0, 0));
            sendText(chatId, "💰 Продано за " + price + " золота.", menuService.mainMenu());
            return;
        }
        if (data.startsWith("inv:auction:")) {
            String item = data.substring("inv:auction:".length());
            if (!gameDao.removeInventoryItem(player.id(), item, 1)) {
                sendText(chatId, "Этого предмета нет в инвентаре.", menuService.mainMenu());
                return;
            }
            int startPrice = Math.max(100, sellPrice(item));
            gameDao.createAuction(player.id(), item, startPrice, Instant.now().plusSeconds(12 * 3600L).toEpochMilli());
            sendText(chatId, "🔨 Лот выставлен на аукцион. Старт: " + startPrice + "💰", menuService.mainMenu());
            return;
        }
        if (data.startsWith("inv:raid:")) {
            String item = data.substring("inv:raid:".length());
            sendText(chatId,
                    "Предмет " + catalog.itemDisplay(item) + " готов к использованию.\n" +
                            "Открой ⚔️ Атаковать → выбери цель → 🎲 Быстрый набег.",
                    menuService.mainMenu());
            return;
        }
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
        StringBuilder txt = new StringBuilder("🔨 МАСТЕРСКАЯ\n━━━━━━━━━━━━\n");
        txt.append("ОБЫЧНЫЕ:\n");
        txt.append(recipeLine(player.id(), "BLUEPRINT_WOODEN_SHIELD", "CRAFTED_WOODEN_SHIELD", new GameCatalog.Cost(30, 0, 0, 10, 0, 0, 0))).append("\n");
        txt.append(recipeLine(player.id(), "BLUEPRINT_SIMPLE_BOW", "CRAFTED_SIMPLE_BOW", new GameCatalog.Cost(40, 0, 0, 15, 0, 0, 0))).append("\n");
        txt.append(recipeLine(player.id(), "BLUEPRINT_LEATHER_VEST", "LEATHER_VEST_ARMOR", new GameCatalog.Cost(20, 0, 0, 10, 0, 0, 0))).append("\n\n");
        txt.append("РЕДКИЕ:\n");
        txt.append(recipeLine(player.id(), "BLUEPRINT_PISTOL", "CRAFTED_PISTOL", new GameCatalog.Cost(0, 0, 0, 50, 30, 0, 0))).append("\n");
        txt.append(recipeLine(player.id(), "BLUEPRINT_CROSSBOW", "CRAFTED_CROSSBOW", new GameCatalog.Cost(80, 0, 0, 40, 0, 0, 0))).append("\n");
        txt.append(recipeLine(player.id(), "BLUEPRINT_CATAPULT", "CRAFTED_CATAPULT", new GameCatalog.Cost(60, 100, 0, 80, 0, 0, 0))).append("\n\n");
        txt.append("ЛЕГЕНДАРНЫЕ:\n");
        txt.append(recipeLine(player.id(), "BLUEPRINT_CANNON", "CRAFTED_CANNON", new GameCatalog.Cost(100, 150, 0, 120, 0, 0, 0))).append("\n");
        txt.append(recipeLine(player.id(), "BLUEPRINT_FLAMETHROWER", "CRAFTED_FLAMETHROWER", new GameCatalog.Cost(0, 0, 0, 200, 100, 50, 0))).append("\n");
        txt.append(recipeLine(player.id(), "BLUEPRINT_MITHRIL_ARMOR", "MITHRIL_ARMOR", new GameCatalog.Cost(0, 0, 0, 300, 200, 100, 0))).append("\n\n");
        txt.append("Твои ресурсы: ").append(costText(new GameCatalog.Cost(r.wood(), r.stone(), r.food(), r.iron(), r.gold(), r.mana(), r.alcohol()))).append("\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        addCraftButtonIfAvailable(rows, player.id(), "BLUEPRINT_WOODEN_SHIELD", "🔨 Щит", "WOODEN_SHIELD", new GameCatalog.Cost(30, 0, 0, 10, 0, 0, 0));
        addCraftButtonIfAvailable(rows, player.id(), "BLUEPRINT_SIMPLE_BOW", "🔨 Лук", "SIMPLE_BOW", new GameCatalog.Cost(40, 0, 0, 15, 0, 0, 0));
        addCraftButtonIfAvailable(rows, player.id(), "BLUEPRINT_LEATHER_VEST", "🔨 Жилет", "LEATHER_VEST", new GameCatalog.Cost(20, 0, 0, 10, 0, 0, 0));
        addCraftButtonIfAvailable(rows, player.id(), "BLUEPRINT_PISTOL", "🔨 Пистолет", "PISTOL", new GameCatalog.Cost(0, 0, 0, 50, 30, 0, 0));
        addCraftButtonIfAvailable(rows, player.id(), "BLUEPRINT_CROSSBOW", "🔨 Арбалет", "CROSSBOW", new GameCatalog.Cost(80, 0, 0, 40, 0, 0, 0));
        addCraftButtonIfAvailable(rows, player.id(), "BLUEPRINT_CATAPULT", "🔨 Катапульта", "CATAPULT", new GameCatalog.Cost(60, 100, 0, 80, 0, 0, 0));
        addCraftButtonIfAvailable(rows, player.id(), "BLUEPRINT_CANNON", "🔨 Пушка", "CANNON", new GameCatalog.Cost(100, 150, 0, 120, 0, 0, 0));
        addCraftButtonIfAvailable(rows, player.id(), "BLUEPRINT_FLAMETHROWER", "🔨 Огнемёт", "FLAMETHROWER", new GameCatalog.Cost(0, 0, 0, 200, 100, 50, 0));
        addCraftButtonIfAvailable(rows, player.id(), "BLUEPRINT_MITHRIL_ARMOR", "🔨 Мифриловая броня", "MITHRIL", new GameCatalog.Cost(0, 0, 0, 300, 200, 100, 0));
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
            case "WOODEN_SHIELD" -> craftItem(chatId, player, "BLUEPRINT_WOODEN_SHIELD", "CRAFTED_WOODEN_SHIELD", new GameCatalog.Cost(30, 0, 0, 10, 0, 0, 0));
            case "SIMPLE_BOW" -> craftItem(chatId, player, "BLUEPRINT_SIMPLE_BOW", "CRAFTED_SIMPLE_BOW", new GameCatalog.Cost(40, 0, 0, 15, 0, 0, 0));
            case "LEATHER_VEST" -> craftItem(chatId, player, "BLUEPRINT_LEATHER_VEST", "LEATHER_VEST_ARMOR", new GameCatalog.Cost(20, 0, 0, 10, 0, 0, 0));
            case "PISTOL" -> {
                craftItem(chatId, player, "BLUEPRINT_PISTOL", "CRAFTED_PISTOL", new GameCatalog.Cost(0, 0, 0, 50, 30, 0, 0));
            }
            case "CANNON" -> {
                craftItem(chatId, player, "BLUEPRINT_CANNON", "CRAFTED_CANNON", new GameCatalog.Cost(100, 150, 0, 120, 0, 0, 0));
            }
            case "CROSSBOW" -> {
                craftItem(chatId, player, "BLUEPRINT_CROSSBOW", "CRAFTED_CROSSBOW", new GameCatalog.Cost(80, 0, 0, 40, 0, 0, 0));
            }
            case "CATAPULT" -> craftItem(chatId, player, "BLUEPRINT_CATAPULT", "CRAFTED_CATAPULT", new GameCatalog.Cost(60, 100, 0, 80, 0, 0, 0));
            case "FLAMETHROWER" -> craftItem(chatId, player, "BLUEPRINT_FLAMETHROWER", "CRAFTED_FLAMETHROWER", new GameCatalog.Cost(0, 0, 0, 200, 100, 50, 0));
            case "MITHRIL" -> craftItem(chatId, player, "BLUEPRINT_MITHRIL_ARMOR", "MITHRIL_ARMOR", new GameCatalog.Cost(0, 0, 0, 300, 200, 100, 0));
            default -> showCraftMenu(chatId, player);
        }
    }

    private void maybeDropBlueprint(PlayerRecord player) {
        double roll = ThreadLocalRandom.current().nextDouble();
        String found;
        String rarity;
        if (roll < 0.003) {
            rarity = "LEGENDARY";
            String[] pool = {"BLUEPRINT_CANNON", "BLUEPRINT_FLAMETHROWER", "BLUEPRINT_MITHRIL_ARMOR"};
            found = pool[ThreadLocalRandom.current().nextInt(pool.length)];
        } else if (roll < 0.013) {
            rarity = "RARE";
            String[] pool = {"BLUEPRINT_PISTOL", "BLUEPRINT_CROSSBOW", "BLUEPRINT_CATAPULT"};
            found = pool[ThreadLocalRandom.current().nextInt(pool.length)];
        } else if (roll < 0.043) {
            rarity = "COMMON";
            String[] pool = {"BLUEPRINT_WOODEN_SHIELD", "BLUEPRINT_SIMPLE_BOW", "BLUEPRINT_LEATHER_VEST"};
            found = pool[ThreadLocalRandom.current().nextInt(pool.length)];
        } else {
            return;
        }
        gameDao.appendDailyLog("RARE", player.villageName() + " нашёл " + catalog.itemDisplay(found));
        pendingLootByPlayer.put(player.id(), found);
        String text = switch (rarity) {
            case "LEGENDARY" -> "📕🔥 ЛЕГЕНДАРНАЯ НАХОДКА!!\n" + catalog.itemDisplay(found);
            case "RARE" -> "📘✨ РЕДКАЯ НАХОДКА!\n" + catalog.itemDisplay(found);
            default -> "📜 Ты нашёл чертёж!\n" + catalog.itemDisplay(found);
        };
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(btn("🔨 Сохранить для крафта", "loot:save:" + found), btn("💰 Продать", "loot:sell:" + found)))
                .keyboardRow(List.of(btn("🔨 Аукцион", "loot:auction:" + found)))
                .build();
        sendAnimationResourceWithUrlFallback(
                player.telegramId(),
                null,
                null,
                null,
                text,
                keyboard
        );
        if ("LEGENDARY".equals(rarity)) {
            sendGroupMessageAsync("📕🔥 " + player.villageName() + " нашёл легендарный чертёж: " + catalog.itemDisplay(found) + "!!");
        }
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
        sendAnimationResourceWithUrlFallback(player.telegramId(),
                null,
                null,
                null,
                "🛡️ Ты нашёл " + catalog.itemDisplay(found) + "!\n" +
                        "Бонус защиты: +" + (int) (armorBonus(found) * 100) + "%",
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
        if (gameDao.inventoryQuantity(attacker.id(), "CRAFTED_FLAMETHROWER") > 0) rows.add(List.of(btn("🔥 Огнемёт +30% урон", "attack:raiditem:" + defender.id() + ":FLAMETHROWER")));
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
        if ("FLAMETHROWER".equals(raidItem)) attEff *= 1.30;
        double chance = (attEff / (attEff + defEff)) * 100.0;
        int aMorale = gameDao.loadMorale(attacker.id());
        int dMorale = gameDao.loadMorale(defender.id());
        if (aMorale >= 80) {
            chance += 5.0;
        }
        if (dMorale >= 80) {
            chance -= 5.0;
        }
        return Math.max(3.0, Math.min(97.0, chance));
    }

    private void consumeRaidItem(long playerId, String item) {
        switch (item) {
            case "PISTOL" -> gameDao.removeInventoryItem(playerId, "CRAFTED_PISTOL", 1);
            case "CANNON" -> gameDao.removeInventoryItem(playerId, "CRAFTED_CANNON", 1);
            case "CROSSBOW" -> gameDao.removeInventoryItem(playerId, "CRAFTED_CROSSBOW", 1);
            case "FLAMETHROWER" -> gameDao.removeInventoryItem(playerId, "CRAFTED_FLAMETHROWER", 1);
            default -> {
            }
        }
    }

    private String raidItemLabel(String item) {
        return switch (item) {
            case "PISTOL" -> "Пистолет";
            case "CANNON" -> "Пушка";
            case "CROSSBOW" -> "Усиленный арбалет";
            case "FLAMETHROWER" -> "Огнемёт";
            default -> "Без предмета";
        };
    }

    private List<NpcTarget> npcTargets() {
        return List.of(
                new NpcTarget("BARBARIANS", "🏕️ Деревня варваров", 90.0, 20, 50, 50, 100, 0, 0, 0, 0, 5, 10),
                new NpcTarget("NOMADS", "🏘️ Посёлок кочевников", 70.0, 80, 150, 0, 0, 20, 40, 0, 0, 10, 20),
                new NpcTarget("TRADERS", "🏙️ Город торговцев", 50.0, 300, 500, 0, 0, 50, 80, 0, 0, 20, 30),
                new NpcTarget("EAST_EMPIRE", "🏯 Империя востока", 30.0, 500, 1000, 0, 0, 200, 260, 50, 50, 30, 50),
                new NpcTarget("WORLD_POWER", "👑 Мировая держава", 10.0, 2000, 5000, 0, 0, 500, 700, 200, 240, 50, 80)
        );
    }

    private void showNpcTargets(long chatId, PlayerRecord attacker) {
        int power = getPlayerPower(attacker.id());
        StringBuilder sb = new StringBuilder("🗺️ СОСЕДНИЕ СТРАНЫ\nВыбери кого атаковать:\n━━━━━━━━━━━━\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (NpcTarget t : npcTargets()) {
            double chance = npcChance(t, power);
            sb.append(t.title()).append("\n")
                    .append("Шанс победы: ").append(String.format("%.1f", chance)).append("%\n")
                    .append("Награда: ").append(npcRewardText(t)).append("\n")
                    .append("Потери армии: ").append(t.lossMin()).append("-").append(t.lossMax()).append("%\n\n");
            rows.add(List.of(btn("⚔️ " + t.title(), "attack:npcgo:" + t.key())));
        }
        sb.append("━━━━━━━━━━━━\n⚠️ Потери армии в любом случае!");
        rows.add(List.of(btn("◀️ Назад", "menu:attack")));
        sendText(chatId, sb.toString(), InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private double npcChance(NpcTarget target, int attackerPower) {
        double bonus = Math.min(20.0, (attackerPower / 1000.0) * 5.0);
        return Math.max(3.0, Math.min(97.0, target.baseChance() + bonus));
    }

    private String npcRewardText(NpcTarget target) {
        List<String> parts = new ArrayList<>();
        parts.add("💰" + target.goldMin() + "-" + target.goldMax());
        if (target.woodMax() > 0) {
            parts.add("🪵" + target.woodMin() + "-" + target.woodMax());
        }
        if (target.ironMax() > 0) {
            parts.add("⚔️" + target.ironMin() + "-" + target.ironMax());
        }
        if (target.manaMax() > 0) {
            parts.add("🧪" + target.manaMin() + "-" + target.manaMax());
        }
        if ("WORLD_POWER".equals(target.key())) {
            parts.add("редкий чертёж");
        }
        return String.join(" + ", parts);
    }

    private void resolveNpcAttack(long chatId, PlayerRecord attacker, String key) {
        NpcTarget target = npcTargets().stream().filter(t -> t.key().equals(key)).findFirst().orElse(null);
        if (target == null) {
            sendText(chatId, "Цель не найдена.", menuService.mainMenu());
            return;
        }
        int power = getPlayerPower(attacker.id());
        if (power <= 0) {
            sendText(chatId, "⚠️ У тебя нет армии! Сначала найми воинов.");
            return;
        }
        double chance = npcChance(target, power);
        boolean won = ThreadLocalRandom.current().nextDouble(0.0, 100.0) < chance;
        int lossPercent = rand(target.lossMin(), target.lossMax());
        List<GameDao.ArmyLoss> losses = gameDao.applyArmyLossPercent(attacker.id(), lossPercent);

        int gold = 0;
        int wood = 0;
        int iron = 0;
        int mana = 0;
        if (won) {
            gold = rand(target.goldMin(), target.goldMax());
            if (target.woodMax() > 0) wood = rand(target.woodMin(), target.woodMax());
            if (target.ironMax() > 0) iron = rand(target.ironMin(), target.ironMax());
            if (target.manaMax() > 0) mana = rand(target.manaMin(), target.manaMax());
            gameDao.addResources(attacker.id(), new GameCatalog.Cost(wood, 0, 0, iron, gold, mana, 0));
            gameDao.changeMorale(attacker.id(), 10);
            if ("WORLD_POWER".equals(target.key())) {
                gameDao.addInventoryItem(attacker.id(), "BLUEPRINT_CANNON", 1);
            }
        } else {
            gameDao.changeMorale(attacker.id(), -15);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🗺️ Атака на ").append(target.title()).append("\n");
        sb.append(won ? "✅ Победа!\n" : "❌ Поражение.\n");
        sb.append("Потери армии: ").append(lossPercent).append("%\n");
        if (won) {
            sb.append("Добыча: ").append(costText(new GameCatalog.Cost(wood, 0, 0, iron, gold, mana, 0))).append("\n");
            if ("WORLD_POWER".equals(target.key())) {
                sb.append("📘 Получен редкий чертёж!\n");
            }
        }
        if (!losses.isEmpty()) {
            sb.append("Потери отрядов: ");
            for (GameDao.ArmyLoss l : losses) {
                sb.append(l.unitType()).append(" -").append(l.lost()).append(" ");
            }
            sb.append("\n");
        }
        sendText(chatId, sb.toString(), menuService.mainMenu());

        if (won && ("EAST_EMPIRE".equals(target.key()) || "WORLD_POWER".equals(target.key()))) {
            sendGroupMessageAsync(
                    "⚔️ ПОДВИГ!\n" +
                            attacker.villageName() + " разгромил " + target.title() + "!\n" +
                            "Добыча: 💰" + gold + " ⚔️" + iron + " 🧪" + mana
            );
        }
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
        rows.add(List.of(btn("🔄 Обмен ресурсами", "trade:exchange"), btn("📣 Активные аукционы", "trade:auctions")));
        if (isMerchantActive()) {
            rows.add(List.of(btn("🧙 Торговец", "trade:merchant")));
        }
        rows.add(List.of(btn("◀️ Назад", "menu:city")));
        sendText(chatId, txt.toString(), InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private void handleTradeCallbacks(long chatId, PlayerRecord player, String data) {
        String[] p = data.split(":");
        if (p.length >= 2 && "exchange".equals(p[1])) {
            handleExchangeCallbacks(chatId, player, p);
            return;
        }
        if (p.length >= 2 && "merchant".equals(p[1])) {
            handleMerchantCallbacks(chatId, player, p);
            return;
        }
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

    private void handleExchangeCallbacks(long chatId, PlayerRecord player, String[] p) {
        if (p.length == 2) {
            showExchangeBoard(chatId, player);
            return;
        }
        if (p.length >= 3 && "new".equals(p[2])) {
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            for (String r : RESOURCES) {
                rows.add(List.of(btn("Отдаю " + resourceEmoji(r), "trade:exchange:give_res:" + r)));
            }
            rows.add(List.of(btn("◀️ Назад", "trade:exchange")));
            sendText(chatId, "Выбери ресурс, который отдаёшь:", InlineKeyboardMarkup.builder().keyboard(rows).build());
            return;
        }
        if (p.length >= 4 && "give_res".equals(p[2])) {
            String res = p[3];
            if (!RESOURCES.contains(res)) return;
            gameDao.setPlayerState(player.id(), "TRADE_GIVE_RES_IDX", RESOURCES.indexOf(res));
            sendAmountSelector(chatId, "Сколько отдаёшь " + resourceEmoji(res) + "?", "trade:exchange:give_amt:");
            return;
        }
        if (p.length >= 4 && "give_amt".equals(p[2])) {
            int amount = Integer.parseInt(p[3]);
            gameDao.setPlayerState(player.id(), "TRADE_GIVE_AMOUNT", amount);
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            for (String r : RESOURCES) {
                rows.add(List.of(btn("Хочу " + resourceEmoji(r), "trade:exchange:want_res:" + r)));
            }
            rows.add(List.of(btn("◀️ Назад", "trade:exchange")));
            sendText(chatId, "Выбери ресурс, который хочешь получить:", InlineKeyboardMarkup.builder().keyboard(rows).build());
            return;
        }
        if (p.length >= 4 && "want_res".equals(p[2])) {
            String res = p[3];
            if (!RESOURCES.contains(res)) return;
            gameDao.setPlayerState(player.id(), "TRADE_WANT_RES_IDX", RESOURCES.indexOf(res));
            sendAmountSelector(chatId, "Сколько хочешь получить " + resourceEmoji(res) + "?", "trade:exchange:want_amt:");
            return;
        }
        if (p.length >= 4 && "want_amt".equals(p[2])) {
            int wantAmount = Integer.parseInt(p[3]);
            Long giveIdx = gameDao.getPlayerState(player.id(), "TRADE_GIVE_RES_IDX");
            Long giveAmount = gameDao.getPlayerState(player.id(), "TRADE_GIVE_AMOUNT");
            Long wantIdx = gameDao.getPlayerState(player.id(), "TRADE_WANT_RES_IDX");
            if (giveIdx == null || giveAmount == null || wantIdx == null) {
                sendText(chatId, "Сначала выбери параметры предложения.", menuService.mainMenu());
                return;
            }
            String giveRes = RESOURCES.get(giveIdx.intValue());
            String wantRes = RESOURCES.get(wantIdx.intValue());
            if (giveRes.equals(wantRes)) {
                sendText(chatId, "Ресурсы должны отличаться.", menuService.mainMenu());
                return;
            }
            if (gameDao.countActiveTradeOffers(player.id()) >= 3) {
                sendText(chatId, "У тебя максимум 3 активных предложения.", menuService.mainMenu());
                return;
            }
            if (!gameDao.spendSingleResource(player.id(), giveRes, giveAmount.intValue())) {
                sendText(chatId, "Недостаточно ресурсов для создания предложения.", menuService.mainMenu());
                return;
            }
            long expiresAt = Instant.now().plusSeconds(24L * 3600L).toEpochMilli();
            gameDao.createTradeOffer(player.id(), giveRes, giveAmount.intValue(), wantRes, wantAmount, expiresAt);
            sendText(chatId,
                    "✅ Предложение создано:\nОтдаю: " + resourceEmoji(giveRes) + giveAmount + "\nХочу: " + resourceEmoji(wantRes) + wantAmount,
                    menuService.mainMenu());
            return;
        }
        if (p.length >= 4 && "accept".equals(p[2])) {
            long offerId = Long.parseLong(p[3]);
            boolean ok = gameDao.acceptTradeOffer(offerId, player.id());
            if (!ok) {
                sendText(chatId, "Сделка не удалась (возможно, не хватает ресурсов или предложение уже закрыто).", menuService.mainMenu());
                return;
            }
            sendText(chatId, "✅ Сделка завершена!", menuService.mainMenu());
            return;
        }
        showExchangeBoard(chatId, player);
    }

    private void showExchangeBoard(long chatId, PlayerRecord player) {
        List<GameDao.TradeOffer> offers = gameDao.listActiveTradeOffers(20);
        StringBuilder sb = new StringBuilder("🔄 БИРЖА РЕСУРСОВ\n━━━━━━━━━━━━\nАктивные предложения:\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        int i = 1;
        for (GameDao.TradeOffer offer : offers) {
            if (offer.sellerId() == player.id()) {
                continue;
            }
            sb.append(i).append(". ").append(offer.sellerVillage()).append(": ")
                    .append(resourceEmoji(offer.giveResource())).append(offer.giveAmount())
                    .append(" → ")
                    .append(resourceEmoji(offer.wantResource())).append(offer.wantAmount())
                    .append("\n");
            rows.add(List.of(btn("✅ Принять #" + i, "trade:exchange:accept:" + offer.id())));
            i++;
        }
        if (i == 1) {
            sb.append("Пока нет активных предложений.\n");
        }
        sb.append("━━━━━━━━━━━━");
        rows.add(List.of(btn("+ Создать предложение", "trade:exchange:new")));
        rows.add(List.of(btn("◀️ Назад", "menu:trade")));
        sendText(chatId, sb.toString(), InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private void handleMerchantCallbacks(long chatId, PlayerRecord player, String[] p) {
        if (!isMerchantActive()) {
            sendText(chatId, "Торговец сейчас недоступен.", menuService.mainMenu());
            return;
        }
        if (p.length == 2) {
            sendText(chatId,
                    "🧙 СТРАНСТВУЮЩИЙ ТОРГОВЕЦ\n" +
                            "Предложения (только 30 минут):\n" +
                            "🪵1000 за 💰50\n" +
                            "⚔️200 за 💰80\n" +
                            "🧪50 за 💰200",
                    InlineKeyboardMarkup.builder()
                            .keyboardRow(List.of(btn("Купить 🪵1000 за 💰50", "trade:merchant:buy:WOOD")))
                            .keyboardRow(List.of(btn("Купить ⚔️200 за 💰80", "trade:merchant:buy:IRON")))
                            .keyboardRow(List.of(btn("Купить 🧪50 за 💰200", "trade:merchant:buy:MANA")))
                            .keyboardRow(List.of(btn("◀️ Назад", "menu:trade")))
                            .build());
            return;
        }
        if (p.length >= 4 && "buy".equals(p[2])) {
            String type = p[3];
            int goldCost;
            String res;
            int amount;
            switch (type) {
                case "WOOD" -> {
                    goldCost = 50;
                    res = "WOOD";
                    amount = 1000;
                }
                case "IRON" -> {
                    goldCost = 80;
                    res = "IRON";
                    amount = 200;
                }
                case "MANA" -> {
                    goldCost = 200;
                    res = "MANA";
                    amount = 50;
                }
                default -> {
                    sendText(chatId, "Неизвестный товар.", menuService.mainMenu());
                    return;
                }
            }
            if (!gameDao.spendSingleResource(player.id(), "GOLD", goldCost)) {
                sendText(chatId, "Недостаточно золота.", menuService.mainMenu());
                return;
            }
            gameDao.addSingleResource(player.id(), res, amount);
            sendText(chatId, "✅ Покупка успешна: " + resourceEmoji(res) + amount + " за 💰" + goldCost, menuService.mainMenu());
            return;
        }
        sendText(chatId, "Торговец сейчас недоступен.", menuService.mainMenu());
    }

    private boolean isMerchantActive() {
        Optional<String> until = gameDao.getServerState("TRAVEL_MERCHANT_UNTIL");
        if (until.isEmpty()) {
            return false;
        }
        try {
            return Long.parseLong(until.get()) > System.currentTimeMillis();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void sendAmountSelector(long chatId, String title, String prefix) {
        sendText(chatId, title,
                InlineKeyboardMarkup.builder()
                        .keyboardRow(List.of(btn("50", prefix + "50"), btn("100", prefix + "100"), btn("300", prefix + "300")))
                        .keyboardRow(List.of(btn("500", prefix + "500"), btn("1000", prefix + "1000"), btn("2000", prefix + "2000")))
                        .keyboardRow(List.of(btn("◀️ Назад", "trade:exchange")))
                        .build());
    }

    private String resourceEmoji(String resourceKey) {
        return switch (resourceKey) {
            case "WOOD" -> "🪵";
            case "STONE" -> "🪨";
            case "FOOD" -> "🌾";
            case "IRON" -> "⚔️";
            case "GOLD" -> "💰";
            case "MANA" -> "🧪";
            case "ALCOHOL" -> "🍺";
            default -> resourceKey;
        };
    }

    private void touchPlayerActivity(long telegramId) {
        gameDao.findPlayerByTelegramId(telegramId).ifPresent(p -> gameDao.touchActivity(p.id()));
    }

    private boolean hasEnough(ResourcesRecord r, GameCatalog.Cost c) {
        return r.wood() >= c.wood()
                && r.stone() >= c.stone()
                && r.food() >= c.food()
                && r.iron() >= c.iron()
                && r.gold() >= c.gold()
                && r.mana() >= c.mana()
                && r.alcohol() >= c.alcohol();
    }

    private String playerResourcesLine(ResourcesRecord r, GameCatalog.Cost c) {
        List<String> parts = new ArrayList<>();
        if (c.wood() > 0) parts.add("🪵" + r.wood() + " " + (r.wood() >= c.wood() ? "✅" : "❌"));
        if (c.stone() > 0) parts.add("🪨" + r.stone() + " " + (r.stone() >= c.stone() ? "✅" : "❌"));
        if (c.food() > 0) parts.add("🌾" + r.food() + " " + (r.food() >= c.food() ? "✅" : "❌"));
        if (c.iron() > 0) parts.add("⚔️" + r.iron() + " " + (r.iron() >= c.iron() ? "✅" : "❌"));
        if (c.gold() > 0) parts.add("💰" + r.gold() + " " + (r.gold() >= c.gold() ? "✅" : "❌"));
        if (c.mana() > 0) parts.add("🧪" + r.mana() + " " + (r.mana() >= c.mana() ? "✅" : "❌"));
        if (c.alcohol() > 0) parts.add("🍺" + r.alcohol() + " " + (r.alcohol() >= c.alcohol() ? "✅" : "❌"));
        return "У тебя: " + String.join(" ", parts);
    }

    private String missingResourcesLine(ResourcesRecord r, GameCatalog.Cost c) {
        List<String> parts = new ArrayList<>();
        if (r.wood() < c.wood()) parts.add("🪵" + (c.wood() - r.wood()) + " дерева");
        if (r.stone() < c.stone()) parts.add("🪨" + (c.stone() - r.stone()) + " камня");
        if (r.food() < c.food()) parts.add("🌾" + (c.food() - r.food()) + " еды");
        if (r.iron() < c.iron()) parts.add("⚔️" + (c.iron() - r.iron()) + " железа");
        if (r.gold() < c.gold()) parts.add("💰" + (c.gold() - r.gold()) + " золота");
        if (r.mana() < c.mana()) parts.add("🧪" + (c.mana() - r.mana()) + " манны");
        if (r.alcohol() < c.alcohol()) parts.add("🍺" + (c.alcohol() - r.alcohol()) + " алкоголя");
        return String.join(", ", parts);
    }

    private String moraleRestrictionText(int morale) {
        if (morale < 20) {
            return "😫 В деревне бунт! Срочно подними мораль!";
        }
        return "😔 Твои люди в упадке духа. Подними мораль чтобы строить!";
    }

    private String moraleLine(int morale) {
        int filled = Math.max(0, Math.min(10, morale / 10));
        String bar = "█".repeat(filled) + "░".repeat(10 - filled);
        String mood = morale >= 80 ? "😄 Высокий боевой дух" : morale >= 50 ? "😐 Нормально" : morale >= 20 ? "😔 Упадок духа" : "😫 Бунт";
        return mood + "\n😄 Мораль: " + morale + "/100 " + bar;
    }

    private long nextPassiveTickEpoch(long now) {
        long block = 10L * 60L * 1000L;
        return ((now / block) + 1L) * block;
    }

    private String recipeLine(long playerId, String blueprint, String crafted, GameCatalog.Cost cost) {
        int qty = gameDao.inventoryQuantity(playerId, blueprint);
        return catalog.itemDisplay(crafted) + " | " + catalog.itemDisplay(blueprint) + " x" + qty +
                (qty > 0 ? "" : " 🔒") + " | " + costText(cost);
    }

    private void addCraftButtonIfAvailable(List<List<InlineKeyboardButton>> rows, long playerId, String blueprint, String title, String action, GameCatalog.Cost cost) {
        if (gameDao.inventoryQuantity(playerId, blueprint) > 0 && hasEnough(gameDao.loadResources(playerId), cost)) {
            rows.add(List.of(btn(title, "craft:make:" + action)));
        }
    }

    private void craftItem(long chatId, PlayerRecord player, String blueprint, String crafted, GameCatalog.Cost cost) {
        if (gameDao.inventoryQuantity(player.id(), blueprint) <= 0) {
            sendText(chatId, "Нужен чертёж: " + catalog.itemDisplay(blueprint), menuService.mainMenu());
            return;
        }
        if (!gameDao.spendResources(player.id(), cost)) {
            sendText(chatId, "Недостаточно ресурсов.", menuService.mainMenu());
            return;
        }
        gameDao.addInventoryItem(player.id(), crafted, 1);
        sendText(chatId, "✅ Скрафчено: " + catalog.itemDisplay(crafted), menuService.mainMenu());
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
            case "BLUEPRINT_WOODEN_SHIELD", "BLUEPRINT_SIMPLE_BOW", "BLUEPRINT_LEATHER_VEST" -> 80;
            case "BLUEPRINT_PISTOL", "BLUEPRINT_CROSSBOW", "BLUEPRINT_CATAPULT" -> 160;
            case "BLUEPRINT_CANNON", "BLUEPRINT_FLAMETHROWER", "BLUEPRINT_MITHRIL_ARMOR" -> 320;
            case "CRAFTED_WOODEN_SHIELD", "CRAFTED_SIMPLE_BOW" -> 120;
            case "LEATHER_VEST_ARMOR" -> 100;
            case "LEATHER_ARMOR" -> 80;
            case "CHAINMAIL_ARMOR" -> 140;
            case "IRON_ARMOR" -> 220;
            case "GOLD_ARMOR" -> 500;
            case "DIAMOND_ARMOR" -> 800;
            case "NETHERITE_ARMOR" -> 1500;
            case "MITHRIL_ARMOR" -> 1300;
            case "CRAFTED_PISTOL", "CRAFTED_CROSSBOW" -> 250;
            case "CRAFTED_CANNON", "CRAFTED_FLAMETHROWER", "CRAFTED_CATAPULT" -> 500;
            default -> 100;
        };
    }

    private int rand(int from, int to) {
        return ThreadLocalRandom.current().nextInt(from, to + 1);
    }

    private int lumbermillBonus(int level) {
        return switch (level) {
            case 1 -> 8;
            case 2 -> 15;
            case 3 -> 25;
            default -> 0;
        };
    }

    private int mineStoneBonus(int level) {
        return switch (level) {
            case 1 -> 4;
            case 2 -> 8;
            case 3 -> 12;
            default -> 0;
        };
    }

    private int mineIronBonus(int level) {
        return switch (level) {
            case 1 -> 3;
            case 2 -> 6;
            case 3 -> 10;
            default -> 0;
        };
    }

    private int farmBonus(int level) {
        return switch (level) {
            case 1 -> 6;
            case 2 -> 12;
            case 3 -> 20;
            default -> 0;
        };
    }

    private int tavernBonus(int level) {
        return switch (level) {
            case 1 -> 2;
            case 2 -> 5;
            case 3 -> 9;
            default -> 0;
        };
    }

    private int templeBonus(int level) {
        return switch (level) {
            case 1 -> 2;
            case 2 -> 5;
            case 3 -> 9;
            default -> 0;
        };
    }

    private int marketBonus(int level) {
        return switch (level) {
            case 1 -> 2;
            case 2 -> 5;
            case 3 -> 9;
            default -> 0;
        };
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

    private void sendBattleSummaryMedia(long chatId, boolean won, String details) {
        String caption = (won ? "🏆 ПОБЕДА!\n" : "💀 ПОРАЖЕНИЕ...\n") + details;
        sendTextRaw(chatId, caption, null);
    }

    private void sendCityLevelUpMedia(long chatId, int newCityLevel) {
        String text;
        switch (newCityLevel) {
            case 2 -> {
                text = "🏘️ Твоя деревня выросла до Посёлка!";
            }
            case 3 -> {
                text = "🏙️ Поздравляем! Теперь у тебя настоящий Город!";
            }
            case 4 -> {
                text = "🏰 Великолепно! Твой Замок возвышается над землями!";
            }
            case 5 -> {
                text = "👑 Да здравствует Король! Твоё Королевство процветает!";
            }
            default -> {
                text = "🌍 Невероятно! Ты построил Империю! Весь мир трепещет!";
            }
        }
        sendTextRaw(chatId, text, null);
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
        sendTextRaw(chatId, text, keyboard);
    }

    private void sendTextRaw(long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage msg = SendMessage.builder().chatId(String.valueOf(chatId)).text(text).replyMarkup(keyboard).build();
        try {
            execute(msg);
        } catch (Exception e) {
            log.error("Failed to send message", e);
        }
    }

    private boolean isMainMenuKeyboard(InlineKeyboardMarkup keyboard) {
        if (keyboard == null || keyboard.getKeyboard() == null || keyboard.getKeyboard().isEmpty()) {
            return false;
        }
        List<InlineKeyboardButton> firstRow = keyboard.getKeyboard().get(0);
        if (firstRow == null || firstRow.isEmpty()) {
            return false;
        }
        String firstCallback = firstRow.get(0).getCallbackData();
        return "menu:city".equals(firstCallback);
    }

    private void sendPhotoWithFallback(long chatId, String photoUrl, String caption, InlineKeyboardMarkup keyboard) {
        sendTextRaw(chatId, caption, keyboard);
    }

    private void sendPhotoResourceWithUrlFallback(long chatId, String resourcePath, String fileName, String photoUrl, String caption, InlineKeyboardMarkup keyboard) {
        sendTextRaw(chatId, caption, keyboard);
    }

    private void sendPhotoResourceWithUrlFallbacks(long chatId, String resourcePath, String fileName, List<String> photoUrls, String caption, InlineKeyboardMarkup keyboard) {
        sendTextRaw(chatId, caption, keyboard);
    }

    private boolean sendPhotoFromResource(long chatId, String resourcePath, String fileName, String caption, InlineKeyboardMarkup keyboard) {
        sendTextRaw(chatId, caption, keyboard);
        return true;
    }

    private void sendAnimationResourceWithUrlFallback(long chatId, String resourcePath, String fileName, String gifUrl, String caption, InlineKeyboardMarkup keyboard) {
        sendTextRaw(chatId, caption, keyboard);
    }

    private boolean sendAnimationFromResource(long chatId, String resourcePath, String fileName, String caption, InlineKeyboardMarkup keyboard) {
        sendTextRaw(chatId, caption, keyboard);
        return true;
    }

    private String resourceFileName(String resourcePath) {
        return "media.bin";
    }
}
