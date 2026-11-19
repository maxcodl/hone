# Shizuku starts the service class by name in the shell process; the AIDL stubs are called across processes.
-keep class io.max.hone.ShellService { *; }
-keep class io.max.hone.IShell { *; }
-keep class io.max.hone.IShell$Stub { *; }
-keep class android.content.pm.IPackageDataObserver { *; }
-keep class android.content.pm.IPackageDataObserver$Stub { *; }
