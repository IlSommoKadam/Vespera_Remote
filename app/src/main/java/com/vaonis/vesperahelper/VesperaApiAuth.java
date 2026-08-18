package com.vaonis.vesperahelper;

import android.util.Base64;
import android.util.Log;

import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;

/**
 * Signs Vespera control requests with Ed25519 (challenge from GET /v1/app/status).
 * Header: {@code Basic android|&lt;prefix&gt;|&lt;signature-b64&gt;}
 * Matches TweetNaCl {@code Signature.sign(sha512(message))} (attached sig + digest).
 */
final class VesperaApiAuth {
    private static final String TAG = "VesperaApiAuth";
    private static final EdDSAParameterSpec ED25519 =
            EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);

    /** 32-byte Ed25519 seed = first 32 bytes of the TweetNaCl 64-byte secret. */
    private static final byte[] ANDROID_CLIENT_SEED = androidClientSeed();

    private VesperaApiAuth() {}

    static String authorizationHeader(VesperaStatusSnapshot snap) {
        if (snap == null || !snap.canSignCommands()) return "";
        try {
            return authorizationHeader(snap.challenge, snap.telescopeId, snap.bootCount);
        } catch (Exception failure) {
            Log.w(TAG, "sign: " + failure.getMessage());
            return "";
        }
    }

    static String authorizationHeader(String challenge, String telescopeId, int bootCount)
            throws Exception {
        if (challenge == null || challenge.length() < 2) {
            throw new IllegalArgumentException("challenge");
        }
        if (telescopeId == null || telescopeId.isEmpty()) {
            throw new IllegalArgumentException("telescopeId");
        }
        char prefix = challenge.charAt(0);
        byte[] challengeBytes = decodeChallenge(challenge.substring(1));
        byte[] suffix = ("|" + telescopeId + "|" + bootCount).getBytes(StandardCharsets.UTF_8);
        byte[] message = concat(challengeBytes, suffix);
        byte[] digest = MessageDigest.getInstance("SHA-512").digest(message);
        byte[] detached = signEd25519(digest);
        byte[] attached = concat(detached, digest);
        String signedB64 = Base64.encodeToString(attached, Base64.NO_WRAP);
        return "Basic android|" + prefix + "|" + signedB64;
    }

    private static byte[] signEd25519(byte[] message) throws Exception {
        EdDSAPrivateKeySpec spec = new EdDSAPrivateKeySpec(ANDROID_CLIENT_SEED, ED25519);
        PrivateKey privateKey = new EdDSAPrivateKey(spec);
        Signature engine = new EdDSAEngine(MessageDigest.getInstance("SHA-512"));
        engine.initSign(privateKey);
        engine.update(message);
        return engine.sign();
    }

    private static byte[] decodeChallenge(String body) {
        String padded = body.trim().replace('-', '+').replace('_', '/');
        int remnant = padded.length() % 4;
        if (remnant != 0) {
            StringBuilder out = new StringBuilder(padded);
            for (int i = 0; i < 4 - remnant; i++) out.append('=');
            padded = out.toString();
        }
        return Base64.decode(padded, Base64.DEFAULT);
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] out = new byte[left.length + right.length];
        System.arraycopy(left, 0, out, 0, left.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }

    private static byte[] androidClientSeed() {
        byte[] secret = Base64.decode(BuildConfig.VESPERA_ED25519_SECRET_B64, Base64.DEFAULT);
        if (secret == null || secret.length < 32) {
            return new byte[32];
        }
        byte[] seed = new byte[32];
        System.arraycopy(secret, 0, seed, 0, 32);
        return seed;
    }
}
