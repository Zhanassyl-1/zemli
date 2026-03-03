package com.zemli.bot.service;

import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.List;

@Service
public class MenuService {

    public InlineKeyboardMarkup mainMenu() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(button("⚔️ ВОЙНА", "menu:attack"), button("🏰 ГОРОД", "menu:city")))
                .keyboardRow(List.of(button("🛡️ АРМИЯ", "menu:army"), button("👑 ГЕРОИ", "menu:heroes")))
                .keyboardRow(List.of(button("📊 ПРОФИЛЬ", "menu:profile"), button("🏗️ СТРОЙКА", "menu:build")))
                .keyboardRow(List.of(button("⛏️ ДОБЫЧА", "menu:mine"), button("🏦 ТОРГОВЛЯ", "menu:trade")))
                .build();
    }

    private InlineKeyboardButton button(String text, String callbackData) {
        return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
    }
}
