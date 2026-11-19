# Source this file (bash): `source scripts/env.sh`
# Android build toolchain installed under $HOME (no sudo).
export JAVA_HOME="$HOME/opt/jdk-17"
export GRADLE_HOME="$HOME/opt/gradle"
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
for _d in "$ANDROID_HOME/platform-tools" "$ANDROID_HOME/cmdline-tools/latest/bin" "$GRADLE_HOME/bin" "$JAVA_HOME/bin"; do
  case ":$PATH:" in
    *":$_d:"*) ;;
    *) PATH="$_d:$PATH" ;;
  esac
done
unset _d
export PATH
