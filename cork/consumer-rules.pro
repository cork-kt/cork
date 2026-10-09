-keep class cork.CorkNative { *; }
-keep class cork.utils.SafUtils$SafInputEntry { *; }
-keep class cork.utils.SafUtils$SafTreeInput { *; }
-keep class cork.utils.SafUtils$SafTreeOutput { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
