-allowaccessmodification
-repackageclasses

# gomobile 绑定（libbox）：JNI 按类名/方法名查找，禁止改名裁剪
-keep class io.nekohasekai.libbox.** { *; }

# kotlinx-serialization：JSON 字段名进持久化/备份档，类与字段名不可混淆
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class * implements kotlinx.serialization.KSerializer { *; }
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** serializer();
}
-dontnote kotlinx.serialization.**

# hev-socks5-tunnel JNI（包名以实际为准，两个候选都保）
-keep class tgz.pstorm.hevsocks5tunnel.** { *; }
-keep class hevsocks5tunnel.** { *; }