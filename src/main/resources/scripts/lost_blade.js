// 迷失之刃：命中后给目标附加缓慢，达到一定等级再追加反胃。
//
// 时长、触发等级与两种效果的药水名全部在 yml 的 options 里：
//   slowness-seconds  —— 每级时长（秒）列表，等级越界取最后一项
//   nausea-from-level —— 从第几级开始追加反胃；写 0 表示每级都给
//   slowness-effect / nausea-effect —— 药水效果名
//
// 药水类型用按名字取字段的写法：这样某个类型在当前服务端缺失时只会少一个效果，
// 不会让整段脚本抛错、连带把冷却记账搞乱。
var TICKS_PER_SECOND = 20;

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

  var level = ctx.level();
  var ticks = Math.round(ctx.byLevel("slowness-seconds", 1) * TICKS_PER_SECOND);
  if (!(ticks > 0)) {
    return;
  }

  applyEffect(victim, ctx.optionText("slowness-effect", "SLOWNESS"), ticks, 0);

  var nauseaFrom = ctx.optionNumber("nausea-from-level", 2);
  if (nauseaFrom <= 0 || level >= nauseaFrom) {
    // 反胃时长与缓慢一致，避免出现"只在某一级反胃一下"这种读不出来的档位差。
    applyEffect(victim, ctx.optionText("nausea-effect", "NAUSEA"), ticks, 0);
  }
}

// amplifier 0 就是 I 级：缓慢 I 已经把移速压到七折，再往上叠会让目标几乎走不动。
function applyEffect(victim, typeName, ticks, amplifier) {
  try {
    var type = org.bukkit.potion.PotionEffectType[typeName];
    if (!type) {
      return;
    }

    // ambient=false（粒子不明显）、particles=true、icon=true：玩家能在状态栏看到自己被挂了什么。
    victim.addPotionEffect(new org.bukkit.potion.PotionEffect(type, ticks, amplifier, false, true, true));
  } catch (e) {
    // 目标跨区、或该药水类型在本服务端不可用：跳过，不影响另一种效果。
  }
}
