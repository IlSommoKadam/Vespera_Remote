package com.vaonis.vesperahelper;

import android.util.Base64;
import android.util.Log;

import com.vaonis.vesperahelper.crypto.TweetNacl;

import java.nio.charset.StandardCharsets;

/**
 * Builds the Vespera REST Authorization header (Ed25519 challenge-response).
 * Keys match the Singularity Android client (shared across all instruments).
 */
final class VesperaApiAuth {
    private static final String TAG = "VesperaApiAuth";
    /** 32-byte public key seed (first ctor arg to TweetNacl.Signature). */
    private static final byte[] KEY_PUBLIC = Base64.decode(
            "aCPG7E1gvOBDwWdj82OceoebY0ARMdie0XG++to/Afc=", Base64.DEFAULT);
    /** 64-byte expanded secret key (second ctor arg). */
    private static final byte[] KEY_SECRET = Base64.decode(
            "O8GD9ttc5pbB/QvCK1W7TfVOmd4ZYlgOZ22Qvz6GhMZoI8bsTWC84EPBZ2PzY5x6"
                    + "h5tjQBEx2J7Rcb762j8B9w==",
            Base64.DEFAULT);

    private VesperaApiAuth() {}

    /** Returns {@code Basic android|<first>|<signature_b64>} or null if inputs are missing. */
    static String buildHeader(VesperaStatusSnapshot status) {
        if (status == null || !status.hasAuthFields()) return null;
        try {
            String challenge = status.challenge;
            char first = challenge.charAt(0);
            byte[] challengeBytes = Base64.decode(challenge.substring(1), Base64.DEFAULT);
            if (challengeBytes == null || challengeBytes.length == 0) return null;

            String suffix = "|" + status.telescopeId + "|" + status.bootCount;
            byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);
            byte[] message = new byte[challengeBytes.length + suffixBytes.length];
            System.arraycopy(challengeBytes, 0, message, 0, challengeBytes.length);
            System.arraycopy(suffixBytes, 0, message, challengeBytes.length, suffixBytes.length);

            byte[] digest = TweetNacl.Hash.sha512(message);
            if (digest == null) return null;

            byte[] signed = new TweetNacl.Signature(KEY_PUBLIC, KEY_SECRET).sign(digest);
            String signedB64 = Base64.encodeToString(signed, Base64.NO_WRAP);
            return "Basic android|" + first + '|' + signedB64;
        } catch (Exception failure) {
            Log.w(TAG, "auth header: " + failure.getMessage());
            return null;
        }
    }

    static boolean keysConfigured() {
        return KEY_PUBLIC != null && KEY_PUBLIC.length == 32
                && KEY_SECRET != null && KEY_SECRET.length == 64;
    }
}
