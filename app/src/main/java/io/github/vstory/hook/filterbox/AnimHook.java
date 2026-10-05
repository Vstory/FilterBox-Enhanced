package io.github.vstory.hook.filterbox;

import static android.util.Log.ERROR;
import static android.util.Log.INFO;

import android.content.res.Resources;
import android.view.View;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 消除列表内容刷新时的整行闪烁。
 *
 * <p>滤盒列表走 DiffUtil，正文一变（下载进度、计时类通知每秒都在变）就产出一条 change；
 * RecyclerView 默认动画对 change 的做法是「旧 ViewHolder 淡出 + 新建的 ViewHolder 淡入」，
 * 整行因此一闪一闪。
 *
 * <p>动画没法从外部替换：R8 把 androidx 的实现类全混淆了（DefaultItemAnimator、
 * SimpleItemAnimator、setSupportsChangeAnimations 在 dex 里都不存在，反射不到）。
 * 但 R8 把 SimpleItemAnimator#canReuseUpdatedViewHolder 内联成了
 * {@code RecyclerView#canReuseUpdatedViewHolder(VH)Z}，public 且名字未混淆，是 change
 * 复用决策的唯一入口。对它返回 true，RecyclerView 便复用同一个 ViewHolder 原地重绑，
 * 不产生 cross-fade；该方法只参与 change 决策，新增/删除/移动动画不受影响。
 */
final class AnimHook {

    private static final String TAG = "FilterBoxEnhanced";

    private static final String PKG_NP = "com.catchingnow.np";

    /** R.id.rv 的运行时值。资源名「rv」跨版本稳定，数值会随资源混淆变，0 表示尚未解析。 */
    private static volatile int sListId;

    private static volatile boolean sActive;

    private AnimHook() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        final String desc = "RecyclerView#canReuseUpdatedViewHolder";
        try {
            Class<?> rv = Class.forName("androidx.recyclerview.widget.RecyclerView", false, cl);
            Class<?> vh = Class.forName("androidx.recyclerview.widget.RecyclerView$E", false, cl);
            Method m = rv.getDeclaredMethod("canReuseUpdatedViewHolder", vh);
            m.setAccessible(true);
            // 该方法短小、调用频繁，极易被 AOT 内联进调用点，不 deopt 的话 hook 可能不生效
            module.deoptimize(m);
            module.hook(m).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object self = chain.getThisObject();
                    if (self instanceof View && isFilterBoxList((View) self)) {
                        if (!sActive) {
                            sActive = true;
                            module.log(INFO, TAG, "anim hook active: " + self.getClass().getName());
                        }
                        return Boolean.TRUE;
                    }
                    return chain.proceed();
                }
            });
            module.log(INFO, TAG, "[OK] " + desc);
        } catch (Throwable t) {
            module.log(ERROR, TAG, "[FAIL] " + desc + ": " + t, t);
        }
    }

    /** 只动滤盒自己的列表。第三方组件（ViewPager2、Material 等）内部的 RecyclerView 不碰。 */
    @SuppressWarnings("deprecation")
    private static boolean isFilterBoxList(View rv) {
        int want = sListId;
        if (want == 0) {
            Resources res = rv.getContext().getResources();
            want = res.getIdentifier("rv", "id", PKG_NP);
            if (want == 0) {
                return false;
            }
            sListId = want;
        }
        return rv.getId() == want;
    }
}
