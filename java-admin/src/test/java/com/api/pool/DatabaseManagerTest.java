package com.api.pool;

import com.api.pool.auth.AuthManager;
import com.api.pool.auth.TokenManager;
import com.api.pool.db.DatabaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseManagerTest {

    @TempDir
    Path tmp;

    @Test
    void userCrudRoundTrip() {
        try (DatabaseManager db = new DatabaseManager(tmp.resolve("test.db").toString())) {
            assertNull(db.getUserByName("alice"));
            db.addUser("alice", AuthManager.hashPassword("secret"), "alice@example.com", "default");
            DatabaseManager.User user = db.getUserByName("alice");
            assertNotNull(user);
            assertEquals("alice", user.username());
            assertEquals("default", user.tier());
            assertTrue(user.passwordHash().startsWith("sha256:"));

            db.setUserTier(user.id(), "premium");
            assertEquals("premium", db.getUserById(user.id()).tier());

            assertTrue(db.deleteUser(user.id()));
            assertNull(db.getUserByName("alice"));
        }
    }

    @Test
    void providerAndKeys() {
        try (DatabaseManager db = new DatabaseManager(tmp.resolve("test2.db").toString())) {
            db.addProvider("mock", "http://127.0.0.1:9999");
            DatabaseManager.Provider p = db.getProviderByName("mock");
            assertNotNull(p);
            assertEquals(100, p.health());

            db.addModel("mock", "m1");
            assertEquals(1, db.listModels("mock").size());

            db.addProviderKey("mock", "sk-test-123", "m1", null);
            assertEquals(1, db.listProviderKeys().size());
            assertNull(db.listProviderKeys().get(0).authHeader());
            assertTrue(db.removeProviderKey("sk-test-123"));
            assertEquals(0, db.listProviderKeys().size());

            // custom auth header (Gemini-style)
            db.addProviderKey("mock", "sk-test-456", null, "x-goog-api-key");
            assertEquals("x-goog-api-key", db.listProviderKeys().get(0).authHeader());
        }
    }

    @Test
    void importModelsAddsAndDeduplicates() {
        try (DatabaseManager db = new DatabaseManager(tmp.resolve("test4.db").toString())) {
            db.addProvider("openrouter", "https://openrouter.ai/api/v1");

            int added = db.importModels("openrouter", List.of("model-a", "model-b", "model-a", "  "));
            assertEquals(2, added);
            assertEquals(List.of("model-a", "model-b"), db.listModels("openrouter"));

            // re-importing the same names adds nothing
            assertEquals(0, db.importModels("openrouter", List.of("model-a", "model-b")));

            // unknown provider
            assertEquals(-1, db.importModels("does-not-exist", List.of("x")));
        }
    }

    @Test
    void keyGenerationAndRevoke() {
        try (DatabaseManager db = new DatabaseManager(tmp.resolve("test3.db").toString())) {
            db.addUser("bob", AuthManager.hashPassword("pw"), null, "default");
            DatabaseManager.User bob = db.getUserByName("bob");

            db.addUserKey(bob.id(), "pkay_abcdef123456", "default", 60, null);
            DatabaseManager.UserKey key = db.listUserKeys().get(0);
            assertEquals("active", key.status());
            assertEquals(60, key.maxLimit());

            assertTrue(db.revokeUserKey("pkay_abcdef123456"));
            assertEquals("revoked", db.listUserKeys().get(0).status());
        }
    }

    @Test
    void tokenLifecycle() {
        TokenManager tokens = new TokenManager(tmp.resolve("session.txt").toString(), 60_000);
        assertFalse(tokens.isValid());
        tokens.createToken();
        assertTrue(tokens.isValid());
        assertNotNull(tokens.currentToken());
        tokens.revoke();
        assertFalse(tokens.isValid());
    }
}