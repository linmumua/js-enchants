// Lifesteal（吸血）：命中后按本次伤害的比例回复攻击者生命。
// 概率、冷却、等级都由框架算好再调用这里，脚本不要自己再掷一次。
//
// 线程边界（Folia）：dispatcher 只在「触发者自己的区域线程」上把 ctx 交给脚本，
// 所以 ctx.player() 属于当前线程，读血量、写血量都是安全的。
// 绝不读 ctx.victim()：目标可能在别的区域线程，越界访问会直接抛错。
//
// 伤害口径（两种都不等于「目标真实掉的血」）：
//   - 同区（ctx.eventWritable() 为 true）：取事件上的 getFinalDamage()，即本次按当前事件修正计算的最终伤害（尚未实际扣血）。
//   - 跨区（排队回攻击者线程）：原事件已不属于本线程，只能用 ctx.damage() 这份排队前冻结的快照，
//     它是事件的基础 damage 值，不是结算后的最终值。
//   两者都受吸收伤害（黄心）、伤害减免、过量击杀影响，因此这里只是「按伤害数值的比例回血」，
//   不是「目标掉多少血就回多少血」。
function onTrigger(ctx) {
  var level = ctx.level();
  if (level < 1) {
    return;
  }
  if (level > 3) {
    level = 3;
  }

  var damage;
  if (ctx.eventWritable()) {
    // 同区：活事件，本线程可读；getFinalDamage() 是按当前事件修正计算的最终伤害（尚未实际扣血）。
    damage = ctx.event().getFinalDamage();
  } else {
    // 跨区：只有冻结快照，别把它当成目标真实掉血。
    damage = ctx.damage();
  }

  if (!(damage > 0)) {
    return;
  }

  var player = ctx.player();
  var health = player.getHealth();
  if (!(health > 0)) {
    return;
  }

  var maxHealth = player.getMaxHealth();
  if (!(maxHealth > 0)) {
    return;
  }

  // 回复比例按等级从 yml 的 options.heal-ratios 取（默认 20%/40%/60%）。
  var ratio = ctx.byLevel("heal-ratios", 0.2);
  var healed = health + damage * ratio;
  if (healed > maxHealth) {
    healed = maxHealth;
  }
  if (healed <= health) {
    return;
  }

  player.setHealth(healed);
}
