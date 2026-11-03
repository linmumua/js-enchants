// 缴械：概率打掉目标的主手物品——要么和背包随机一格交换，要么直接掉在地上。
//
// 两种结果各占一半（由 yml 的 options.drop-chance 控制）：要么和背包随机一格交换，
// 要么直接掉在地上。生物没有玩家背包（拿不到 36 格存储），只有装备槽，
// 因此对生物只能丢弃。

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

  if (victim instanceof org.bukkit.entity.Player) {
    disarmPlayer(ctx, victim);
    return;
  }

  disarmMob(victim);
}

function disarmPlayer(ctx, player) {
  var inventory = player.getInventory();
  var main = inventory.getItemInMainHand();

  if (isEmpty(main)) {
    return;
  }

  if (Math.random() < ctx.optionNumber("drop-chance", 0.5)) {
    inventory.setItemInMainHand(null);
    drop(player, main);
    return;
  }

  // 只在前 N 格主存储里挑：再往后是护甲与副手槽，把护甲换到手上会变成穿不回去的怪东西。
  var storage = Math.round(ctx.optionNumber("storage-slots", 36));
  var slots = Math.min(storage, inventory.getSize());
  if (!(slots > 0)) {
    return;
  }

  var slot = Math.floor(Math.random() * slots);
  var swapped = inventory.getItem(slot);

  inventory.setItem(slot, main);
  inventory.setItemInMainHand(swapped);
}

function disarmMob(victim) {
  try {
    var equipment = victim.getEquipment();
    if (null == equipment) {
      return;
    }

    var held = equipment.getItemInMainHand();
    if (isEmpty(held)) {
      return;
    }

    equipment.setItemInMainHand(null);
    drop(victim, held);
  } catch (e) {
    // 跨区目标或该实体没有装备接口：放弃本次缴械。
  }
}

function isEmpty(item) {
  return null == item || item.getType().isAir();
}

function drop(entity, item) {
  try {
    entity.getWorld().dropItemNaturally(entity.getLocation(), item);
  } catch (e) {
    // 掉落失败就把物品留在原处，不吞东西。
  }
}
