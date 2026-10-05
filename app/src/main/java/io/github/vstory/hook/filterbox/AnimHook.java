package io.github.vstory.hook.filterbox;

import static android.util.Log.ERROR;
import static android.util.Log.INFO;

import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
 *
 * <p>debug 变体额外输出观测日志：hook 是否被调用、原决策返回什么、id 是否匹配、change
 * 频率多少。release 变体只留 [OK]/[FAIL]。
 */
final class AnimHook {

    private static final String TAG = "FilterBoxEnhanced";

    private static final String PKG_NP = "com.catchingnow.np";

    /** 观测窗口等于心跳间隔，窗口内明细限量，避免每秒刷新的列表把 logcat 环形缓冲冲掉。 */
    private static final long HEARTBEAT_MS = 60_000L;
    private static final int DETAIL_PER_WINDOW = 5;

    /** R.id.rv 的运行时值。资源名「rv」跨版本稳定，数值会随资源混淆变。 */
    private static volatile int sListId;
    private static volatile boolean sIdResolved;

    private static volatile XposedModule sModule;

    private static final AtomicInteger sCalls = new AtomicInteger();
    private static final AtomicInteger sHits = new AtomicInteger();
    /** 命中滤盒列表的调用里，原实现本来返回 false 的次数 —— 即真正的 change 决策数。 */
    private static final AtomicInteger sChanges = new AtomicInteger();
    private static final AtomicInteger sDetail = new AtomicInteger();

    private static final Set<Integer> sSkippedIds = ConcurrentHashMap.newKeySet();

    private AnimHook() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        sModule = module;
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
                    sCalls.incrementAndGet();
                    Object self = chain.getThisObject();
                    if (!(self instanceof View) || !isFilterBoxList((View) self)) {
                        noteSkipped(self);
                        return chain.proceed();
                    }
                    sHits.incrementAndGet();
                    // 原实现无副作用，debug 下先跑一遍拿真实决策：既能量出 change 频率，也证明 hook 拦在了调用点上。
                    // release 下这段整块消失，只留 return TRUE。
                    if (BuildConfig.DEBUG) {
                        boolean wasChange;
                        try {
                            wasChange = !Boolean.TRUE.equals(chain.proceed());
                        } catch (Throwable t) {
                            log(ERROR, "orig threw: " + t);
                            return Boolean.TRUE;
                        }
                        if (wasChange) {
                            sChanges.incrementAndGet();
                            detail(self, chain.getArg(0));
                        }
                    }
                    return Boolean.TRUE;
                }
            });
            module.log(INFO, TAG, "[OK] " + desc);
            startHeartbeat();
        } catch (Throwable t) {
            module.log(ERROR, TAG, "[FAIL] " + desc + ": " + t, t);
        }
    }

    /** 只动滤盒自己的列表。第三方组件（ViewPager2、Material 等）内部的 RecyclerView 不碰。 */
    @SuppressWarnings("deprecation")
    private static boolean isFilterBoxList(View rv) {
        if (!sIdResolved) {
            synchronized (AnimHook.class) {
                if (!sIdResolved) {
                    int id = 0;
                    try {
                        id = rv.getContext().getResources().getIdentifier("rv", "id", PKG_NP);
                    } catch (Throwable t) {
                        dbg("id lookup failed: " + t);
                    }
                    sListId = id;
                    sIdResolved = true;
                    dbg("R.id.rv = " + (id == 0 ? "NOT FOUND" : "0x" + Integer.toHexString(id)));
                }
            }
        }
        int want = sListId;
        return want != 0 && rv.getId() == want;
    }

    /** 记下滤盒里除 R.id.rv 之外的 RecyclerView，用来区分「id 不匹配」和「本来就没有」。 */
    private static void noteSkipped(Object self) {
        if (!BuildConfig.DEBUG || !(self instanceof View)) {
            return;
        }
        int id = ((View) self).getId();
        if (sSkippedIds.add(id)) {
            dbg("skip rv id=0x" + Integer.toHexString(id) + " " + self.getClass().getName());
        }
    }

    private static void detail(Object self, Object holder) {
        if (sDetail.incrementAndGet() > DETAIL_PER_WINDOW) {
            return;
        }
        dbg("change #" + sChanges.get() + " " + name(self) + " holder=" + name(holder)
                + " children=" + childCount(self));
    }

    private static void startHeartbeat() {
        if (!BuildConfig.DEBUG) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(HEARTBEAT_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    int calls = sCalls.getAndSet(0);
                    int hits = sHits.getAndSet(0);
                    int changes = sChanges.getAndSet(0);
                    sDetail.set(0);
                    // 全 0 也照打：这是区分「hook 没被调用」和「模块没注入」的唯一依据
                    dbg("stat 60s: calls=" + calls + " hits=" + hits + " changes=" + changes);
                }
            }
        }, "fb-anim-stat");
        t.setDaemon(true);
        t.start();
    }

    private static String name(Object o) {
        return o == null ? "null"
                : o.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(o));
    }

    private static int childCount(Object o) {
        return o instanceof ViewGroup ? ((ViewGroup) o).getChildCount() : -1;
    }

    private static void dbg(String msg) {
        if (BuildConfig.DEBUG) {
            log(INFO, msg);
        }
    }

    private static void log(int prio, String msg) {
        XposedModule m = sModule;
        if (m != null) {
            m.log(prio, TAG, msg);
        }
    }
}
