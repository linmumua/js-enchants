package com.linmumua.jsenchant.trigger;

/**
 * 一次触发该怎么投递——同区同步、跨区排队、还是只能放弃。
 *
 * <p>从 {@link TriggerDispatcher} 里抽出来的原因和 {@link TriggerPolicy} 一样：这是纯决策，
 * 输入只有「当前线程是否拥有触发者」和触发类型，不需要服务端就能断言。
 * 派发器里混着活事件，任何判断都只能靠实测。
 *
 * <p>规则本身就三条，写在 {@link #decide(boolean, TriggerType)} 里。
 */
final class TriggerRouting {

    /** 三种投递方式。 */
    enum Delivery {

        /** 当前线程拥有触发者：同步执行，事件可改。 */
        SYNC,

        /** 触发者不在本线程：排队到它自己的实体调度器，事件不可改。 */
        QUEUED,

        /** 无法安全重放：放弃并限流告警。 */
        UNSUPPORTED
    }

    private TriggerRouting() {
    }

    /**
     * @param actorOwned 当前线程是否拥有触发者（触发物品的持有者）
     * @param trigger    触发类型
     */
    static Delivery decide(final boolean actorOwned, final TriggerType trigger) {
        if (actorOwned) {
            return Delivery.SYNC;
        }

        // 攻击 / 被攻击 / 击杀的目标是实体，可以用「目标实体句柄」在它自己的线程上重放：
        // 背包、等级、脚本参数全都在那边重新读一遍，不需要任何跨区数据。
        // 击杀事件尤其常见：它在死者的区域线程上触发，凶手很可能在另一个区域。
        if (TriggerType.ATTACK == trigger || TriggerType.DEFEND == trigger || TriggerType.KILL == trigger) {
            return Delivery.QUEUED;
        }

        // 挖掘 / 右键 / 射箭的对象是方块与投射物，只在当前线程有效。
        // 重放等于把外区引用塞进排队任务里，宁可放弃也不要从错误的线程改世界。
        return Delivery.UNSUPPORTED;
    }
}
