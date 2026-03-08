package com.agentframework.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HashUtil} — SHA-256 hashing utility.
 */
class HashUtilTest {

    @Test
    void sha256_nullInput_returnsNull() {
        assertThat(HashUtil.sha256(null)).isNull();
    }

    @Test
    void sha256_emptyInput_returnsNull() {
        assertThat(HashUtil.sha256("")).isNull();
    }

    @Test
    void sha256_deterministic_sameInputSameOutput() {
        String input = "hello world";
        String hash1 = HashUtil.sha256(input);
        String hash2 = HashUtil.sha256(input);

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void sha256_knownValue_matchesExpectedHash() {
        // SHA-256("hello world") is a well-known value
        String expected = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9";
        assertThat(HashUtil.sha256("hello world")).isEqualTo(expected);
    }

    @Test
    void sha256_outputFormat_64charLowercaseHex() {
        String hash = HashUtil.sha256("test content");

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
    }

    @Test
    void sha256_differentInputs_differentHashes() {
        String hash1 = HashUtil.sha256("input A");
        String hash2 = HashUtil.sha256("input B");

        assertThat(hash1).isNotEqualTo(hash2);
    }
}
