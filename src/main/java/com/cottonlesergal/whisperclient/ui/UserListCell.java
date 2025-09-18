package com.cottonlesergal.whisperclient.ui;

import com.cottonlesergal.whisperclient.models.UserSummary;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Circle;

public class UserListCell extends ListCell<UserSummary> {
    private final HBox box = new HBox(10);
    private final ImageView avatar = new ImageView();
    private final Label name = new Label();

    public UserListCell() {
        avatar.setFitWidth(32); avatar.setFitHeight(32);
        Circle clip = new Circle(16, 16, 16);
        avatar.setClip(clip);
        name.getStyleClass().add("user-cell-name");
        box.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(avatar, name);
    }

    @Override protected void updateItem(UserSummary item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) { setGraphic(null); return; }
        avatar.setImage(AvatarCache.get(item.getAvatar(), 32));
        name.setText(item.getDisplay().isBlank() ? item.getUsername() : item.getDisplay());
        setGraphic(box);
    }
}
