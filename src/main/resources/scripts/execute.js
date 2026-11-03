// 斩杀：概率直接处决血量偏低的非玩家生物。
//
// 为什么默认排除玩家：需求里的「生物」在中文语境指 mob，而玩家最大血量只有 20，
// 若一并处理会让 PVP 变成纯粹的概率抽签。要放开就把 yml 里的 exclude-players 改成 false。
//
// 阈值与溢出伤害也在 yml 的 options 里（max-health / overkill-damage）。
//
// 为什么用 setDamage 而不是 setHealth(0)：走正常伤害结算才会触发死亡掉落、
// 击杀统计与其他插件的死亡事件；直接清零会绕过这些。
function onTrigger(ctx) {
  var victim;
  try {
    victim = ctx.victim();
  } catch (e) {
    return;
  }
  if (null == victim) {
    return;
  }

  if (ctx.optionFlag("exclude-players", true) && victim instanceof org.bukkit.entity.Player) {
    return;
  }

  var health = victim.getHealth();
  var threshold = ctx.optionNumber("max-health", 200);
  if (!(health > 0) || health >= threshold) {
    return;
  }

  // 跨区触发没有可写事件，改不了这一次的伤害结算。
  if (!ctx.eventWritable()) {
    return;
  }

  // 给足溢出伤害：目标的护甲、抗性、吸收都可能吃掉一部分，只加当前血量不保证致死。
  ctx.event().setDamage(health + ctx.optionNumber("overkill-damage", 1000.0));
}
