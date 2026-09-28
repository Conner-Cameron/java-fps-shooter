# Runs the browser PvP mode (web/) -- not the desktop LWJGL game, which
# needs a real display and can't run in a container.
#
# Zero build step: GameServer.java has no dependencies, so the JDK just
# interprets it directly (JEP 330 single-file source launch).
FROM eclipse-temurin:17-jdk-jammy

WORKDIR /app
COPY web/ /app/web/
RUN mkdir -p /app/data

EXPOSE 8080
# Small heap + serial GC: the free tier has 512 MB total and this server is tiny.
CMD ["java", "-Xmx256m", "-XX:+UseSerialGC", "-Xss512k", "web/server/GameServer.java"]
