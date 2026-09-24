package eu.griddigit.cimpal.main.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiKnowledgeSearchSecurityTest {
    @Test
    void acceptsCredentialFreePublicHttpsUrl() {
        assertDoesNotThrow(() -> AiKnowledgeSearch.validatePublicHttpsUri("https://8.8.8.8/reference"));
    }

    @Test
    void rejectsPlainHttp() {
        assertThrows(IllegalArgumentException.class,
                () -> AiKnowledgeSearch.validatePublicHttpsUri("http://example.com/reference"));
    }

    @Test
    void rejectsEmbeddedCredentials() {
        assertThrows(IllegalArgumentException.class,
                () -> AiKnowledgeSearch.validatePublicHttpsUri("https://token@example.com/reference"));
    }

    @Test
    void rejectsLoopbackAndPrivateAddresses() {
        assertThrows(IllegalArgumentException.class,
                () -> AiKnowledgeSearch.validatePublicHttpsUri("https://127.0.0.1/reference"));
        assertThrows(IllegalArgumentException.class,
                () -> AiKnowledgeSearch.validatePublicHttpsUri("https://192.168.1.1/reference"));
        assertThrows(IllegalArgumentException.class,
                () -> AiKnowledgeSearch.validatePublicHttpsUri("https://[::1]/reference"));
    }
}
