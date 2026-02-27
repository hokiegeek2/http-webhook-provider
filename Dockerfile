# Extend the official Keycloak image
FROM keycloak/keycloak:26.5.3

# Copy your provider JAR(s) into Keycloak's providers directory
# Put your .jar files in a local ./providers/ folder next to this Dockerfile
COPY --chown=keycloak:keycloak build/libs/*.jar /opt/keycloak/providers/

# (Optional) If you also have custom themes
# COPY --chown=keycloak:keycloak themes/ /opt/keycloak/themes/

# Build the server image so providers are indexed and available at runtime
RUN /opt/keycloak/bin/kc.sh build

# Keep the default entrypoint/cmd from the base image
