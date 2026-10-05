# 入口类由框架按名字从 META-INF/xposed/java_init.list 反射加载，R8 不得改名
-keep class io.github.vstory.hook.filterbox.MainHook { *; }
