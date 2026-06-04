#
# MockServer Admission Webhook Dockerfile
#
# Builds the admission webhook server that handles MutatingAdmissionWebhook
# requests for automatic sidecar injection.
#
# Build:
#   cd <repo-root>/mockserver && ./mvnw package -pl mockserver-k8s-webhook -DskipTests
#   cd <repo-root> && docker build -f docker/webhook/Dockerfile \
#       --build-arg VERSION=6.1.1-SNAPSHOT \
#       -t mockserver/mockserver-webhook:6.1.1-SNAPSHOT .
#

# runtime image — distroless java17 (nonroot, ~180 MB)
FROM gcr.io/distroless/java17:nonroot@sha256:81d09cac6ec47f6a13c61a941557f95079213320f3ddbf9d353de9317669aab5

LABEL maintainer="James Bloom <jamesdbloom@gmail.com>"
LABEL description="MockServer Kubernetes Admission Webhook for automatic sidecar injection"

# The webhook listens on 8443 by default
EXPOSE 8443

# Copy the fat jar
ARG VERSION=6.1.1-SNAPSHOT
COPY mockserver/mockserver-k8s-webhook/target/mockserver-k8s-webhook-${VERSION}-jar-with-dependencies.jar /mockserver-webhook.jar

# Don't run as root
USER nonroot

ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "/mockserver-webhook.jar"]

# Default environment (overridden by Helm values)
ENV WEBHOOK_PORT=8443
ENV WEBHOOK_TLS_CERT_FILE=/etc/webhook/tls/tls.crt
ENV WEBHOOK_TLS_KEY_FILE=/etc/webhook/tls/tls.key

# No Docker HEALTHCHECK — the image is distroless (no shell, no curl).
# Health checking is handled by Kubernetes readiness/liveness probes
# configured in the Helm webhook-deployment.yaml (GET /healthz over HTTPS).
