package com.linmumua.jsenchant.trigger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 投递方式的决策表。
 *
 * <p>这张表是 Folia 支持的一半：什么可以在本线程同步跑、什么必须交回触发者自己的线程、
 * 什么只能放弃。它必须能脱离服务端断言——否则「跨区时到底有没有排队」只能靠线上实测。
 */
class TriggerRoutingTest {

    @Test
    void 触发者在本线程时一律同步执行() {
        for (final TriggerType trigger : TriggerType.values()) {
            assertEquals(TriggerRouting.Delivery.SYNC, TriggerRouting.decide(true, trigger),
                    trigger + " 在触发者自己的区域线程上必须同步执行，且事件可改");
        }
    }

    @Test
    void 跨区的实体类触发改为排队到触发者线程() {
        // 这三类可以用实体句柄在触发者自己的线程上重放：背包、等级、脚本参数都在那边重读。
        for (final TriggerType trigger : new TriggerType[]{
                TriggerType.ATTACK, TriggerType.DEFEND, TriggerType.KILL}) {
            assertEquals(TriggerRouting.Delivery.QUEUED, TriggerRouting.decide(false, trigger),
                    trigger + " 跨区时必须排队，不能在本线程硬跑");
        }
    }

    @Test
    void 跨区的方块与射弹触发只能放弃() {
        // 方块与投射物只在原线程有效，带进排队任务就是越界改世界。
        for (final TriggerType trigger : new TriggerType[]{
                TriggerType.MINE, TriggerType.RIGHT_CLICK, TriggerType.SHOOT}) {
            assertEquals(TriggerRouting.Delivery.UNSUPPORTED, TriggerRouting.decide(false, trigger),
                    trigger + " 跨区时无法安全重放");
        }
    }
}
