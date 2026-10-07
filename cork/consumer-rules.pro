-keep class cork.CorkNative { *; }
-keep class cork.utils.SafUtils { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
