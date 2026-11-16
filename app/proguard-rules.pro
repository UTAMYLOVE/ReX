# ReX：只縮減、不混淆。使用者回報的堆疊要能直接對回原始碼。
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# AI 解讀用的 Anthropic Java SDK（Jackson 用反射序列化，型別要留著）
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn com.fasterxml.jackson.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn okhttp3.internal.**
-dontwarn com.google.auto.value.**
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn io.opentelemetry.**
-dontwarn com.github.victools.**
# SDK 的 JSON 解析用 Kotlin 反射讀資料類別
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.jvm.internal.** { *; }
-dontwarn kotlin.reflect.jvm.internal.**
