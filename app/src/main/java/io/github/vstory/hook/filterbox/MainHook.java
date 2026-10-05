package io.github.vstory.hook.filterbox;

import static android.util.Log.DEBUG;
import static android.util.Log.INFO;

import android.util.Log;

import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class MainHook extends XposedModule {

    public static final String TAG = "FilterBoxEnhanced";

    private static final String PKG_NP = "com.catchingnow.np";

    private static final String HOOK_DESC =
            "androidx.recyclerview.widget.RecyclerView#canReuseUpdatedViewHolder";

    /** D 级调试通道（静态方法）要用实例调框架 log()，热重载后由 onHotReloaded 重新赋值。 */
    static MainHook sInstance;

    /** 热重载重装时的兜底 ClassLoader，优先从旧 hook handle 反推。 */
    private ClassLoader mAppCl;

    private static int sHookOk;
    private static int sHookFail;
    private static StringBuilder sHookDetail;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        sInstance = this;
        log(INFO, TAG, "api102 module loaded");
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!PKG_NP.equals(param.getPackageName())) {
            return;
        }
        ClassLoader cl = param.getClassLoader();
        log(INFO, TAG, "[pkg] onPackageReady, pkg=" + param.getPackageName()
                + " classLoader=" + (cl != null ? "non-null" : "NULL!"));
        if (cl == null) {
            return;
        }
        mAppCl = cl;
        installHooks(cl);
    }

    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        // 接口默认返回 false；不覆写则旧代码永远拒绝热重载，module.prop 的 autoHotReload 形同虚设
        return true;
    }

    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        sInstance = this;
        ClassLoader cl = classLoaderFrom(param.getOldHookHandles());
        if (cl == null) {
            cl = mAppCl;
        }
        if (cl == null) {
            log(INFO, TAG, "hot reloaded but no usable ClassLoader, hooks not reinstalled");
            return;
        }
        log(INFO, TAG, "hot reloaded, reinstalling hooks");
        mAppCl = cl;
        installHooks(cl);
    }

    /**
     * 从旧 hook handle 反推宿主 ClassLoader。平台类（android.* / java.* / com.android.*）的
     * getClassLoader() 在 ART 上并非 null（返回平台 loader），误用会让宿主类全部 ClassNotFound。
     */
    private static ClassLoader classLoaderFrom(List<XposedInterface.HookHandle> handles) {
        if (handles == null) {
            return null;
        }
        for (XposedInterface.HookHandle h : handles) {
            Class<?> c = h.getExecutable().getDeclaringClass();
            String n = c.getName();
            if (n.startsWith("android.") || n.startsWith("java.") || n.startsWith("com.android.")) {
                continue;
            }
            ClassLoader cl = c.getClassLoader();
            if (cl != null) {
                return cl;
            }
        }
        return null;
    }

    private void installHooks(ClassLoader cl) {
        sHookOk = 0;
        sHookFail = 0;
        sHookDetail = new StringBuilder();
        // #ifdef DEBUG
        if (BuildConfig.DEBUG) {
            dbg("installHooks start, classLoader=" + cl);
        }
        // #endif

        try {
            AnimHook.install(this, cl);
            sHookOk++;
            sHookDetail.append("\n[OK] ").append(HOOK_DESC);
        } catch (Throwable e) {
            sHookFail++;
            sHookDetail.append("\n[FAIL] ").append(HOOK_DESC).append(": ").append(e.getMessage());
            // #ifdef DEBUG
            if (BuildConfig.DEBUG) {
                dbg("hook install FAIL: " + HOOK_DESC + " -> " + e);
            }
            // #endif
        }

        log(INFO, TAG, "installHooks done: " + sHookOk + " OK / " + sHookFail + " FAIL" + sHookDetail);
    }

    // #ifdef DEBUG
    /** D 级调试通道：框架日志（LSPosed 管理器可见）+ logcat 双写。release 下调用点被裁，方法随之消失。 */
    static void dbg(String msg) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        MainHook m = sInstance;
        if (m != null) {
            m.log(DEBUG, TAG, msg);
        }
        Log.d(TAG, msg);
    }
    // #endif
}
