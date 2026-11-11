# Applied to any app that depends on :core:llama.
# llama-android.cpp binds to these by JNI name, so R8 must not rename or remove them.
-keepclasseswithmembernames class com.reaj.bugassistant.llama.LlamaNative {
    native <methods>;
}
-keep interface com.reaj.bugassistant.llama.TokenCallback { *; }
-keepclassmembers class * implements com.reaj.bugassistant.llama.TokenCallback {
    void onToken(byte[]);
}
