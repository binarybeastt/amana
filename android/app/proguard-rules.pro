# offline-core (Rust) binds by JNI name: keep the native entry points and the exception it throws.
-keep class dev.offlinezec.wallet.core.NativeCore { native <methods>; }
-keep class dev.offlinezec.wallet.core.NativeCoreException { <init>(java.lang.String); }
# The core runs in its own process behind this provider (named in the manifest).
-keep class dev.offlinezec.wallet.core.CoreProvider { *; }
