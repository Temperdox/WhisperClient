package com.cottonlesergal.whisperclient.services;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public class CredentialsStorageService {
    private static final CredentialsStorageService INSTANCE = new CredentialsStorageService();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CREDENTIALS_FILE = "saved_credentials.dat";

    private final Path credentialsPath;
    private final SecretKey machineKey;

    public static CredentialsStorageService getInstance() {
        return INSTANCE;
    }

    private CredentialsStorageService() {
        String userHome = System.getProperty("user.home");
        Path appDir = Paths.get(userHome, ".whisperclient");

        try {
            Files.createDirectories(appDir);
            this.credentialsPath = appDir.resolve(CREDENTIALS_FILE);
            this.machineKey = deriveMachineKey();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize credentials storage", e);
        }
    }

    public static class SavedCredentials {
        private String provider;
        private String encryptedToken;
        private String username;
        private String displayName;
        private String avatarUrl;
        private long savedAt;

        public SavedCredentials() {}

        public SavedCredentials(String provider, String token, String username, String displayName, String avatarUrl) {
            this.provider = provider;
            this.encryptedToken = token; // Will be encrypted before storage
            this.username = username;
            this.displayName = displayName;
            this.avatarUrl = avatarUrl;
            this.savedAt = System.currentTimeMillis();
        }

        // Getters and setters
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public String getEncryptedToken() { return encryptedToken; }
        public void setEncryptedToken(String encryptedToken) { this.encryptedToken = encryptedToken; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }

        public String getAvatarUrl() { return avatarUrl; }
        public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

        public long getSavedAt() { return savedAt; }
        public void setSavedAt(long savedAt) { this.savedAt = savedAt; }
    }

    /**
     * Save credentials for auto sign-on
     */
    public void saveCredentials(String provider, String token, String username, String displayName, String avatarUrl) {
        try {
            // Encrypt the JWT token
            String encryptedToken = encryptString(token);

            SavedCredentials credentials = new SavedCredentials(provider, encryptedToken, username, displayName, avatarUrl);

            // Serialize and encrypt the entire credentials object
            String json = MAPPER.writeValueAsString(credentials);
            byte[] encrypted = encryptData(json.getBytes());

            Files.write(credentialsPath, encrypted);
            System.out.println("[CredentialsStorage] Saved credentials for: " + username);

        } catch (Exception e) {
            System.err.println("[CredentialsStorage] Failed to save credentials: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Load saved credentials for auto sign-on
     */
    public SavedCredentials loadCredentials() {
        try {
            if (!Files.exists(credentialsPath)) {
                return null;
            }

            byte[] encrypted = Files.readAllBytes(credentialsPath);
            byte[] decrypted = decryptData(encrypted);
            String json = new String(decrypted);

            SavedCredentials credentials = MAPPER.readValue(json, SavedCredentials.class);

            // Decrypt the token
            String decryptedToken = decryptString(credentials.getEncryptedToken());
            credentials.setEncryptedToken(decryptedToken); // Now contains the actual token

            // Check if credentials are not too old (e.g., 30 days)
            long maxAge = 30L * 24 * 60 * 60 * 1000; // 30 days in milliseconds
            if (System.currentTimeMillis() - credentials.getSavedAt() > maxAge) {
                System.out.println("[CredentialsStorage] Credentials expired, clearing");
                clearCredentials();
                return null;
            }

            System.out.println("[CredentialsStorage] Loaded credentials for: " + credentials.getUsername());
            return credentials;

        } catch (Exception e) {
            System.err.println("[CredentialsStorage] Failed to load credentials: " + e.getMessage());
            // If we can't decrypt, just clear the corrupted file
            clearCredentials();
            return null;
        }
    }

    /**
     * Clear saved credentials (for logout)
     */
    public void clearCredentials() {
        try {
            if (Files.exists(credentialsPath)) {
                Files.delete(credentialsPath);
                System.out.println("[CredentialsStorage] Cleared saved credentials");
            }
        } catch (IOException e) {
            System.err.println("[CredentialsStorage] Failed to clear credentials: " + e.getMessage());
        }
    }

    /**
     * Check if credentials exist
     */
    public boolean hasCredentials() {
        return Files.exists(credentialsPath);
    }

    private SecretKey deriveMachineKey() throws Exception {
        // Create a machine-specific key based on system properties
        String machineInfo = System.getProperty("user.name") +
                System.getProperty("os.name") +
                System.getProperty("java.version") +
                "WhisperClientSecretKey2024";

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = digest.digest(machineInfo.getBytes());
        return new SecretKeySpec(keyBytes, "AES");
    }

    private byte[] encryptData(byte[] data) throws Exception {
        byte[] nonce = new byte[12];
        SecureRandom.getInstanceStrong().generateSeed(12);
        System.arraycopy(SecureRandom.getInstanceStrong().generateSeed(12), 0, nonce, 0, 12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, machineKey, new GCMParameterSpec(128, nonce));

        byte[] encrypted = cipher.doFinal(data);

        // Combine nonce + encrypted data
        byte[] result = new byte[nonce.length + encrypted.length];
        System.arraycopy(nonce, 0, result, 0, nonce.length);
        System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);

        return result;
    }

    private byte[] decryptData(byte[] data) throws Exception {
        if (data.length < 12) throw new IllegalArgumentException("Invalid encrypted data");

        byte[] nonce = new byte[12];
        byte[] encrypted = new byte[data.length - 12];

        System.arraycopy(data, 0, nonce, 0, 12);
        System.arraycopy(data, 12, encrypted, 0, encrypted.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, machineKey, new GCMParameterSpec(128, nonce));

        return cipher.doFinal(encrypted);
    }

    private String encryptString(String text) throws Exception {
        byte[] encrypted = encryptData(text.getBytes());
        return Base64.getEncoder().encodeToString(encrypted);
    }

    private String decryptString(String encryptedBase64) throws Exception {
        byte[] encrypted = Base64.getDecoder().decode(encryptedBase64);
        byte[] decrypted = decryptData(encrypted);
        return new String(decrypted);
    }
}