// 自动熔炼：把挖到的方块直接换成熟成品，省掉回家开炉子那一步。
//
// 为什么必须取消事件：框架没有监听 BlockDropItemEvent，掉落物只能在原版结算之前拦下来。
// 因此在 HIGHEST 上取消 BlockBreakEvent，自己清空方块并掉落产物。
// 代价（已知且不隐藏）：这一块不再给挖掘经验，也不再走原版的时运/精准采集判定，
// 工具耐久已经由原版扣过，不会重复扣。
//
// 映射表在 yml 的 options.smelt 里，每条写成 "源:产物"。
// 不放在脚本里，是因为「哪些方块能熔炼」是最常被改的一项，改它不该动脚本。
function onTrigger(ctx) {
  var block;
  try {
    block = ctx.block();
  } catch (e) {
    return;
  }
  if (null == block) {
    return;
  }

  var product = smeltTable(ctx)[block.getType().name()];
  if (null == product) {
    return;
  }

  // 跨区触发拿不到可写事件；此时原版掉落已经定了，硬改只会变成复制物品。
  if (!ctx.eventWritable()) {
    return;
  }

  var event = ctx.event();
  if (event.isCancelled()) {
    return;
  }

  var output = org.bukkit.Material.matchMaterial(product);
  if (null == output) {
    return;
  }

  event.setCancelled(true);

  var location = block.getLocation();
  location.add(0.5, 0.5, 0.5);
  var world = block.getWorld();

  block.setType(org.bukkit.Material.AIR);
  world.dropItemNaturally(location, new org.bukkit.inventory.ItemStack(output, 1));
}

// 每次触发重建一次映射表：条目就二十来条，解析成本远小于一趟挖掘；
// 换来的是改完 yml 重载后立刻生效，不会留着上一份映射。
function smeltTable(ctx) {
  var entries = ctx.optionList("smelt");
  var table = {};

  for (var i = 0; i < entries.length; i++) {
    var pair = parsePair(entries[i]);
    if (null != pair) {
      table[pair[0]] = pair[1];
    }
  }

  return table;
}

// 纯函数：把 "IRON_ORE:IRON_INGOT" 解析成 ["IRON_ORE", "IRON_INGOT"]，认不出返回 null。
// 冒号与 > 都接受（> 不需要在 YAML 里加引号，写起来更省事）。
function parsePair(raw) {
  if (null == raw) {
    return null;
  }

  var text = ("" + raw).trim();
  if (text.length === 0 || text.charAt(0) === "#") {
    return null;
  }

  var separator = text.indexOf(":");
  if (separator < 0) {
    separator = text.indexOf(">");
  }
  if (separator <= 0) {
    return null;
  }

  var from = text.substring(0, separator).trim();
  var to = text.substring(separator + 1).trim();

  if (from.length === 0 || to.length === 0) {
    return null;
  }

  return [from.toUpperCase(), to.toUpperCase()];
}
