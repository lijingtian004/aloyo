# ALOYO ProGuard Rules

# NCNN相关类不混淆
-keep class com.aloyo.inference.NcnnInferenceEngine { *; }
-keep class com.aloyo.inference.NcnnInferenceEngine$* { *; }

# JNI方法不混淆
-keepclasseswithmembernames class * {
    native <methods>;
}

# 序列化相关
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Gson相关
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.aloyo.common.** { *; }
-keep class * implements com.aloyo.common.** { *; }

# Kotlin协程
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
