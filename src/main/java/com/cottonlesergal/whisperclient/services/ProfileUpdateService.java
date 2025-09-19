package com.cottonlesergal.whisperclient.services;

import com.cottonlesergal.whisperclient.core.Session;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import javax.imageio.ImageIO;

public class ProfileUpdateService {
    private static final ProfileUpdateService INSTANCE = new ProfileUpdateService();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_AVATAR_SIZE = 1024; // 1024x1024 max
    private static final long MAX_FILE_SIZE = 8 * 1024 * 1024; // 8MB max

    private final HttpClient httpClient = HttpClient.newHttpClient();

    public static ProfileUpdateService getInstance() {
        return INSTANCE;
    }

    private ProfileUpdateService() {}

    /**
     * Upload a new avatar image
     * @param imageFile The image file to upload
     * @return The URL of the uploaded avatar, or null if failed
     */
    public String uploadAvatar(File imageFile) throws Exception {
        // Validate file size
        if (imageFile.length() > MAX_FILE_SIZE) {
            throw new Exception("Image file is too large. Maximum size is 8MB.");
        }

        // Validate and resize image
        BufferedImage image = ImageIO.read(imageFile);
        if (image == null) {
            throw new Exception("Invalid image file format.");
        }

        // Resize if necessary
        BufferedImage resizedImage = resizeImage(image, MAX_AVATAR_SIZE);

        // Convert to base64
        String base64Image = imageToBase64(resizedImage, getImageFormat(imageFile.getName()));

        // For now, we'll store the image as a data URL since we don't have an image upload service
        // In a production environment, you'd upload to a service like CloudFlare Images, AWS S3, etc.
        return "data:image/" + getImageFormat(imageFile.getName()) + ";base64," + base64Image;
    }

    /**
     * Update user profile information
     */
    public boolean updateProfile(String displayName, String avatarUrl) {
        try {
            var payload = MAPPER.createObjectNode()
                    .put("username", Session.me.getUsername())
                    .put("pubkey", Session.me.getPubKey())
                    .put("display", displayName)
                    .put("provider", Session.me.getProvider())
                    .put("avatar", avatarUrl != null ? avatarUrl : "");

            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/register"))
                    .header("authorization", "Bearer " + Config.APP_TOKEN)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200;

        } catch (Exception e) {
            System.err.println("Failed to update profile: " + e.getMessage());
            return false;
        }
    }

    private BufferedImage resizeImage(BufferedImage original, int maxSize) {
        int width = original.getWidth();
        int height = original.getHeight();

        // If image is already small enough, return as-is
        if (width <= maxSize && height <= maxSize) {
            return original;
        }

        // Calculate new dimensions maintaining aspect ratio
        double ratio = Math.min((double) maxSize / width, (double) maxSize / height);
        int newWidth = (int) (width * ratio);
        int newHeight = (int) (height * ratio);

        // Create resized image
        BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g2d = resized.createGraphics();
        g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.drawImage(original, 0, 0, newWidth, newHeight, null);
        g2d.dispose();

        return resized;
    }

    private String imageToBase64(BufferedImage image, String format) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, format, baos);
        byte[] imageBytes = baos.toByteArray();
        return Base64.getEncoder().encodeToString(imageBytes);
    }

    private String getImageFormat(String filename) {
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        switch (extension) {
            case "jpg":
            case "jpeg":
                return "jpeg";
            case "png":
                return "png";
            case "gif":
                return "gif";
            case "bmp":
                return "bmp";
            default:
                return "png"; // Default to PNG
        }
    }

    /**
     * Validate display name
     */
    public boolean isValidDisplayName(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return false;
        }

        String trimmed = displayName.trim();

        // Length limits
        if (trimmed.length() < 1 || trimmed.length() > 32) {
            return false;
        }

        // No leading/trailing whitespace
        if (!trimmed.equals(displayName)) {
            return false;
        }

        // Basic character validation (allow Unicode for international names)
        // Reject only control characters and certain problematic characters
        return !trimmed.matches(".*[\\p{Cntrl}\\p{Cc}\\p{Cf}\\p{Co}\\p{Cn}].*");
    }
}