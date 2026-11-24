# Keep BiometricPrompt and AndroidX Security Crypto classes
-keep class androidx.biometric.** { *; }
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }

# Keep custom Views and Android components referenced in Manifest
-keep class com.local.offlinelocker.PatternLockView { *; }
-keep class com.local.offlinelocker.AppMonitorService { *; }
-keep class com.local.offlinelocker.AppFirewallVpnService { *; }
-keep class com.local.offlinelocker.VaultDeviceAdminReceiver { *; }
-keep class com.local.offlinelocker.BootReceiver { *; }
-keep class com.local.offlinelocker.LockActivity { *; }
-keep class com.local.offlinelocker.MainActivity { *; }

# Suppress warnings for optional HTTP/Time classes in Tink (unused in offline mode)
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
