package io.github.vstory.hook.filterbox;

import static android.util.Log.INFO;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class MainHook extends XposedModule {

    private static final String TAG = "FilterBoxEnhanced";

    private static final String PKG_NP = "com.catchingnow.np";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(INFO, TAG, "loaded: " + param.getProcessName());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!PKG_NP.equals(param.getPackageName())) {
            return;
        }
        AnimHook.install(this, param.getClassLoader());
    }
}
