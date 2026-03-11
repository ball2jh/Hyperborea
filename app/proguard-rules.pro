# =============================================================================
# Hyperborea R8/ProGuard Rules — Aggressive Obfuscation
# =============================================================================

# ---- Aggressive obfuscation settings ----------------------------------------

# Move all obfuscated classes into the root package, erasing package structure
-repackageclasses ''

# Allow methods with different return types to share names
-overloadaggressively

# Merge interfaces/classes together when possible
-mergeinterfacesaggressively

# Strip source file names and line numbers from stack traces
-renamesourcefileattribute ''
-keepattributes SourceFile,LineNumberTable

# Remove Kotlin metadata so decompilers can't reconstruct original signatures
-dontwarn kotlin.Metadata
-keep,allowobfuscation,allowshrinking @interface kotlin.Metadata

# ---- Hilt / Dagger ----------------------------------------------------------
# Hilt's Gradle plugin auto-generates the necessary keep rules.
# These are safety nets for edge cases.

-keepnames @dagger.hilt.android.lifecycle.HiltViewModel class *
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }

# ---- Compose -----------------------------------------------------------------
# R8 full mode has built-in Compose support in AGP 8+. No extra rules needed.

# ---- Coroutines --------------------------------------------------------------
# kotlinx.coroutines internals use ServiceLoader reflection
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ---- BLE / GATT callbacks ----------------------------------------------------
# BluetoothGattServerCallback methods are called by the platform via reflection
-keepclassmembers class * extends android.bluetooth.BluetoothGattServerCallback {
    *;
}
-keepclassmembers class * extends android.bluetooth.BluetoothGattCallback {
    *;
}

# ---- BouncyCastle Ed25519 (license signature verification) -------------------
-keep class org.bouncycastle.crypto.params.Ed25519PublicKeyParameters { *; }
-keep class org.bouncycastle.crypto.signers.Ed25519Signer { *; }

# ---- Tink / security-crypto (EncryptedSharedPreferences) --------------------
# Tink uses reflection to load key managers and primitives
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ---- Suppress warnings -------------------------------------------------------
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
