package com.cottonlesergal.whisperclient.events;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class EventBus {
    private final ConcurrentMap<String, CopyOnWriteArrayList<Consumer<Event>>> map = new ConcurrentHashMap<>();

    public AutoCloseable on(String type, Consumer<Event> handler) {
        var list = map.computeIfAbsent(type, t -> new CopyOnWriteArrayList<>());
        list.add(handler);
        return () -> list.remove(handler);
    }

    public void emit(Event e) {
        CopyOnWriteArrayList<Consumer<Event>> handlers = map.get(e.type);
        if (handlers == null || handlers.isEmpty()) return;

        for (Consumer<Event> h : handlers) {
            try { h.accept(e); } catch (Exception ignored) {}
        }
    }
}
