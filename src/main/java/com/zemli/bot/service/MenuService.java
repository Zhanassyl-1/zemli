package com.zemli.bot.service;

import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.List;

@Service
public class MenuService {

    public InlineKeyboardMarkup mainMenu() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(button("📊 Мой город", "menu:city"), button("⚔️ Атаковать", "menu:attack")))
                .keyboardRow(List.of(button("🏗️ Строить", "menu:build"), button("⚔️ Армия", "menu:army")))
                .keyboardRow(List.of(button("⛏️ Добыть", "menu:mine"), button("🔨 Крафт", "menu:craft")))
                .keyboardRow(List.of(button("🎒 Инвентарь", "menu:inventory"), button("🏦 Торговля", "menu:trade")))
                .keyboardRow(List.of(button("🤝 Альянс", "menu:alliance")))
                .build();
    }

    private InlineKeyboardButton button(String text, String callbackData) {
        return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
    }
}
