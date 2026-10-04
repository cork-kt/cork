-keep class cork.CorkNative { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
