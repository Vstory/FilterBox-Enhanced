package io.github.vstory.hook.filterbox;

import static android.util.Log.ERROR;
import static android.util.Log.INFO;
import static android.util.Log.WARN;

import android.service.notification.NotificationListenerService;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 通知滤盒（com.catchingnow.np）AI 智能过滤的伴生模块。
 *
 * <p>目标：被滤盒放行的通知，必须和没装滤盒时一样正常响铃、震动。
 *
 * <p>滤盒对每条到达的通知先 {snoozeNotification(key, 2400|4800)} 压延迟、再异步算分，
 * 这一下会把通知连同正在响的铃声/震动一起掐掉（NMS 的 cancel → clearEffectsLocked 按 key
 * 同时清 mSoundNotificationKey 和 mVibrateNotificationKey），2.4s 后才重发 —— 放行的通知
 * 因此不响。本模块只把这一档写死的预延迟压短，等算分一完成就重发，不碰滤盒的判定与撤销。
 */
public class MainHook extends XposedModule {

    private static final String TAG = "FilterBoxEnhanced";

    private static final String PKG_NP = "com.catchingnow.np";

    /** 滤盒 AI 过滤写死的预延迟两档（模型就绪 / 未就绪），见 q3.h.c 的 :goto_300。 */
    private static final long[] NP_PRE_POSTPONE_MS = {2400L, 4800L};

    /** 替换值：只需覆盖一次 tflite 推理。滤盒给的 2400ms 是保守余量，不是实际耗时。 */
    private static final long REPLACED_DELAY_MS = 600L;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(INFO, TAG, "loaded: " + param.getProcessName());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!PKG_NP.equals(param.getPackageName())) {
            return;
        }
        ClassLoader cl = param.getClassLoader();
        hookSnooze();
        hookScoring(cl);
    }

    private void hookSnooze() {
        final String desc = "NotificationListenerService#snoozeNotification";
        try {
            Method m = NotificationListenerService.class
                    .getDeclaredMethod("snoozeNotification", String.class, long.class);
            m.setAccessible(true);
            hook(m).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object raw = chain.getArg(1);
                    long ms = raw instanceof Long ? (Long) raw : -1L;
                    if (!isPrePostpone(ms)) {
                        // 用户手动延时等其它 snooze 一律原样放行
                        return chain.proceed();
                    }
                    log(INFO, TAG, "snooze " + ms + "ms -> " + REPLACED_DELAY_MS + "ms key="
                            + chain.getArg(0));
                    return chain.proceed(new Object[]{chain.getArg(0), REPLACED_DELAY_MS});
                }
            });
            log(INFO, TAG, "[OK] " + desc);
        } catch (Throwable t) {
            log(ERROR, TAG, "[FAIL] " + desc + ": " + t, t);
        }
    }

    /** 算分入口（q3.h.c 里那个异步 invoke）。只记录耗时：这个数决定预延迟能压到多短。 */
    private void hookScoring(ClassLoader cl) {
        final String desc = "scoring(n3.g#invoke)";
        try {
            Class<?> c = Class.forName("n3.g", false, cl);
            Method m = c.getDeclaredMethod("invoke");
            m.setAccessible(true);
            hook(m).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    long t0 = System.nanoTime();
                    try {
                        return chain.proceed();
                    } finally {
                        log(INFO, TAG, "scoring " + ((System.nanoTime() - t0) / 1_000_000L) + "ms");
                    }
                }
            });
            log(INFO, TAG, "[OK] " + desc);
        } catch (Throwable t) {
            // 混淆名只用于观测，失手不影响主功能
            log(WARN, TAG, "[SKIP] " + desc + ": " + t);
        }
    }

    private static boolean isPrePostpone(long ms) {
        for (long v : NP_PRE_POSTPONE_MS) {
            if (v == ms) {
                return true;
            }
        }
        return false;
    }
}
