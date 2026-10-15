# Mineradio Android —— 保持 JNI 桥接类不被混淆
-keep class com.mineradio.android.NodeRunner { *; }
-keep class com.mineradio.android.MediaBridge { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
