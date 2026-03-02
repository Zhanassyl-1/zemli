package com.zemli.bot.config;

import com.zemli.bot.bot.ZemliTelegramBot;
import com.zemli.bot.dao.GameDao;
import com.zemli.bot.service.GameCatalog;
import com.zemli.bot.service.MenuService;
import com.zemli.bot.service.RegistrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.util.StringUtils;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeAllGroupChats;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeAllPrivateChats;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.methods.updates.DeleteWebhook;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.List;

@Configuration
public class TelegramBotConfig {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotConfig.class);

    @Bean
    public ZemliTelegramBot zemliTelegramBot(
            @Value("${telegram.bot.token}") String botToken,
            @Value("${telegram.bot.username}") String botUsername,
            @Value("${GROUP_CHAT_ID:0}") Long groupChatId,
            RegistrationService registrationService,
            MenuService menuService,
            GameDao gameDao,
            GameCatalog gameCatalog,
            TaskExecutor taskExecutor
    ) {
        if (!StringUtils.hasText(botToken)) {
            throw new IllegalStateException("telegram.bot.token is empty. Set BOT_TOKEN in Railway variables.");
        }
        if (!StringUtils.hasText(botUsername)) {
            throw new IllegalStateException("telegram.bot.username is empty. Set BOT_USERNAME in Railway variables.");
        }
        return new ZemliTelegramBot(
                botToken,
                botUsername,
                registrationService,
                menuService,
                gameDao,
                gameCatalog,
                taskExecutor,
                groupChatId == null ? 0L : groupChatId
        );
    }

    @Bean
    public CommandLineRunner registerTelegramBotRunner(ZemliTelegramBot bot) {
        return args -> {
            log.info("Bot username configured as: {}", bot.getBotUsername());

            try {
                bot.execute(new DeleteWebhook());
                log.info("Webhook deleted (if existed), using Long Polling");
            } catch (Exception e) {
                throw new IllegalStateException("Failed to delete webhook before Long Polling", e);
            }

            try {
                TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
                botsApi.registerBot(bot);
                log.info("Telegram Long Polling bot registered successfully");
            } catch (Exception e) {
                throw new IllegalStateException("Failed to register Telegram bot (check BOT_TOKEN and bot state)", e);
            }

            long groupChatId = bot.getGroupChatId();
            log.info("GROUP_CHAT_ID value: {}", groupChatId);
            if (groupChatId != 0) {
                try {
                    bot.execute(GetChat.builder().chatId(String.valueOf(groupChatId)).build());
                    log.info("Bot can access group chat {}", groupChatId);
                } catch (Exception e) {
                    log.error("Bot cannot access group chat {}. Ensure bot is a member/admin. Error: {}", groupChatId, e.getMessage());
                }
            } else {
                log.warn("GROUP_CHAT_ID not configured!");
            }

            try {
                bot.execute(SetMyCommands.builder()
                        .scope(new BotCommandScopeAllGroupChats())
                        .commands(List.of(
                                new BotCommand("/top", "🏆 Топ игроков"),
                                new BotCommand("/battles", "⚔️ Последние сражения"),
                                new BotCommand("/stats", "📊 Статистика сервера"),
                                new BotCommand("/factions", "🌍 Рейтинг фракций"),
                                new BotCommand("/me", "👤 Мой профиль"),
                                new BotCommand("/alliances", "🤝 Альянсы"),
                                new BotCommand("/event", "🎯 Событие дня"),
                                new BotCommand("/help", "📋 Все команды")
                        ))
                        .build());

                bot.execute(SetMyCommands.builder()
                        .scope(new BotCommandScopeAllPrivateChats())
                        .commands(List.of(
                                new BotCommand("/start", "🚀 Начать игру"),
                                new BotCommand("/help", "📋 Помощь"),
                                new BotCommand("/getid", "🆔 Показать ID")
                        ))
                        .build());
                log.info("Bot commands registered for group/private scopes");
            } catch (Exception e) {
                log.error("Failed to register bot commands: {}", e.getMessage(), e);
            }
        };
    }
}
