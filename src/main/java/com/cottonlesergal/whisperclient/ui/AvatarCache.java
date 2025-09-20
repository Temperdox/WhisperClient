package com.cottonlesergal.whisperclient.ui;

import javafx.scene.image.Image;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AvatarCache {
    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();
    private AvatarCache(){}

    public static Image get(String url, double size) {
        String key = (url == null ? "" : url) + "@" + (int) size;
        return CACHE.computeIfAbsent(key, k -> {
            if (url == null || url.isBlank()) {
                // Create a simple colored rectangle as default avatar instead of requiring a file
                try {
                    // Try to load the blank.png resource
                    var resource = AvatarCache.class.getResource("/com/cottonlesergal/whisperclient/css/blank.png");
                    if (resource != null) {
                        return new Image(resource.toExternalForm(), size, size, true, true);
                    }
                } catch (Exception e) {
                }
                return new Image("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVQIHWNgAAIAAAUAAY27m/MAAAAASUVORK5CYII=",
                        size, size, true, true);
            }
            return new Image(url, size, size, true, true, true);
        });
    }
}