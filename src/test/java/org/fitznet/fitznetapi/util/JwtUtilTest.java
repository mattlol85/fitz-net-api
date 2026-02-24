package org.fitznet.fitznetapi.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.util.Date;
import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = JwtUtil.class)
@TestPropertySource(properties = {
        "jwt.secret=testSecretKeyForJwtTokenGenerationInTestEnvironmentOnly",
        "jwt.expiration=86400000"
})
class JwtUtilTest {

    @Autowired
    private JwtUtil jwtUtil;

    private String testUsername;

    @BeforeEach
    void setUp() {
        testUsername = "testuser";
    }

    @Test
    void generateTokenShouldReturnValidToken() {
        String token = jwtUtil.generateToken(testUsername);

        assertNotNull(token);
        assertFalse(token.isEmpty());
        assertTrue(token.split("\\.").length == 3); // JWT has 3 parts separated by dots
    }

    @Test
    void extractUsernameShouldReturnCorrectUsername() {
        String token = jwtUtil.generateToken(testUsername);

        String extractedUsername = jwtUtil.extractUsername(token);

        assertEquals(testUsername, extractedUsername);
    }

    @Test
    void extractExpirationShouldReturnFutureDate() {
        String token = jwtUtil.generateToken(testUsername);

        Date expiration = jwtUtil.extractExpiration(token);

        assertNotNull(expiration);
        assertTrue(expiration.after(new Date())); // Expiration should be in the future
    }

    @Test
    void validateTokenShouldReturnTrueForValidToken() {
        String token = jwtUtil.generateToken(testUsername);

        Boolean isValid = jwtUtil.validateToken(token, testUsername);

        assertTrue(isValid);
    }

    @Test
    void validateTokenShouldReturnFalseForWrongUsername() {
        String token = jwtUtil.generateToken(testUsername);

        Boolean isValid = jwtUtil.validateToken(token, "wronguser");

        assertFalse(isValid);
    }

    @Test
    void validateTokenWithoutUsernameShouldReturnTrueForValidToken() {
        String token = jwtUtil.generateToken(testUsername);

        Boolean isValid = jwtUtil.validateToken(token);

        assertTrue(isValid);
    }

    @Test
    void validateTokenShouldReturnFalseForExpiredToken() {
        // Create an expired token manually
        String secret = "testSecretKeyForJwtTokenGenerationInTestEnvironmentOnly";
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes());

        String expiredToken =
                Jwts.builder()
                        .subject(testUsername)
                        .issuedAt(new Date(System.currentTimeMillis() - 10000))
                        .expiration(new Date(System.currentTimeMillis() - 1000)) // Expired 1 second ago
                        .signWith(key)
                        .compact();

        Boolean isValid = jwtUtil.validateToken(expiredToken);

        assertFalse(isValid);
    }

    @Test
    void validateTokenShouldReturnFalseForInvalidToken() {
        String invalidToken = "invalid.jwt.token";

        Boolean isValid = jwtUtil.validateToken(invalidToken);

        assertFalse(isValid);
    }

    @Test
    void validateTokenShouldReturnFalseForMalformedToken() {
        String malformedToken = "malformed-token-without-proper-structure";

        Boolean isValid = jwtUtil.validateToken(malformedToken);

        assertFalse(isValid);
    }

    @Test
    void extractUsernameFromExpiredTokenShouldThrowException() {
        // Create an expired token
        String secret = "testSecretKeyForJwtTokenGenerationInTestEnvironmentOnly";
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes());

        String expiredToken =
                Jwts.builder()
                        .subject(testUsername)
                        .issuedAt(new Date(System.currentTimeMillis() - 10000))
                        .expiration(new Date(System.currentTimeMillis() - 1000))
                        .signWith(key)
                        .compact();

        assertThrows(ExpiredJwtException.class, () -> jwtUtil.extractUsername(expiredToken));
    }

    @Test
    void tokenShouldContainCorrectClaims() {
        String token = jwtUtil.generateToken(testUsername);

        String username = jwtUtil.extractUsername(token);
        Date expiration = jwtUtil.extractExpiration(token);

        assertEquals(testUsername, username);
        assertNotNull(expiration);
        assertTrue(expiration.after(new Date()));
    }

    @Test
    void multipleTokensForSameUserShouldBeDifferent() {
        String token1 = jwtUtil.generateToken(testUsername);

        // Wait a tiny bit to ensure different issuedAt timestamp
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String token2 = jwtUtil.generateToken(testUsername);

        assertNotEquals(token1, token2);
        assertEquals(jwtUtil.extractUsername(token1), jwtUtil.extractUsername(token2));
    }

    @Test
    void tokensForDifferentUsersShouldHaveDifferentUsernames() {
        String user1 = "user1";
        String user2 = "user2";

        String token1 = jwtUtil.generateToken(user1);
        String token2 = jwtUtil.generateToken(user2);

        assertEquals(user1, jwtUtil.extractUsername(token1));
        assertEquals(user2, jwtUtil.extractUsername(token2));
        assertNotEquals(jwtUtil.extractUsername(token1), jwtUtil.extractUsername(token2));
    }
}

