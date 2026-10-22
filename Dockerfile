# Base images are pinned by multi-arch index digest (linux/amd64 + linux/arm64); Dependabot's
# docker ecosystem bumps tag and digest together.

# Build stage
FROM eclipse-temurin:25-jdk-alpine@sha256:3fd2d245c4e0eba615fe366a71b8bd25f5db7104f53e4026b24bf508b880bd2a AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -q
COPY src/ src/
RUN ./mvnw clean package -DskipTests -q

# Runtime stage
FROM eclipse-temurin:25-jre-alpine@sha256:3c0a9084927a221ccd1d007fcaf614465672c0af37aaa834c5184483afe56d61
WORKDIR /app

# TEMPORARY workaround for the eMudhra intermediate-CA compromise
# (https://blog.cloudflare.com/emudhra-certificate-compromise): drop the emSign roots that
# NSS 3.131.1 removes. Remove this RUN once Temurin ships cacerts from NSS >= 3.131.1
# (temurin-build PR #4587). Tracking: doemefu/homelab#212 ("eMudhra: third-party images + Temurin follow-up").
# Each listed root is deleted if present (a failed delete fails the build). The final check matches the
# roots by SHA-256 fingerprint (so a renamed alias is caught too) and fails the build if keytool itself fails.
# "changeit" is the JDK's public default cacerts password, not a secret.
RUN set -e; \
    for a in "cn_emsign_ecc_root_ca__g3,o_emudhra_technologies_limited,ou_emsign_pki,c_in [jdk]" \
             "cn_emsign_root_ca__c1,o_emudhra_inc,ou_emsign_pki,c_us [jdk]" \
             "cn_emsign_ecc_root_ca__c3,o_emudhra_inc,ou_emsign_pki,c_us [jdk]"; do \
      if keytool -list -cacerts -storepass changeit -alias "$a" >/dev/null 2>&1; then \
        keytool -delete -cacerts -storepass changeit -alias "$a"; \
      fi; \
    done; \
    certs="$(keytool -list -v -cacerts -storepass changeit)" || exit 1; \
    if printf '%s\n' "$certs" | grep -qiE \
         "86:A1:EC:BA:08:9C:4A:8D:3B:BE:27:34:C6:12:BA:34:1D:81:3E:04:3C:F9:E8:A8:62:CD:5C:57:A3:6B:BE:6B|12:56:09:AA:30:1D:A0:A2:49:B9:7A:82:39:CB:6A:34:21:6F:44:DC:AC:9F:39:54:B1:42:92:F2:E8:C8:60:8F|BC:4D:80:9B:15:18:9D:78:DB:3E:1D:8C:F4:F9:72:6A:79:5D:A1:64:3C:A5:F1:35:8E:1D:DB:0E:DC:0D:7E:B3"; then \
      echo "distrusted emSign root still present in cacerts" >&2; exit 1; \
    fi

# Non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=build /app/target/data-service-*.jar app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
