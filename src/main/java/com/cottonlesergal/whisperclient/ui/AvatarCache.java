package com.cottonlesergal.whisperclient.ui;

import javafx.scene.image.Image;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class AvatarCache {
    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();
    private AvatarCache(){}

    public static Image get(String url, double size) {
        String key = (url == null ? "" : url) + "@" + (int) size;
        return CACHE.computeIfAbsent(key, k -> {
            if (url == null || url.isBlank()) {
                return new Image(Objects.requireNonNull(AvatarCache.class.getResource("/com/cottonlesergal/whisperclient/css/blank.png")).toExternalForm(),
                        size, size, true, true);
            }
            return new Image(url, size, size, true, true, true);
        });
    }
}
