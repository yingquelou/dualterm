# JNI 入口必须保留，否则 native 方法名会被混淆
-keepclasseswithmembernames class dev.dualterm.NativePty {
    native <methods>;
}
-keep class dev.dualterm.NativePty { *; }
