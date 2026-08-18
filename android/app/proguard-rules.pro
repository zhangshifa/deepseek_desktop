# 默认不开启混淆（isMinifyEnabled=false），保留此文件备用。
# 如需启用混淆，可在此添加 keep 规则。
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
