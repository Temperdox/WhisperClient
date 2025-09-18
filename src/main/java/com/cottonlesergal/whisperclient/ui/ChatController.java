package com.cottonlesergal.whisperclient.ui;

import com.cottonlesergal.whisperclient.core.Session;
import com.cottonlesergal.whisperclient.models.Friend;
import com.cottonlesergal.whisperclient.models.Message;
import com.cottonlesergal.whisperclient.services.Config;
import com.cottonlesergal.whisperclient.services.CryptoService;
import com.cottonlesergal.whisperclient.services.InboxWs;
import com.cottonlesergal.whisperclient.services.SignalingClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * ChatController: binds to a selected Friend, sets up WebRTC + signaling,
 * and encrypts/decrypts messages end-to-end.
 */
public class ChatController {

    @FXML private VBox      messagesBox;
    @FXML private TextField txtMessage;

    private Friend friend;

    private final CryptoService   crypto     = CryptoService.get();
    private final SignalingClient signaling  = new SignalingClient();
    private final HttpClient      http       = HttpClient.newHttpClient();
    private final ObjectMapper    M          = new ObjectMapper();

    private InboxWs inbox; // your WS client for inbox events

    /** Bind this chat view to a friend (called by MainController when opening a DM). */
    public void bindFriend(Friend f) {
        this.friend = f;

        // 1) Setup WebRTC
        signaling.initRtc();

        // inbound bytes -> decrypt -> render
        signaling.onInboundBytes(bytes -> {
            try {
                Message m = crypto.decryptMessage(bytes, friend.getPubKey());
                String text = (m.getKind() == Message.Kind.TEXT) ? m.getText() : "[image]";
                addBubble(friend.getDisplayName() + ": " + text, false);
            } catch (Exception e) {
                e.printStackTrace();
                addBubble("[decrypt error]", false);
            }
        });

        // local offer/ice → send to peer via our Worker’s /signal
        signaling.setLocalOfferHandler(sdp -> postSignal("offer", M.valueToTree(sdp).toString())); // payload is JSON string
        signaling.setLocalIceHandler(candidateJson -> postSignal("ice", candidateJson));            // already JSON

        // 2) Attach our inbox WebSocket for signaling messages addressed to us
        inbox = new InboxWs(this::onInboxText);
        inbox.connect(Config.DIR_WORKER, Session.me.getUsername(), Session.token);
    }

    // ─────────────── UI handlers ───────────────
    @FXML
    public void onSend() {
        String t = txtMessage.getText();
        if (t == null || t.isBlank()) return;
        try {
            byte[] cipher = crypto.encryptMessage(Message.text(t), friend.getPubKey());
            signaling.send(cipher);
            addBubble("Me: " + t, true);
            txtMessage.clear();
        } catch (Exception e) {
            e.printStackTrace();
            addBubble("[send failed]", true);
        }
    }

    @FXML public void onAttach() {
        // TODO: pick an image/file, encrypt as Message.image(bytes) then signaling.send(...)
    }

    // ─────────────── Inbox events (WS) ───────────────
    private void onInboxText(String raw) {
        try {
            JsonNode j = M.readTree(raw);
            String type = j.path("type").asText("");

            if ("signal".equals(type)) {
                String from = j.path("from").asText("");
                if (!from.equalsIgnoreCase(friend.getUsername())) {
                    // different conversation; ignore (or route to another tab in future)
                    return;
                }

                String kind = j.path("kind").asText("");
                JsonNode payload = j.path("payload"); // may be string or object depending on kind

                switch (kind) {
                    // If your SignalingClient supports receiving offers directly, handle it here:
                    // case "offer":
                    //     String offerSdp = payload.asText();
                    //     signaling.recvRemoteOffer(offerSdp);
                    //     // If your client produces an answer callback, postSignal("answer", json(answer));
                    //     break;

                    case "answer": {
                        String sdp = payload.asText();
                        signaling.recvRemoteAnswer(sdp);
                        break;
                    }
                    case "ice": {
                        // payload is already JSON; pass-through string form
                        signaling.recvRemoteIce(payload.toString());
                        break;
                    }
                    default:
                        // ignore unknown kinds
                        break;
                }
            } else if ("friend-request".equals(type)) {
                // Friend requests are handled in MainController. Ignore here.
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ─────────────── Helpers ───────────────
    private void addBubble(String text, boolean me) {
        Label l = new Label(text);
        l.getStyleClass().add("message-bubble");
        if (me) l.getStyleClass().add("me");
        HBox row = new HBox(l);
        row.setAlignment(me ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        messagesBox.getChildren().add(row);
    }

    /** POST a signaling message to the Worker. `payloadJson` must be a JSON string (already quoted if needed). */
    private void postSignal(String kind, String payloadJson) {
        try {
            String body = "{\"to\":\"" + friend.getUsername() + "\",\"kind\":\"" + kind + "\",\"payload\":" + payloadJson + "}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/signal"))
                    .header("content-type", "application/json")
                    .header("authorization", "Bearer " + Session.token)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
