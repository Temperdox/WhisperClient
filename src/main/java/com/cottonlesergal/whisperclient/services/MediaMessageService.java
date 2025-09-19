package com.cottonlesergal.whisperclient.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.concurrent.Task;
import javafx.scene.image.Image;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MediaMessageService {
    private static final MediaMessageService INSTANCE = new MediaMessageService();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    // URL patterns for different platforms
    private static final Pattern YOUTUBE_PATTERN = Pattern.compile(
            "(?:https?://)?(?:www\\.)?" +
                    "(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/embed/)" +
                    "([a-zA-Z0-9_-]{11})"
    );

    private static final Pattern TWITTER_PATTERN = Pattern.compile(
            "(?:https?://)?(?:www\\.)?" +
                    "(?:twitter\\.com|x\\.com)/\\w+/status/(\\d+)"
    );

    private static final Pattern BLUESKY_PATTERN = Pattern.compile(
            "(?:https?://)?(?:www\\.)?" +
                    "bsky\\.app/profile/[^/]+/post/([a-zA-Z0-9]+)"
    );

    private static final Pattern AMAZON_PATTERN = Pattern.compile(
            "(?:https?://)?(?:www\\.)?amazon\\.[a-z.]+/.*?/dp/([A-Z0-9]{10})"
    );

    private static final Pattern GENERAL_URL_PATTERN = Pattern.compile(
            "https?://(?:[-\\w.])+(?:\\:[0-9]+)?(?:/(?:[\\w/_.])*(?:\\?(?:[\\w&=%.])*)?(?:#(?:[\\w.])*)?)?",
            Pattern.CASE_INSENSITIVE
    );

    public static MediaMessageService getInstance() {
        return INSTANCE;
    }

    private MediaMessageService() {}

    /**
     * Enhanced message with media and embeds
     */
    public static class EnhancedMessage {
        private String text;
        private MessageType type;
        private String mediaData; // Base64 for images, file path for files, etc.
        private String mediaType; // MIME type
        private String fileName;
        private long fileSize;
        private LinkEmbed linkEmbed;

        public enum MessageType {
            TEXT, IMAGE, VIDEO, AUDIO, FILE, LINK_EMBED
        }

        // Getters and setters
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }

        public MessageType getType() { return type; }
        public void setType(MessageType type) { this.type = type; }

        public String getMediaData() { return mediaData; }
        public void setMediaData(String mediaData) { this.mediaData = mediaData; }

        public String getMediaType() { return mediaType; }
        public void setMediaType(String mediaType) { this.mediaType = mediaType; }

        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }

        public long getFileSize() { return fileSize; }
        public void setFileSize(long fileSize) { this.fileSize = fileSize; }

        public LinkEmbed getLinkEmbed() { return linkEmbed; }
        public void setLinkEmbed(LinkEmbed linkEmbed) { this.linkEmbed = linkEmbed; }
    }

    /**
     * Link embed data structure
     */
    public static class LinkEmbed {
        private String url;
        private String title;
        private String description;
        private String imageUrl;
        private String siteName;
        private EmbedType embedType;
        private String embedData; // JSON data for specific embeds

        public enum EmbedType {
            YOUTUBE, TWITTER, BLUESKY, AMAZON, GENERIC, IMAGE, VIDEO
        }

        // Getters and setters
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getImageUrl() { return imageUrl; }
        public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

        public String getSiteName() { return siteName; }
        public void setSiteName(String siteName) { this.siteName = siteName; }

        public EmbedType getEmbedType() { return embedType; }
        public void setEmbedType(EmbedType embedType) { this.embedType = embedType; }

        public String getEmbedData() { return embedData; }
        public void setEmbedData(String embedData) { this.embedData = embedData; }
    }

    /**
     * Process a file upload and create an enhanced message
     */
    public EnhancedMessage processFileUpload(File file) throws IOException {
        if (file == null || !file.exists()) {
            throw new IOException("File does not exist");
        }

        EnhancedMessage message = new EnhancedMessage();
        message.setFileName(file.getName());
        message.setFileSize(file.length());

        // Determine MIME type
        String mimeType = URLConnection.guessContentTypeFromName(file.getName());
        if (mimeType == null) {
            mimeType = Files.probeContentType(file.toPath());
        }
        if (mimeType == null) {
            mimeType = "application/octet-stream";
        }

        message.setMediaType(mimeType);

        // Set message type based on MIME type
        if (mimeType.startsWith("image/")) {
            message.setType(EnhancedMessage.MessageType.IMAGE);
            // For images, encode as base64
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            message.setMediaData(Base64.getEncoder().encodeToString(fileBytes));
        } else if (mimeType.startsWith("video/")) {
            message.setType(EnhancedMessage.MessageType.VIDEO);
            // For videos, we might want to store file path or upload to server
            message.setMediaData(file.getAbsolutePath());
        } else if (mimeType.startsWith("audio/")) {
            message.setType(EnhancedMessage.MessageType.AUDIO);
            message.setMediaData(file.getAbsolutePath());
        } else {
            message.setType(EnhancedMessage.MessageType.FILE);
            message.setMediaData(file.getAbsolutePath());
        }

        return message;
    }

    /**
     * Process text message and extract link embeds
     */
    public CompletableFuture<EnhancedMessage> processTextMessage(String text) {
        EnhancedMessage message = new EnhancedMessage();
        message.setText(text);
        message.setType(EnhancedMessage.MessageType.TEXT);

        // Check for URLs in the text
        Matcher urlMatcher = GENERAL_URL_PATTERN.matcher(text);
        if (urlMatcher.find()) {
            String url = urlMatcher.group();
            return generateLinkEmbed(url).thenApply(embed -> {
                if (embed != null) {
                    message.setLinkEmbed(embed);
                    message.setType(EnhancedMessage.MessageType.LINK_EMBED);
                }
                return message;
            });
        }

        return CompletableFuture.completedFuture(message);
    }

    /**
     * Generate link embed for a URL
     */
    public CompletableFuture<LinkEmbed> generateLinkEmbed(String url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LinkEmbed embed = new LinkEmbed();
                embed.setUrl(url);

                // Check for specific platform patterns
                if (YOUTUBE_PATTERN.matcher(url).find()) {
                    return generateYouTubeEmbed(url);
                } else if (TWITTER_PATTERN.matcher(url).find()) {
                    return generateTwitterEmbed(url);
                } else if (BLUESKY_PATTERN.matcher(url).find()) {
                    return generateBlueSkyEmbed(url);
                } else if (AMAZON_PATTERN.matcher(url).find()) {
                    return generateAmazonEmbed(url);
                } else {
                    return generateGenericEmbed(url);
                }

            } catch (Exception e) {
                System.err.println("Error generating embed for " + url + ": " + e.getMessage());
                return null;
            }
        });
    }

    private LinkEmbed generateYouTubeEmbed(String url) throws Exception {
        Matcher matcher = YOUTUBE_PATTERN.matcher(url);
        if (!matcher.find()) return null;

        String videoId = matcher.group(1);
        LinkEmbed embed = new LinkEmbed();
        embed.setUrl(url);
        embed.setEmbedType(LinkEmbed.EmbedType.YOUTUBE);
        embed.setSiteName("YouTube");
        embed.setImageUrl("https://img.youtube.com/vi/" + videoId + "/maxresdefault.jpg");

        // Try to get video info from YouTube API or oEmbed
        try {
            String oEmbedUrl = "https://www.youtube.com/oembed?url=" + url + "&format=json";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(oEmbedUrl))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode json = MAPPER.readTree(response.body());
                embed.setTitle(json.path("title").asText());
                embed.setDescription("YouTube Video");
                embed.setEmbedData(response.body());
            }
        } catch (Exception e) {
            embed.setTitle("YouTube Video");
            embed.setDescription("Click to watch on YouTube");
        }

        return embed;
    }

    private LinkEmbed generateTwitterEmbed(String url) throws Exception {
        LinkEmbed embed = new LinkEmbed();
        embed.setUrl(url);
        embed.setEmbedType(LinkEmbed.EmbedType.TWITTER);
        embed.setSiteName("X (Twitter)");

        // Try Twitter oEmbed API
        try {
            String oEmbedUrl = "https://publish.twitter.com/oembed?url=" + url;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(oEmbedUrl))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode json = MAPPER.readTree(response.body());
                embed.setTitle("Tweet");
                embed.setDescription("Click to view on X");
                embed.setEmbedData(response.body());
            }
        } catch (Exception e) {
            embed.setTitle("X Post");
            embed.setDescription("Click to view on X");
        }

        return embed;
    }

    private LinkEmbed generateBlueSkyEmbed(String url) {
        LinkEmbed embed = new LinkEmbed();
        embed.setUrl(url);
        embed.setEmbedType(LinkEmbed.EmbedType.BLUESKY);
        embed.setSiteName("Bluesky");
        embed.setTitle("Bluesky Post");
        embed.setDescription("Click to view on Bluesky");
        return embed;
    }

    private LinkEmbed generateAmazonEmbed(String url) throws Exception {
        LinkEmbed embed = new LinkEmbed();
        embed.setUrl(url);
        embed.setEmbedType(LinkEmbed.EmbedType.AMAZON);
        embed.setSiteName("Amazon");

        // Try to scrape basic info
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String html = response.body();

                // Extract title from HTML (basic parsing)
                Pattern titlePattern = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE);
                Matcher titleMatcher = titlePattern.matcher(html);
                if (titleMatcher.find()) {
                    embed.setTitle(titleMatcher.group(1).trim());
                }

                embed.setDescription("Amazon Product");
            }
        } catch (Exception e) {
            embed.setTitle("Amazon Product");
            embed.setDescription("Click to view on Amazon");
        }

        return embed;
    }

    private LinkEmbed generateGenericEmbed(String url) throws Exception {
        LinkEmbed embed = new LinkEmbed();
        embed.setUrl(url);
        embed.setEmbedType(LinkEmbed.EmbedType.GENERIC);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String html = response.body();

                // Extract Open Graph data
                extractOpenGraphData(html, embed);

                // If no OG data, extract basic HTML meta
                if (embed.getTitle() == null) {
                    extractBasicMetaData(html, embed);
                }

                // Set site name from domain if not found
                if (embed.getSiteName() == null) {
                    try {
                        URI uri = URI.create(url);
                        embed.setSiteName(uri.getHost());
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            // Fallback
            embed.setTitle("Link");
            embed.setDescription(url);
        }

        return embed;
    }

    private void extractOpenGraphData(String html, LinkEmbed embed) {
        // Extract Open Graph meta tags
        Pattern ogPattern = Pattern.compile("<meta\\s+property=\"og:([^\"]+)\"\\s+content=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
        Matcher ogMatcher = ogPattern.matcher(html);

        while (ogMatcher.find()) {
            String property = ogMatcher.group(1);
            String content = ogMatcher.group(2);

            switch (property) {
                case "title":
                    embed.setTitle(content);
                    break;
                case "description":
                    embed.setDescription(content);
                    break;
                case "image":
                    embed.setImageUrl(content);
                    break;
                case "site_name":
                    embed.setSiteName(content);
                    break;
            }
        }
    }

    private void extractBasicMetaData(String html, LinkEmbed embed) {
        // Extract title
        if (embed.getTitle() == null) {
            Pattern titlePattern = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE);
            Matcher titleMatcher = titlePattern.matcher(html);
            if (titleMatcher.find()) {
                embed.setTitle(titleMatcher.group(1).trim());
            }
        }

        // Extract meta description
        if (embed.getDescription() == null) {
            Pattern descPattern = Pattern.compile("<meta\\s+name=\"description\"\\s+content=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
            Matcher descMatcher = descPattern.matcher(html);
            if (descMatcher.find()) {
                embed.setDescription(descMatcher.group(1));
            }
        }
    }

    /**
     * Check if URL points to a direct image
     */
    public boolean isDirectImageUrl(String url) {
        String lowerUrl = url.toLowerCase();
        return lowerUrl.matches(".*\\.(jpg|jpeg|png|gif|bmp|webp)(\\?.*)?$");
    }

    /**
     * Check if URL points to a direct video
     */
    public boolean isDirectVideoUrl(String url) {
        String lowerUrl = url.toLowerCase();
        return lowerUrl.matches(".*\\.(mp4|webm|mov|avi|mkv)(\\?.*)?$");
    }

    /**
     * Format file size for display
     */
    public String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}