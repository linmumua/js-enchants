// Fireball：攻击时概率在目标处爆炸。
// ctx 由 TriggerDispatcher 注入，等级、概率、冷却都由框架算好。
//
// 威力与音效都在 yml 的 options 里（power-per-level / sound），改配置不用动脚本。
function onTrigger(ctx) {
  if (!ctx.rollChance()) {
    return;
  }

  // 不要写 ctx.victim().getWorld().createExplosion(...)：
  // 目标可能在另一个区域线程上，直接读它的位置就已经越界了。
  // explodeAtTarget 会把动作路由到目标自己的区域线程，
  // 并在那边检查爆炸范围、钳制威力；检查不通过会拒绝并告警。
  ctx.explodeAtTarget(ctx.optionNumber("power-per-level", 1.5) * ctx.level());

  // 音效只会投递到玩家自己的区域线程，跨区也不会从错误线程执行。
  ctx.playSound(ctx.optionText("sound", "ENTITY_GENERIC_EXPLODE"));
}
