# SAMTEX release rules.
# Hilt, Room and Compose ship their own consumer rules.
# Strip verbose/debug logging calls from release builds as defence in depth
# (SAMTEX never logs card data, but this guarantees no android.util.Log output remains).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# BouncyCastle registers algorithm implementations by class name (reflection): keep them all.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
# JMRTD / SCUBA: LDS and SecurityInfo types are instantiated by name.
-keep class org.jmrtd.** { *; }
-keep class net.sf.scuba.** { *; }
-keep class org.ejbca.cvc.** { *; }
-dontwarn org.ejbca.cvc.**
-dontwarn javax.naming.**
