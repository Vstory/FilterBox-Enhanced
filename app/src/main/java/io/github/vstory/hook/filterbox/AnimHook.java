package io.github.vstory.hook.filterbox;

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
 */
final class AnimHook {

    private static final String PKG_NP = "com.catchingnow.np";

    /** R.id.rv 的运行时值。资源名「rv」跨版本稳定，数值会随资源混淆变；-1 = 解析过但拿不到。 */
    private static volatile int sListId;

    // #ifdef DEBUG
    private static final AtomicInteger sCalls = new AtomicInteger();
    private static final AtomicInteger sHits = new AtomicInteger();
    private static final AtomicInteger sChanges = new AtomicInteger();
    private static final AtomicInteger sDetail = new AtomicInteger();
    private static final Set<Integer> sSkippedIds = ConcurrentHashMap.newKeySet();
    /** 观测窗口等于心跳间隔；窗口内明细限量，防每秒刷新的列表冲掉日志环形缓冲。 */
    private static final long HEARTBEAT_MS = 60_000L;
    private static final int DETAIL_PER_WINDOW = 5;
    // #endif

    private AnimHook() {
    }

    static void install(XposedModule module, ClassLoader cl) throws Throwable {
        Class<?> rv = Class.forName("androidx.recyclerview.widget.RecyclerView", false, cl);
        Class<?> vh = Class.forName("androidx.recyclerview.widget.RecyclerView$E", false, cl);
        Method m = rv.getDeclaredMethod("canReuseUpdatedViewHolder", vh);
        m.setAccessible(true);
        // 该方法短小且调用频繁，极易被 AOT 内联进调用点，不 deopt 时 hook 可能整体不生效
        module.deoptimize(m);
        module.hook(m).intercept(new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object self = chain.getThisObject();
                boolean target = self instanceof View && isFilterBoxList((View) self);

                if (!target) {
                    // #ifdef DEBUG
                    if (BuildConfig.DEBUG) {
                        sCalls.incrementAndGet();
                        noteSkipped(self);
                    }
                    // #endif
                    return chain.proceed();
                }

                // #ifdef DEBUG
                if (BuildConfig.DEBUG) {
                    sCalls.incrementAndGet();
                    sHits.incrementAndGet();
                    // 原实现无副作用，跑一遍只为量出真实决策：既得 change 频率，也证明拦在了调用点上
                    if (!Boolean.TRUE.equals(chain.proceed())) {
                        sChanges.incrementAndGet();
                        detailChange(self, chain.getArg(0));
                    }
                }
                // #endif
                return Boolean.TRUE;
            }
        });

        // #ifdef DEBUG
        if (BuildConfig.DEBUG) {
            startHeartbeat();
        }
        // #endif
    }

    /** 只动滤盒自己的列表。第三方组件（ViewPager2、Material 等）内部的 RecyclerView 不碰。 */
    @SuppressWarnings("deprecation")
    private static boolean isFilterBoxList(View rv) {
        int want = sListId;
        if (want == 0) {
            synchronized (AnimHook.class) {
                want = sListId;
                if (want == 0) {
                    try {
                        want = rv.getContext().getResources().getIdentifier("rv", "id", PKG_NP);
                    } catch (Throwable ignored) {
                    }
                    // #ifdef DEBUG
                    if (BuildConfig.DEBUG) {
                        MainHook.dbg("R.id.rv = "
                                + (want == 0 ? "NOT FOUND" : "0x" + Integer.toHexString(want)));
                    }
                    // #endif
                    sListId = want == 0 ? -1 : want;
                    want = sListId;
                }
            }
        }
        return want > 0 && rv.getId() == want;
    }

    // #ifdef DEBUG
    /** 记下滤盒里除 R.id.rv 之外的 RecyclerView —— 用来区分「id 不匹配」和「本来没有别的列表」。 */
    private static void noteSkipped(Object self) {
        if (!(self instanceof View)) {
            return;
        }
        int id = ((View) self).getId();
        if (sSkippedIds.add(id)) {
            MainHook.dbg("skip rv id=0x" + Integer.toHexString(id) + " " + self.getClass().getName());
        }
    }

    private static void detailChange(Object self, Object holder) {
        if (sDetail.incrementAndGet() > DETAIL_PER_WINDOW) {
            return;
        }
        MainHook.dbg("change #" + sChanges.get() + " " + name(self) + " holder=" + name(holder)
                + " children=" + childCount(self));
    }

    private static void startHeartbeat() {
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
                    // 全 0 也照打：这是区分「hook 未被调用」和「模块没注入」的唯一依据
                    MainHook.dbg("stat 60s: calls=" + calls + " hits=" + hits
                            + " changes=" + changes);
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
    // #endif
}
