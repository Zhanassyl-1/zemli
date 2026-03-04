package com.zemli.bot.service;

import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.List;

@Service
public class MenuService {

    public InlineKeyboardMarkup mainMenu() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(button("⚔️ ВОЙНА", "menu:attack"), button("🛡️ АРМИЯ", "menu:army")))
                .keyboardRow(List.of(button("👤 ПРОФИЛЬ", "menu:profile"), button("🗺️ КАРТА", "MAP")))
                .keyboardRow(List.of(button("💰 ТОРГОВЛЯ", "menu:trade")))
                .build();
    }

    private InlineKeyboardButton button(String text, String callbackData) {
        return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
    }
}
