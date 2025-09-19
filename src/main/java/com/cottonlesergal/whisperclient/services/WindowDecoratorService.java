package com.cottonlesergal.whisperclient.services;

import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import javafx.stage.Screen;
import javafx.stage.Stage;

public class WindowDecoratorService {

    // Resize detection constants
    private static final double RESIZE_BORDER = 5.0;

    // Dragging state
    private double dragOffsetX = 0;
    private double dragOffsetY = 0;
    private boolean isDragging = false;

    // Resize state
    private boolean isResizing = false;
    private ResizeDirection resizeDirection = ResizeDirection.NONE;

    // Window state before maximize
    private double preMaxX, preMaxY, preMaxWidth, preMaxHeight;

    private enum ResizeDirection {
        NONE, N, S, E, W, NE, NW, SE, SW
    }

    /**
     * Make a stage use custom window decorations
     */
    public void decorateWindow(Stage stage, Node titleBar) {
        // NOTE: Stage style must be set BEFORE the stage is shown
        // This should be done in MainApp.start() method

        // Add resize capability to the scene root
        stage.getScene().getRoot().setOnMouseMoved(this::handleMouseMove);
        stage.getScene().getRoot().setOnMousePressed(this::handleMousePressed);
        stage.getScene().getRoot().setOnMouseDragged(event -> handleMouseDragged(event, stage));
        stage.getScene().getRoot().setOnMouseReleased(this::handleMouseReleased);

        // Make title bar draggable
        if (titleBar != null) {
            titleBar.setOnMousePressed(event -> {
                if (event.getClickCount() == 2) {
                    toggleMaximize(stage);
                } else {
                    startDragging(event, stage);
                }
            });

            titleBar.setOnMouseDragged(event -> {
                if (isDragging && !stage.isMaximized()) {
                    stage.setX(event.getScreenX() - dragOffsetX);
                    stage.setY(event.getScreenY() - dragOffsetY);
                }
            });

            titleBar.setOnMouseReleased(event -> isDragging = false);
        }
    }

    public void minimizeWindow(Stage stage) {
        stage.setIconified(true);
    }

    public void toggleMaximize(Stage stage) {
        if (stage.isMaximized()) {
            // Restore
            stage.setMaximized(false);
            if (preMaxWidth > 0 && preMaxHeight > 0) {
                stage.setX(preMaxX);
                stage.setY(preMaxY);
                stage.setWidth(preMaxWidth);
                stage.setHeight(preMaxHeight);
            }
        } else {
            // Maximize
            preMaxX = stage.getX();
            preMaxY = stage.getY();
            preMaxWidth = stage.getWidth();
            preMaxHeight = stage.getHeight();

            Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
            stage.setX(screenBounds.getMinX());
            stage.setY(screenBounds.getMinY());
            stage.setWidth(screenBounds.getWidth());
            stage.setHeight(screenBounds.getHeight());
            stage.setMaximized(true);
        }
    }

    public void closeWindow(Stage stage) {
        Platform.exit();
    }

    private void startDragging(MouseEvent event, Stage stage) {
        if (!stage.isMaximized()) {
            isDragging = true;
            dragOffsetX = event.getSceneX();
            dragOffsetY = event.getSceneY();
        }
    }

    private void handleMouseMove(MouseEvent event) {
        if (isDragging || isResizing) return;

        Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
        if (stage.isMaximized()) return;

        double x = event.getSceneX();
        double y = event.getSceneY();
        double width = stage.getScene().getWidth();
        double height = stage.getScene().getHeight();

        ResizeDirection direction = getResizeDirection(x, y, width, height);
        setCursorForDirection(stage.getScene().getRoot(), direction);
    }

    private void handleMousePressed(MouseEvent event) {
        Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
        if (stage.isMaximized()) return;

        double x = event.getSceneX();
        double y = event.getSceneY();
        double width = stage.getScene().getWidth();
        double height = stage.getScene().getHeight();

        resizeDirection = getResizeDirection(x, y, width, height);
        if (resizeDirection != ResizeDirection.NONE) {
            isResizing = true;
            dragOffsetX = event.getSceneX();
            dragOffsetY = event.getSceneY();
        }
    }

    private void handleMouseDragged(MouseEvent event, Stage stage) {
        if (!isResizing || stage.isMaximized()) return;

        double deltaX = event.getSceneX() - dragOffsetX;
        double deltaY = event.getSceneY() - dragOffsetY;

        double newX = stage.getX();
        double newY = stage.getY();
        double newWidth = stage.getWidth();
        double newHeight = stage.getHeight();

        switch (resizeDirection) {
            case N:
                newY += deltaY;
                newHeight -= deltaY;
                break;
            case S:
                newHeight += deltaY;
                break;
            case E:
                newWidth += deltaX;
                break;
            case W:
                newX += deltaX;
                newWidth -= deltaX;
                break;
            case NE:
                newY += deltaY;
                newHeight -= deltaY;
                newWidth += deltaX;
                break;
            case NW:
                newX += deltaX;
                newY += deltaY;
                newWidth -= deltaX;
                newHeight -= deltaY;
                break;
            case SE:
                newHeight += deltaY;
                newWidth += deltaX;
                break;
            case SW:
                newX += deltaX;
                newHeight += deltaY;
                newWidth -= deltaX;
                break;
        }

        // Apply minimum size constraints
        if (newWidth >= stage.getMinWidth() && newHeight >= stage.getMinHeight()) {
            stage.setX(newX);
            stage.setY(newY);
            stage.setWidth(newWidth);
            stage.setHeight(newHeight);
        }
    }

    private void handleMouseReleased(MouseEvent event) {
        isResizing = false;
        resizeDirection = ResizeDirection.NONE;
        isDragging = false;

        // Reset cursor
        Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
        stage.getScene().getRoot().setCursor(Cursor.DEFAULT);
    }

    private ResizeDirection getResizeDirection(double x, double y, double width, double height) {
        boolean isNorth = y <= RESIZE_BORDER;
        boolean isSouth = y >= height - RESIZE_BORDER;
        boolean isEast = x >= width - RESIZE_BORDER;
        boolean isWest = x <= RESIZE_BORDER;

        if (isNorth && isEast) return ResizeDirection.NE;
        if (isNorth && isWest) return ResizeDirection.NW;
        if (isSouth && isEast) return ResizeDirection.SE;
        if (isSouth && isWest) return ResizeDirection.SW;
        if (isNorth) return ResizeDirection.N;
        if (isSouth) return ResizeDirection.S;
        if (isEast) return ResizeDirection.E;
        if (isWest) return ResizeDirection.W;

        return ResizeDirection.NONE;
    }

    private void setCursorForDirection(Node node, ResizeDirection direction) {
        switch (direction) {
            case N:
            case S:
                node.setCursor(Cursor.N_RESIZE);
                break;
            case E:
            case W:
                node.setCursor(Cursor.E_RESIZE);
                break;
            case NE:
            case SW:
                node.setCursor(Cursor.NE_RESIZE);
                break;
            case NW:
            case SE:
                node.setCursor(Cursor.NW_RESIZE);
                break;
            default:
                node.setCursor(Cursor.DEFAULT);
                break;
        }
    }
}