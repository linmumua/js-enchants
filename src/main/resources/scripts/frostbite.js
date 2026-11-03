// 冻结：命中后让目标进入寒冷状态，并附加挖掘疲劳。
//
// 「寒冷」在 26.3 不是药水效果——PotionEffectType 里没有任何寒冷条目。
// 原版的寒冷由实体的 freezeTicks 承载（细雪里那套），所以这里直接把 freezeTicks 顶上去：
// 表现为屏幕结霜、移速下降、持续冻伤，结算与站在细雪里完全一致。
//
// 三个参数都在 yml 的 options 里：freeze-ticks / fatigue-ticks / fatigue-effect。
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

  freeze(victim, ctx.optionNumber("freeze-ticks", 140));

  var fatigue = ctx.optionNumber("fatigue-ticks", 60);
  if (fatigue > 0) {
    applyEffect(victim, ctx.optionText("fatigue-effect", "MINING_FATIGUE"), Math.round(fatigue), 0);
  }
}

function freeze(victim, ticks) {
  try {
    var wanted = Math.round(ticks);
    if (!(wanted > 0)) {
      return;
    }

    var max = victim.getMaxFreezeTicks();
    // 目标若已有更高的静态度量（例如抗冻），不要把它压回去。
    victim.setFreezeTicks(max > wanted ? max : wanted);
  } catch (e) {
    // 目标跨区或该实体不支持冻结：跳过，挖掘疲劳仍然生效。
  }
}

function applyEffect(victim, typeName, ticks, amplifier) {
  try {
    var type = org.bukkit.potion.PotionEffectType[typeName];
    if (!type) {
      return;
    }

    victim.addPotionEffect(new org.bukkit.potion.PotionEffect(type, ticks, amplifier, false, true, true));
  } catch (e) {
  }
}
