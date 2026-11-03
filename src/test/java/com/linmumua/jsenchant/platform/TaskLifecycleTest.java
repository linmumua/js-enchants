package com.linmumua.jsenchant.platform;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生命周期守卫的纯逻辑校验。
 *
 * <p>为什么这条逻辑值得单独立测试：Folia 的调度不阻塞，任务提交出去之后要靠「停用时的
 * 取消 + 执行前的复查」两层才行。搞错任何一层，后果都是插件停用之后还有代码在碰已经
 * 关掉的监听器与脚本引擎——这类故障只在关服/重载那一瞬间出现，靠手工测试几乎抓不到。
 */
class TaskLifecycleTest {

    private static final Logger LOGGER = Logger.getLogger("JsEnchantsTest");

    /** 记录取消次数的假句柄。 */
    private static final class Handle {

        private final AtomicInteger cancelled = new AtomicInteger();

        Runnable cancel() {
            return this.cancelled::incrementAndGet;
        }

        int cancelledCount() {
            return this.cancelled.get();
        }
    }

    @Test
    void 未关闭时是活跃的() {
        assertTrue(new TaskLifecycle().active());
    }

    @Test
    void 关闭后会取消所有已登记的句柄() {
        final TaskLifecycle lifecycle = new TaskLifecycle();
        final Handle first = new Handle();
        final Handle second = new Handle();

        lifecycle.track("first", first.cancel());
        lifecycle.track("second", second.cancel());

        lifecycle.close(LOGGER);

        assertFalse(lifecycle.active(), "关闭后必须立刻变为不活跃");
        assertEquals(1, first.cancelledCount(), "已登记的任务必须被取消");
        assertEquals(1, second.cancelledCount(), "已登记的任务必须被取消");
    }

    @Test
    void 关闭后登记的句柄会被立即取消而不是留到以后() {
        final TaskLifecycle lifecycle = new TaskLifecycle();
        final Handle late = new Handle();

        lifecycle.close(LOGGER);
        lifecycle.track("late", late.cancel());

        assertEquals(1, late.cancelledCount(), "关闭后提交成功的任务必须马上取消，不能留在待办里");
    }

    @Test
    void 已执行完的任务撤销登记后不再被取消() {
        final TaskLifecycle lifecycle = new TaskLifecycle();
        final Handle finished = new Handle();

        lifecycle.track("finished", finished.cancel());
        lifecycle.release("finished");

        lifecycle.close(LOGGER);

        assertEquals(0, finished.cancelledCount(), "跑完的任务不该在停用时再被取消一次");
    }

    @Test
    void 重复关闭只取消一次() {
        final TaskLifecycle lifecycle = new TaskLifecycle();
        final Handle handle = new Handle();

        lifecycle.track("one", handle.cancel());
        lifecycle.close(LOGGER);
        lifecycle.close(LOGGER);

        assertEquals(1, handle.cancelledCount(), "关闭必须幂等");
    }

    @Test
    void 取消句柄出错不阻断其余句柄的取消() {
        final List<String> cancelled = new ArrayList<>();
        final TaskLifecycle lifecycle = new TaskLifecycle();

        lifecycle.track("boom", () -> {
            throw new IllegalStateException("取消失败");
        });
        lifecycle.track("ok", () -> cancelled.add("ok"));

        lifecycle.close(LOGGER);

        assertEquals(List.of("ok"), cancelled, "一个句柄取消失败不能拖住其它句柄");
    }
}
