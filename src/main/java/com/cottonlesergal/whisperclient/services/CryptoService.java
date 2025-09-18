package com.cottonlesergal.whisperclient.services;

import com.cottonlesergal.whisperclient.models.Message;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class CryptoService {
    private static final CryptoService I = new CryptoService();
    public static CryptoService get(){ return I; }

    private KeyPair localKeyPair; // X25519

    public synchronized String ensureLocalIdentity() throws Exception {
        if (localKeyPair == null) {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("XDH");
            kpg.initialize(new NamedParameterSpec("X25519"));
            localKeyPair = kpg.generateKeyPair();
        }
        return Base64.getEncoder().encodeToString(localKeyPair.getPublic().getEncoded());
    }

    private SecretKey deriveSharedKey(String peerPubB64){
        try {
            KeyAgreement ka = KeyAgreement.getInstance("XDH");
            ka.init(localKeyPair.getPrivate());
            PublicKey peer = KeyFactory.getInstance("XDH")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(peerPubB64)));
            ka.doPhase(peer, true);
            byte[] shared = ka.generateSecret();
            byte[] key = MessageDigest.getInstance("SHA-256").digest(shared);
            return new SecretKeySpec(key, 0, 16, "AES");
        } catch (Exception e){ throw new RuntimeException(e); }
    }

    public byte[] encryptMessage(Message m, String peerPubB64){
        try {
            SecretKey k = deriveSharedKey(peerPubB64);
            byte[] nonce = SecureRandom.getInstanceStrong().generateSeed(12);
            byte[] plain = m.getKind()== Message.Kind.TEXT ? m.getText().getBytes(StandardCharsets.UTF_8)
                    : Base64.getDecoder().decode(m.getBase64Image());
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(128, nonce));
            byte[] ct = c.doFinal(plain);
            ByteBuffer bb = ByteBuffer.allocate(1+12+ct.length);
            bb.put((byte)(m.getKind()== Message.Kind.TEXT?0:1)); bb.put(nonce); bb.put(ct);
            return bb.array();
        } catch (Exception e){ throw new RuntimeException(e); }
    }

    public Message decryptMessage(byte[] packet, String peerPubB64){
        try {
            ByteBuffer bb = ByteBuffer.wrap(packet);
            byte kind = bb.get(); byte[] nonce = new byte[12]; bb.get(nonce);
            byte[] ct = new byte[bb.remaining()]; bb.get(ct);
            SecretKey k = deriveSharedKey(peerPubB64);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, nonce));
            byte[] pt = c.doFinal(ct);
            return (kind==0) ? Message.text(new String(pt, StandardCharsets.UTF_8))
                    : Message.image(Base64.getEncoder().encodeToString(pt));
        } catch (Exception e){ throw new RuntimeException(e); }
    }
}
