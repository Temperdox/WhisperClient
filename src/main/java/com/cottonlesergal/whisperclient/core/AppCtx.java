package com.cottonlesergal.whisperclient.core;

import com.cottonlesergal.whisperclient.events.EventBus;

public final class AppCtx {
    private AppCtx() {}
    public static final EventBus BUS = new EventBus();
}
