package com.zemli.bot.controller;

import com.zemli.bot.dao.GameDao;
import com.zemli.bot.model.PlayerRecord;
import com.zemli.bot.service.RegistrationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api")
public class MapController {

    private final GameDao gameDao;
    private final RegistrationService registrationService;

    public MapController(GameDao gameDao, RegistrationService registrationService) {
        this.gameDao = gameDao;
        this.registrationService = registrationService;
    }

    @GetMapping("/map")
    public MapData getMap(
            @RequestParam int x,
            @RequestParam int y,
            @RequestParam(defaultValue = "10") int radius,
            @RequestParam long userId
    ) {
        PlayerRecord player = registrationService.findRegistered(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Player not found for userId=" + userId));
        int safeRadius = Math.max(1, Math.min(radius, 30));
        List<MapCellDto> cells = buildSimpleCells(x, y, safeRadius);
        return new MapData(cells);
    }

    @PostMapping("/build")
    public ActionResult build(@RequestBody BuildRequest request) {
        PlayerRecord player = registrationService.findRegistered(request.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Player not found for userId=" + request.userId()));
        gameDao.setPlayerState(player.id(), "LAST_WEB_BUILD_X", request.x());
        gameDao.setPlayerState(player.id(), "LAST_WEB_BUILD_Y", request.y());
        gameDao.setPlayerState(player.id(), "LAST_WEB_BUILD_AT", System.currentTimeMillis());
        return new ActionResult(true, "Команда на строительство отправлена");
    }

    @PostMapping("/move")
    public ActionResult move(@RequestBody MoveRequest request) {
        PlayerRecord player = registrationService.findRegistered(request.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Player not found for userId=" + request.userId()));
        gameDao.setPlayerState(player.id(), "LAST_WEB_MOVE_X", request.x());
        gameDao.setPlayerState(player.id(), "LAST_WEB_MOVE_Y", request.y());
        gameDao.setPlayerState(player.id(), "LAST_WEB_MOVE_UNITS", request.units());
        gameDao.setPlayerState(player.id(), "LAST_WEB_MOVE_AT", System.currentTimeMillis());
        return new ActionResult(true, "Передвижение зафиксировано");
    }

    private List<MapCellDto> buildSimpleCells(int centerX, int centerY, int radius) {
        java.util.ArrayList<MapCellDto> cells = new java.util.ArrayList<>();
        for (int yy = centerY - radius; yy <= centerY + radius; yy++) {
            for (int xx = centerX - radius; xx <= centerX + radius; xx++) {
                String biome = ((Math.abs(xx) + Math.abs(yy)) % 7 == 0) ? "plain" : "forest";
                cells.add(new MapCellDto(xx, yy, biome, null, null, null));
            }
        }
        return cells;
    }

    public record MapData(List<MapCellDto> cells) {
    }

    public record MapCellDto(int x, int y, String biome, String building, String owner, String resource) {}

    public record BuildRequest(long userId, int x, int y, String building) {
    }

    public record MoveRequest(long userId, int x, int y, int units) {
    }

    public record ActionResult(boolean success, String message) {
    }
}
