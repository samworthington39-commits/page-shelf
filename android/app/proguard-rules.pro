# Retrofit and Gson discover API interfaces and DTOs through reflection. Keeping
# only their fields lets R8 full mode remove response classes that are referenced
# solely from generic method signatures, which breaks a successful login response.
-keepattributes Signature,*Annotation*
-keep class com.example.bookshelf.data.remote.** {
    *;
}

# sherpa-onnx enters Kotlin API methods from JNI while generating speech.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
