package com.cottonlesergal.whisperclient.services;

import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Scanner;
import java.util.function.Consumer;

public class SignalingClient {
    private final WebView web = new WebView();
    private JSObject window;
    private Consumer<byte[]> onInbound = b->{};
    private java.util.function.Consumer<String> onLocalOffer = s->{};
    private java.util.function.Consumer<String> onLocalIce   = s->{};

    public void initRtc(){
        WebEngine eng = web.getEngine();
        eng.setJavaScriptEnabled(true);
        eng.getLoadWorker().stateProperty().addListener((obs,o,s)->{
            if (s == javafx.concurrent.Worker.State.SUCCEEDED) {
                window = (JSObject) eng.executeScript("window");
                window.setMember("java", new JsBridge());
                eng.executeScript("WC.init({turnUser:'"+Config.TURN_USER+"',turnPass:'"+Config.TURN_PASS+"',turnHost:'"+Config.TURN_HOST+"',stun:'"+Config.STUN+"'})");
            }
        });
        eng.loadContent(readRes("/web/webrtc.html"));
    }

    public void onInboundBytes(Consumer<byte[]> cb){ this.onInbound = cb; }
    public void setLocalOfferHandler(java.util.function.Consumer<String> c){ onLocalOffer = c; }
    public void setLocalIceHandler(java.util.function.Consumer<String> c){ onLocalIce = c; }

    public void send(byte[] cipher){
        Platform.runLater(() -> window.call("WC_sendB64", Base64.getEncoder().encodeToString(cipher)));
    }
    public void recvRemoteAnswer(String sdp){ Platform.runLater(() -> window.call("WC_recvAnswer", sdp)); }
    public void recvRemoteIce(String candJson){ Platform.runLater(() -> window.call("WC_addIce", candJson)); }

    public class JsBridge {
        public void onInbound(String b64){ onInbound.accept(Base64.getDecoder().decode(b64)); }
        public void onLocalOffer(String sdp){ onLocalOffer.accept(sdp); }
        public void onLocalIce(String candJson){ onLocalIce.accept(candJson); }
    }

    private static String readRes(String path){
        try (InputStream in = SignalingClient.class.getResourceAsStream(path)) {
            return new Scanner(in, StandardCharsets.UTF_8).useDelimiter("\\A").next();
        } catch (Exception e){ throw new RuntimeException("Missing resource: "+path, e); }
    }
}
