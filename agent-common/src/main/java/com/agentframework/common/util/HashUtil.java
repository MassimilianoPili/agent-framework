package com.agentframework.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Utility for computing SHA-256 hashes.
 *
 * <p>Used by:</p>
 * <ul>
 *   <li>Worker-SDK: provenance tracking ({@code promptHash}, {@code skillsHash})</li>
 *   <li>Orchestrator: Content-Addressable Storage ({@code ArtifactStore})</li>
 * </ul>
 */
public final class HashUtil {

    private HashUtil() {}

    /**
     * Computes the SHA-256 hex digest of the input string.
     *
     * @param input the string to hash
     * @return 64-character lowercase hex string, or {@code null} if input is null or empty
     */
    public static String sha256(String input) {
        if (input == null || input.isEmpty()) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
