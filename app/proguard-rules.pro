# SAMTEX release rules.
# Hilt, Room and Compose ship their own consumer rules.
# Strip verbose/debug logging calls from release builds as defence in depth
# (SAMTEX never logs card data, but this guarantees no android.util.Log output remains).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
