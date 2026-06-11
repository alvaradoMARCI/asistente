#!/bin/sh
#
# NubiaAgent Gradle Wrapper - Auto-bootstrap edition
# This script automatically downloads gradle-wrapper.jar on first run.
#
# Prerequisites: JDK 17+, Android SDK with compileSdk 34
#

##############################################################################
# Gradle Wrapper Bootstrap
##############################################################################

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
WRAPPER_JAR="$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_PROPERTIES="$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.properties"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

# Auto-download gradle-wrapper.jar if missing
if [ ! -f "$WRAPPER_JAR" ]; then
    echo "==> gradle-wrapper.jar no encontrado. Descargando..."
    
    # Parse distributionUrl from properties
    if [ -f "$WRAPPER_PROPERTIES" ]; then
        DIST_URL=$(grep 'distributionUrl' "$WRAPPER_PROPERTIES" | sed 's/distributionUrl=//' | sed 's/\\://g')
    else
        DIST_URL="https://services.gradle.org/distributions/gradle-8.5-bin.zip"
    fi
    
    # Download the full Gradle distribution and extract wrapper jar
    DIST_ZIP="$GRADLE_USER_HOME/wrapper/dists/gradle-8.5-bin/gradle-8.5-bin.zip"
    mkdir -p "$(dirname "$DIST_ZIP")"
    
    if [ ! -f "$DIST_ZIP" ]; then
        echo "==> Descargando Gradle 8.5..."
        curl -fSL -o "$DIST_ZIP" "$DIST_URL" || {
            echo "Error: No se pudo descargar Gradle. Instálalo manualmente:"
            echo "  sudo apt install gradle"
            echo "  O descarga desde: https://gradle.org/install/"
            exit 1
        }
    fi
    
    # Extract the wrapper jar from the distribution
    TMP_DIR=$(mktemp -d)
    unzip -q -o "$DIST_ZIP" -d "$TMP_DIR" "gradle-8.5/lib/gradle-wrapper-*.jar" 2>/dev/null
    WRAPPER_JAR_FROM_DIST=$(find "$TMP_DIR" -name "gradle-wrapper-*.jar" 2>/dev/null | head -1)
    
    if [ -n "$WRAPPER_JAR_FROM_DIST" ] && [ -f "$WRAPPER_JAR_FROM_DIST" ]; then
        cp "$WRAPPER_JAR_FROM_DIST" "$WRAPPER_JAR"
        echo "==> gradle-wrapper.jar instalado correctamente"
    else
        # Try alternative: install gradle and generate wrapper
        echo "==> Extrayendo wrapper de la distribución..."
        if command -v gradle > /dev/null 2>&1; then
            cd "$SCRIPT_DIR" && gradle wrapper --gradle-version 8.5
        else
            echo "Error: No se pudo obtener gradle-wrapper.jar"
            echo "Solución: Instala Gradle y ejecuta 'gradle wrapper' en el directorio del proyecto"
            rm -rf "$TMP_DIR"
            exit 1
        fi
    fi
    rm -rf "$TMP_DIR"
fi

# Determine the Java command to use
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

if ! command -v "$JAVACMD" > /dev/null 2>&1; then
    echo "Error: JAVA_HOME no está configurado y java no se encuentra en el PATH."
    echo "Instala JDK 17+ y configura JAVA_HOME."
    exit 1
fi

# Execute Gradle
exec "$JAVACMD" \
    $DEFAULT_JVM_OPTS \
    $JAVA_OPTS \
    $GRADLE_OPTS \
    "-Dorg.gradle.appname=$APP_BASE_NAME" \
    -classpath "$WRAPPER_JAR" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
