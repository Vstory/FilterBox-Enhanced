package io.github.vstory.hook.filterbox;

import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 消除列表内容刷新时的整行闪烁。
 *
 * <p>滤盒列表走 DiffUtil，正文一变（下载进度、计时类通知每秒都在变）就产出一条 change。
 * change 有两条会改到视觉的路径，只堵前一条是堵不住的：
 *
 * <p>① {@code RecyclerView#canReuseUpdatedViewHolder} 是 change 复用决策的唯一入口
 * （全 dex 只有 {@code Recycler.scrapView} 一处调用点）。返回 true 时 holder 进 attachedScrap
 * 原地重绑，不会新建 holder 再 cross-fade。
 *
 * <p>② 但 RecyclerView 在 {@code dispatchLayoutStep3} 里对每个 change 都无条件调用
 * {@code animateChange(old, new, preInfo, postInfo)}，而 holder 被复用时 old == new，
 * DefaultItemAnimator 会把这种调用退化成 animateMove(preInfo → postInfo)。通知正文一变、
 * 行高跟着变，这次 move 就是一次真实的整行位移动画 —— 看上去仍是闪。
 * 把 postInfo 的位置对齐到 preInfo 让位移归零；对齐后 animateMove 走 0 位移分支，
 * 收尾 dispatch 照常，不影响 add/remove/move 任何其它动画。
 *
 * <p>动画没法从外部整个换掉：R8 把 androidx 的实现类全混淆了（DefaultItemAnimator 成了
 * androidx.recyclerview.widget.m、SimpleItemAnimator 成了 androidx.recyclerview.widget.E），
 * 反射拿不到；但 RecyclerView 自身的方法名没被混淆，是唯一可用入口。
 */
final class AnimHook {

    private static final String PKG_NP = "com.catchingnow.np";

    /** R.id.rv 的运行时值。资源名「rv」跨版本稳定，数值会随资源混淆变；-1 = 解析过但拿不到。 */
    private static volatile int sListId;

    // #ifdef DEBUG
    private static final AtomicInteger sCalls = new AtomicInteger();
    private static final AtomicInteger sHits = new AtomicInteger();
    private static final AtomicInteger sChanges = new AtomicInteger();
    private static final AtomicInteger sChangeCalls = new AtomicInteger();
    private static final AtomicInteger sMoved = new AtomicInteger();
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

        hookReuse(module, rv, vh);
        try {
            hookChange(module, rv, vh);
        } catch (Throwable e) {
            // ② 失败只该降级成「还闪」，不能把已经装好的 ① 一起废掉
            // #ifdef DEBUG
            if (BuildConfig.DEBUG) {
                MainHook.dbg("hook animateChange FAIL: " + e);
            }
            // #endif
        }

        // #ifdef DEBUG
        if (BuildConfig.DEBUG) {
            startHeartbeat();
        }
        // #endif
    }

    /** ① change 复用决策：返回 true 让 RecyclerView 复用原 ViewHolder，不新建、不 cross-fade。 */
    private static void hookReuse(XposedModule module, Class<?> rv, Class<?> vh) throws Throwable {
        Method m = rv.getDeclaredMethod("canReuseUpdatedViewHolder", vh);
        m.setAccessible(true);
        // 该方法短小且调用频繁，极易被 AOT 内联进调用点，不 deopt 时 hook 可能整体不生效
        module.deoptimize(m);
        module.hook(m).intercept(new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object self = chain.getThisObject();
                boolean target = isFilterBoxList(self);

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
    }

    /** ② change 走到的位移动画：old == new 时把 postInfo 位置对齐 preInfo，让位移归零。 */
    private static void hookChange(XposedModule module, Class<?> rv, Class<?> vh) throws Throwable {
        Method m = findAnimateChange(rv, vh);
        if (m == null) {
            throw new NoSuchMethodException(
                    "RecyclerView.animateChange(VH,VH,ItemHolderInfo,ItemHolderInfo,ZZ)");
        }
        // ItemHolderInfo 的混淆名随版本变，从签名反推比硬编码稳
        Class<?> info = m.getParameterTypes()[2];
        Field left;
        Field top;
        try {
            left = info.getDeclaredField("a");
            top = info.getDeclaredField("b");
        } catch (NoSuchFieldException e) {
            // 字段名随滤盒版本变；拿不到就只留 ①，不去猜字段顺序乱改
            // #ifdef DEBUG
            if (BuildConfig.DEBUG) {
                MainHook.dbg("animateChange: ItemHolderInfo fields not found, skip");
            }
            // #endif
            return;
        }
        left.setAccessible(true);
        top.setAccessible(true);
        final Field fLeft = left;
        final Field fTop = top;

        // 与 ① 同样短小高频，同样要防 AOT 内联
        module.deoptimize(m);
        module.hook(m).intercept(new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object oldHolder = chain.getArg(0);
                if (oldHolder != chain.getArg(1) || !isFilterBoxList(chain.getThisObject())) {
                    return chain.proceed();
                }
                Object pre = chain.getArg(2);
                Object post = chain.getArg(3);
                try {
                    // #ifdef DEBUG
                    if (BuildConfig.DEBUG) {
                        observeChange(pre, post, fLeft, fTop);
                    }
                    // #endif
                    fTop.setInt(post, fTop.getInt(pre));
                    fLeft.setInt(post, fLeft.getInt(pre));
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            }
        });
    }

    /** 按签名而不是名字找：animateChange 是 private，名字会被 R8 改掉，签名不会。 */
    private static Method findAnimateChange(Class<?> rv, Class<?> vh) {
        for (Method m : rv.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getReturnType() == void.class && p.length == 6
                    && p[0] == vh && p[1] == vh && p[2] == p[3]
                    && p[4] == boolean.class && p[5] == boolean.class) {
                return m;
            }
        }
        return null;
    }

    /** 只动滤盒自己的列表。第三方组件（ViewPager2、Material 等）内部的 RecyclerView 不碰。 */
    @SuppressWarnings("deprecation")
    private static boolean isFilterBoxList(Object self) {
        if (!(self instanceof View)) {
            return false;
        }
        int want = sListId;
        if (want == 0) {
            synchronized (AnimHook.class) {
                want = sListId;
                if (want == 0) {
                    try {
                        want = ((View) self).getContext().getResources()
                                .getIdentifier("rv", "id", PKG_NP);
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
        return want > 0 && ((View) self).getId() == want;
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

    /**
     * changeCalls 是 old == new 的 change 次数，moved 是其中 pre/post 位置真的不同、会退化成
     * move 位移的次数。moved 为 0 就说明闪与动画无关，不必再往这条路修。
     */
    private static void observeChange(Object pre, Object post, Field left, Field top) {
        sChangeCalls.incrementAndGet();
        int preLeft;
        int preTop;
        int postLeft;
        int postTop;
        try {
            preLeft = ((Integer) left.get(pre)).intValue();
            preTop = ((Integer) top.get(pre)).intValue();
            postLeft = ((Integer) left.get(post)).intValue();
            postTop = ((Integer) top.get(post)).intValue();
        } catch (Throwable e) {
            return;
        }
        if (preLeft == postLeft && preTop == postTop) {
            return;
        }
        int n = sMoved.incrementAndGet();
        if (sDetail.incrementAndGet() > DETAIL_PER_WINDOW) {
            return;
        }
        MainHook.dbg("moved #" + n + " pre=(" + preLeft + "," + preTop + ") post=("
                + postLeft + "," + postTop + ")");
    }
    // #endif

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
                    int changeCalls = sChangeCalls.getAndSet(0);
                    int moved = sMoved.getAndSet(0);
                    sDetail.set(0);
                    // 全 0 也照打：这是区分「hook 未被调用」和「模块没注入」的唯一依据
                    MainHook.dbg("stat 60s: calls=" + calls + " hits=" + hits
                            + " changes=" + changes + " changeCalls=" + changeCalls
                            + " moved=" + moved);
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
