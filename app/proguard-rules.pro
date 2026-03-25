# ── OkHttp ──────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── BouncyCastle ─────────────────────────────────────────────────────────────
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ── JSON ─────────────────────────────────────────────────────────────────────
-keep class org.json.** { *; }

# ── Markwon ──────────────────────────────────────────────────────────────────
-keep class io.noties.markwon.** { *; }
-dontwarn io.noties.markwon.**

# ── App models ───────────────────────────────────────────────────────────────
-keep class com.openclaw.app.Message { *; }
-keep class com.openclaw.app.StreamCallback { *; }
-keep class com.openclaw.app.ApiClient$Attachment { *; }
-keep class com.openclaw.app.ApiClient$ModelsCallback { *; }

# ── Preserve stack traces ────────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile