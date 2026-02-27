package org.hokiegeek2.keycloakhttpwebhookprovider.provider;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;


public class KeycloakHttpWebhookProviderFactory implements EventListenerProviderFactory {
    private static final Logger log = Logger.getLogger(KeycloakHttpWebhookProviderFactory.class);
    private String serverUrl;

    @Override
    public EventListenerProvider create(KeycloakSession keycloakSession) {

        return new KeycloakHttpWebhookProvider(keycloakSession, serverUrl);
    }

    @Override
    public void init(Config.Scope config) {
        // Read from environment variable
        serverUrl = System.getenv("WEBHOOK_URL");

        if (serverUrl == null || serverUrl.isBlank()) {
            log.warn("WEBHOOK_URL environment variable not set. Webhooks will be disabled.");
        } else {
            log.infof("HTTP webhook provider initialized with WEBHOOK_URL=%s", serverUrl);
        }
    }

    @Override
    public void postInit(KeycloakSessionFactory keycloakSessionFactory) {

    }

    @Override
    public void close() {

    }

    @Override
    public String getId() {
        return "http_webhook";
    }
}
