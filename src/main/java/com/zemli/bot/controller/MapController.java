package com.zemli.bot.controller;

import com.zemli.bot.dao.GameDao;
import com.zemli.bot.model.PlayerRecord;
import com.zemli.bot.model.ResourcesRecord;
import com.zemli.bot.service.RegistrationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@CrossOrigin(origins = "*")
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
        if (request.userId() == null || request.userId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        PlayerRecord player = registrationService.findRegistered(request.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Player not found for userId=" + request.userId()));
        String buildingType = normalizeBuildingType(request);
        if (buildingType == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "building/type is required");
        }
        gameDao.setPlayerState(player.id(), "LAST_WEB_BUILD_X", request.x());
        gameDao.setPlayerState(player.id(), "LAST_WEB_BUILD_Y", request.y());
        gameDao.setPlayerState(player.id(), "LAST_WEB_BUILD_AT", System.currentTimeMillis());
        gameDao.setPlayerState(player.id(), "LAST_WEB_BUILD_TYPE_HASH", buildingType.hashCode());
        gameDao.saveMapBuilding(player.id(), request.x(), request.y(), buildingType);
        return new ActionResult(true, "Команда на строительство отправлена");
    }

    @GetMapping("/state")
    public MapStateResponse getState(@RequestParam long userId) {
        PlayerRecord player = registrationService.findRegistered(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Player not found for userId=" + userId));

        GameDao.KingdomState kingdom = gameDao.loadKingdom(player.id())
                .orElseGet(() -> {
                    gameDao.upsertKingdom(player.id(), player.faction().name().toLowerCase(), 0, 0, player.cityLevel());
                    return gameDao.loadKingdom(player.id()).orElseThrow();
                });

        ResourcesRecord resources = gameDao.loadResources(player.id());
        List<MapBuildingDto> buildings = gameDao.loadMapBuildingsByOwner(player.id()).stream()
                .map(row -> new MapBuildingDto(row.x(), row.y(), row.type(), row.ownerId(), row.builtAt()))
                .toList();

        return new MapStateResponse(
                new KingdomDto(kingdom.race(), kingdom.homeX(), kingdom.homeY(), kingdom.level(), kingdom.createdAt().toEpochMilli()),
                new ResourcesDto(resources.wood(), resources.stone(), resources.iron(), resources.gold(), resources.food()),
                buildings
        );
    }

    @GetMapping("/buildings")
    public List<MapBuildingDto> getBuildings(
            @RequestParam int x1,
            @RequestParam int y1,
            @RequestParam int x2,
            @RequestParam int y2
    ) {
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2);
        int maxY = Math.max(y1, y2);
        return gameDao.loadMapBuildingsInArea(minX, minY, maxX, maxY).stream()
                .map(row -> new MapBuildingDto(row.x(), row.y(), row.type(), row.ownerId(), row.builtAt()))
                .toList();
    }

    @PostMapping("/move")
    public ActionResult move(@RequestBody MoveRequest request) {
        PlayerRecord player = registrationService.findRegistered(request.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Player not found for userId=" + request.userId()));
        gameDao.setPlayerState(player.id(), "LAST_WEB_MOVE_X", request.x());
        gameDao.setPlayerState(player.id(), "LAST_WEB_MOVE_Y", request.y());
        gameDao.setPlayerState(player.id(), "LAST_WEB_MOVE_UNITS", request.units());
        gameDao.setPlayerState(player.id(), "LAST_WEB_MOVE_AT", System.currentTimeMillis());
        gameDao.updateKingdomHome(player.id(), request.x(), request.y());
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

    private String normalizeBuildingType(BuildRequest request) {
        String raw = request.building() != null && !request.building().isBlank()
                ? request.building()
                : request.type();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toLowerCase();
    }

    public record MapData(List<MapCellDto> cells) {
    }

    public record MapCellDto(int x, int y, String biome, String building, String owner, String resource) {}

    public record BuildRequest(Long userId, int x, int y, String building, String type) {
    }

    public record MoveRequest(long userId, int x, int y, int units) {
    }

    public record ActionResult(boolean success, String message) {
    }

    public record MapBuildingDto(int x, int y, String type, long ownerId, long builtAt) {
    }

    public record KingdomDto(String race, int homeX, int homeY, int level, long createdAt) {
    }

    public record ResourcesDto(int wood, int stone, int iron, int gold, int food) {
    }

    public record MapStateResponse(KingdomDto kingdom, ResourcesDto resources, List<MapBuildingDto> buildings) {
    }
}
