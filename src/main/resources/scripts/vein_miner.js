// 连锁采集：从被挖掉的那块出发，沿相连的同类方块继续开采。
//
// ── 哪些方块允许起手连锁：由附魔 yml 的 options.blocks 白名单决定 ──────────
// 三种写法可以混用（大小写不敏感）：
//   1) 原版方块名      "DIAMOND_ORE"（也可写 "minecraft:diamond_ore"）
//   2) 原版方块标签    "#minecraft:ores"、"#logs"（带 # 即按标签处理，走 org.bukkit.Tag）
//   3) CE 自定义方块   "betterend:amber_ore"（含 : 且命名空间不是 minecraft，即按
//                      CraftEngine 的方块 id 比对；需要服上装了 CraftEngine）
//
// 白名单留空（不写 options、或 blocks 为空列表）时回退到升级前的行为：
// 原版矿物标签 ORES、远古残骸/萤石、原木标签 LOGS、菌柄，外加"除地形方块外都连锁"的
// CHAIN_CUSTOM 兜底。这条回退存在的理由很实际——白名单是新加的字段，
// 存量 yml 里没有它，升级后连锁采集不能直接失效。
//
// 白名单非空时只有列出来的方块能起手，"任意非地形方块都连锁"的兜底不再生效：
// 那正是本次要关掉的（CE 方块与原版载体区分不开时，一镐能把成片地形带走）。
//
// ── 触发条件与代价 ──────────────────────────────────────────────────
// 三个旋钮都在 yml 的 options 里，改完 /jse reload 即生效：
//
//   require-sneak: true         只有潜行挖掘才起手连锁；站着挖就是普通的一镐。
//   durability-multiplier: 1.2  连锁破坏 N 块的总耐久 = floor(N × 1.2)。
//   hunger-per-block: 0.1       连锁破坏一块额外消耗 0.1 点饱食度。
//
// 悬停预览跟着 require-sneak 走：站着不显示描边，蹲下才显示——
// 否则"看起来能连锁、挖下去却不行"比不显示更误导。
//
// 耐久为什么要在连锁后自己结算：breakNaturally 到底扣不扣工具耐久取决于服务端实现，
// 这里不去赌。连锁前后各读一次剩余耐久，算出它实际扣了多少，再只补到 floor(N × 1.2)。
// 无论它已经扣了 N 点还是一点没扣，最终总量都是这个值。
//
// ── 为什么需要 CE 判定 ────────────────────────────────────────────
// CraftEngine 把自定义方块编码成"一个原版方块 + 一段独特 blockdata"，所以只看 Bukkit
// 侧的 Material 是分不清"这是自定义矿"还是"这是原版钻石矿"的。
// 现在直接问 CE：isCustomBlock(block) → getCustomBlockState(block).customBlockState().ownerId()，
// 拿到的就是资源包里那个 namespace:value。
//
// 脚本沙箱要放行 net.momirealms.craftengine.（见 config.yml 的 scripts.allowed-packages），
// 并且 plugin.yml 的 softdepend 里要声明 CraftEngine，类才在本插件的类加载器里可见。
// 没装 CE 时下面的调用会整体降级成"不是自定义方块"，连锁照常用原版名单工作。
var CHAIN_CUSTOM = true;

// 绝对不连锁的地形方块：这类方块成片存在，纳入连锁等于一镐清空地形。
// 只在白名单留空（回退旧行为）时生效。
var TERRAIN = {
  "STONE": 1, "DEEPSLATE": 1, "COBBLESTONE": 1, "COBBLED_DEEPSLATE": 1,
  "DIRT": 1, "COARSE_DIRT": 1, "ROOTED_DIRT": 1, "GRASS_BLOCK": 1, "PODZOL": 1,
  "MYCELIUM": 1, "MUD": 1, "CLAY": 1, "GRAVEL": 1, "SAND": 1, "RED_SAND": 1,
  "SANDSTONE": 1, "RED_SANDSTONE": 1, "NETHERRACK": 1, "END_STONE": 1,
  "TUFF": 1, "CALCITE": 1, "ANDESITE": 1, "DIORITE": 1, "GRANITE": 1,
  "BASALT": 1, "BLACKSTONE": 1, "SOUL_SAND": 1, "SOUL_SOIL": 1,
  "SNOW_BLOCK": 1, "ICE": 1, "PACKED_ICE": 1, "BLUE_ICE": 1,
  "MOSS_BLOCK": 1, "SCULK": 1, "BEDROCK": 1, "OBSIDIAN": 1
};

var MAX_BLOCKS = [8, 16, 24];
var SEARCH_RADIUS = 4;
var DIRECTIONS = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];

// yml options 里的键名。
var BLOCKS_OPTION = "blocks";
var SNEAK_OPTION = "require-sneak";
var DURABILITY_OPTION = "durability-multiplier";
var HUNGER_OPTION = "hunger-per-block";

// options 里没写这几项时用的值：老配置不写也照常工作，行为等同于下表的默认值。
var DEFAULT_REQUIRE_SNEAK = true;
var DEFAULT_DURABILITY_MULTIPLIER = 1.2;
var DEFAULT_HUNGER_PER_BLOCK = 0.1;

// 原版换算：4.0 疲劳值（exhaustion）折 1 点饱食度。
// 饱食度本身是整数，扣不动 0.1 这种小数，所以累到疲劳值上交给原版结算。
var EXHAUSTION_PER_FOOD_POINT = 4.0;

// ── 高光 ────────────────────────────────────────────────────────────
// 用的是「只发给触发玩家」的粒子：Player#spawnParticle 本身就是单播，
// 服务端只把包发给这一名玩家，别人屏幕上什么都没有——这就是"只有自己看得到"。
//
// 时机限制（必须知道）：框架的 MINE 时机是方块被破坏的那一刻，
// 所以这里是"连锁扫过去时的闪光",不是"挖掘前先亮出范围"。
// 粒子寿命约 1 秒，方块消失后光点还留在原位，视觉上能看到一条连锁轨迹。
// 想要真正的事前预览，需要"开始挖掘"的触发时机 + 定时刷新，那要改核心。
var HIGHLIGHT = true;
var HIGHLIGHT_PARTICLE = "END_ROD";
var HIGHLIGHT_COUNT = 4;
var HIGHLIGHT_SPREAD = 0.28;

// 防重入：breakNaturally 会再派一次 BlockBreakEvent，直接就会回到本脚本。
// 按执行线程各一份（每个线程独立作用域），只挡住本次连锁内部的回派。
var CHAINING = false;

function onTrigger(ctx) {
  if (CHAINING) {
    return;
  }

  var block;
  try {
    block = ctx.block();
  } catch (e) {
    return;
  }
  if (null == block) {
    return;
  }

  if (!canStartChain(ctx, block)) {
    return;
  }

  var player = ctx.player();
  if (null == player) {
    return;
  }

  // 潜行才连锁：这道闸最好解释——站着挖就是普通的一镐。
  if (requireSneak(ctx) && !player.isSneaking()) {
    return;
  }

  var fingerprint = identity(block);
  var level = Math.min(Math.max(ctx.level(), 1), MAX_BLOCKS.length);
  var limit = MAX_BLOCKS[level - 1];

  var tool = player.getInventory().getItemInMainHand();
  var before = durabilityLeft(tool);

  CHAINING = true;
  var broken = 0;
  try {
    broken = chain(block, fingerprint, limit, tool, player);
  } finally {
    // 即使中途抛错也必须复位，否则这名玩家所在线程之后再也连锁不了。
    CHAINING = false;
  }

  // 代价必须在连锁之后结算：只有这时才知道真正挖掉了几块。
  chargeHunger(player, broken, hungerPerBlock(ctx));
  chargeWear(player, before, broken, durabilityMultiplier(ctx));
}

// 指纹：只按"方块是什么"判断，**不看朝向与其它方块状态**。
//
// 为什么不再用 blockData：原木的 axis、楼梯的 facing 都写在 blockData 里，
// 拿它做指纹会要求"朝向也必须一致"。竖着放的原木挨着横着放的原木就不算连通，
// 这在真实矿脉/树里很常见，表现为"明明连在一起却断链"，很难解释。
//
// 那 CE 方块怎么办？CE 把自定义身份编码在方块状态里，看起来必须用 blockData 才能区分。
// 但它给了 ownerId()——那是**方块 id**（如 betterend:amber_ore），不含朝向等属性。
// 所以：
//   * CE 自定义方块 → 用 ownerId()，既精确又不含朝向；
//   * 原版方块     → 直接用材质名，天然忽略一切状态属性；
//   * 拿不到 CE id 时退回原版材质，绝不因为 CE 没装就断链。
//
// 注意 ownerId() 不会误伤"同 id 不同朝向"的 CE 方块：朝向是状态属性，不影响 id。
// 代价是同一材质但状态有意义的原版方块（例如含水与否）现在会连锁到一起，
// 对本功能（挖矿/砍树）这正是想要的效果。
function identity(block) {
  var ceId = customBlockId(block);
  if (null != ceId) {
    return "ce:" + ceId;
  }

  return "minecraft:" + block.getType().name();
}

// 起手方块能不能连锁。白名单只在这里生效：一旦起手，扩散范围仍由 identity() 决定，
// 所以"铁矿不会顺带把旁边的金矿挖了"这条老行为不变。
function canStartChain(ctx, block) {
  var type = block.getType();
  var name = type.name();

  if (name === "AIR" || name === "CAVE_AIR" || name === "VOID_AIR") {
    return false;
  }
  if (name === "WATER" || name === "LAVA") {
    return false;
  }

  var specs = blockWhitelist(ctx);

  if (specs.length > 0) {
    // CE 判定要碰第三方类，只在真的配了白名单时才去问，回退路径完全不碰 CE。
    return whitelistMatches(specs, block, type, name, customBlockId(block));
  }

  // ── 白名单留空：保持升级前的行为 ──
  // 原版矿物：交给服务端注册表，不自己枚举
  if (isTagged(type, "ORES")) {
    return true;
  }
  // 远古残骸与萤石不在 ORES 标签里，单独列出
  if (name === "ANCIENT_DEBRIS" || name === "GLOWSTONE") {
    return true;
  }
  if (isTagged(type, "LOGS")) {
    return true;
  }
  // 菌柄不属于 LOGS 标签
  if (name.indexOf("_STEM") >= 0 || name === "MUSHROOM_STEM") {
    return true;
  }

  if (!CHAIN_CUSTOM) {
    return false;
  }

  // 走到这里说明它不是原版矿物/木头：只排除明确的地形方块，其余放行（旧行为）。
  return !TERRAIN[name];
}

// ── 白名单 ──────────────────────────────────────────────────────────

// 读 options.blocks 并解析成 spec 数组。
// 每次触发解析一次：白名单就几条，解析成本远小于一趟连锁；
// 不做跨触发缓存，图的是"改了 yml 重载后立刻生效，不会留着上一份定义的白名单"。
function blockWhitelist(ctx) {
  var raw;
  try {
    raw = ctx.optionList(BLOCKS_OPTION);
  } catch (e) {
    return [];
  }
  if (null == raw) {
    return [];
  }

  var specs = [];
  for (var i = 0; i < raw.length; i++) {
    var spec = parseBlockSpec(raw[i]);
    if (null != spec) {
      specs.push(spec);
    }
  }

  return specs;
}

// 纯函数：把一条白名单写法解析成 spec，空行/认不出的返回 null。
// 刻意不碰任何 Java 类型，因此可以脱离服务端单测（见 VeinMinerWhitelistTest）。
//
//   "DIAMOND_ORE"          → {kind: "material", name: "DIAMOND_ORE"}
//   "minecraft:diamond_ore"→ {kind: "material", name: "DIAMOND_ORE"}（命名空间是 minecraft，就是原版）
//   "#minecraft:ores"      → {kind: "tag", tag: "ORES"}
//   "betterend:amber_ore"  → {kind: "ce", id: "betterend:amber_ore"}
function parseBlockSpec(raw) {
  if (null == raw) {
    return null;
  }

  var text = ("" + raw).trim();
  if (text.length === 0) {
    return null;
  }

  if (text.charAt(0) === "#") {
    return parseTagSpec(text.substring(1));
  }

  var separator = text.indexOf(":");
  if (separator > 0) {
    var namespace = text.substring(0, separator).toLowerCase();
    var value = text.substring(separator + 1).toLowerCase();
    if (value.length === 0) {
      return null;
    }
    // 写 minecraft:diamond_ore 的人显然指的是原版钻石矿；
    // 当成 CE id 比对永远不可能命中，那才是真的"配了却不起作用"。
    if (namespace === "minecraft") {
      return { kind: "material", name: normalizeName(value) };
    }
    return { kind: "ce", id: namespace + ":" + value };
  }

  return { kind: "material", name: normalizeName(text) };
}

// 标签写法：#minecraft:ores / #ores / #minecraft:stone_bricks。
// 统一成 Bukkit Tag 的字段名形态（大写、下划线），命名空间丢掉——脚本侧只用原版标签。
function parseTagSpec(body) {
  var tag = ("" + body).trim().toLowerCase();
  var separator = tag.lastIndexOf(":");
  if (separator >= 0) {
    tag = tag.substring(separator + 1);
  }
  if (tag.length === 0) {
    return null;
  }

  return { kind: "tag", tag: normalizeName(tag) };
}

// 统一成 Bukkit 的命名形态（大写 + 下划线）。这里手写字符过滤而不上正则：
// 脚本运行期不依赖 Rhino 的正则实现，少一个"只有这条路径会用到"的组件。
function normalizeName(raw) {
  var text = ("" + raw).trim().toUpperCase();
  var result = "";

  for (var i = 0; i < text.length; i++) {
    var ch = text.charAt(i);
    var ok = (ch >= "A" && ch <= "Z") || (ch >= "0" && ch <= "9") || ch === "_";
    result += ok ? ch : "_";
  }

  return result;
}

// 纯函数：一条 spec 是否命中这个方块，不做任何 Java/服务端调用——
// 标签是否命中由调用方先算好传进来（tagMatched）。
//
// CE 优先，而且要在两个方向上都成立：
//   * CE 白名单项只认 CE id，不会因为载体材质恰好相同就放过一个原版方块；
//   * 已经是 CE 自定义方块的，原版名/标签一律不算命中——它的载体材质只是实现细节，
//     按材质放行会把"自定义矿"和其他用同一载体的方块混成一种。
function judgeSpec(spec, name, ceId, tagMatched) {
  if (null == spec) {
    return false;
  }

  if (spec.kind === "ce") {
    return null != ceId && spec.id === ("" + ceId).toLowerCase();
  }

  if (null != ceId) {
    return false;
  }

  if (spec.kind === "tag") {
    return !!tagMatched;
  }

  return spec.kind === "material" && spec.name === name;
}

// 任一 spec 命中即可连锁。
function whitelistMatches(specs, block, type, name, ceId) {
  for (var i = 0; i < specs.length; i++) {
    var spec = specs[i];
    var tagMatched = spec.kind === "tag" ? isTagged(type, spec.tag) : false;

    if (judgeSpec(spec, name, ceId, tagMatched)) {
      return true;
    }
  }

  return false;
}

// 读原版方块标签。Tag 的静态字段要等服务端注册表就绪才有值，
// 拿不到时返回 false，让调用方退回"不是这个标签"而不是抛错中断整次连锁。
//
// 先按 Tag 的静态字段找（ORES / LOGS / IRON_ORES …，覆盖绝大多数常用标签）；
// 找不到再退回注册表查询，让 #minecraft:stone_ore_replaceables 这类没进 Tag 枚举的
// 标签也能生效——否则服主写了一个正确的标签却毫无反应，很难查。
function isTagged(material, tagName) {
  try {
    var tag = org.bukkit.Tag[tagName];
    if (tag && tag.isTagged(material)) {
      return true;
    }
    if (tag) {
      return false;
    }
  } catch (e) {
    // 落到下面的注册表查询
  }

  try {
    var key = org.bukkit.NamespacedKey.fromString("minecraft:" + ("" + tagName).toLowerCase());
    if (null == key) {
      return false;
    }
    var registryTag = org.bukkit.Bukkit.getTag("blocks", key, org.bukkit.Material);
    return !!registryTag && registryTag.isTagged(material);
  } catch (e) {
    return false;
  }
}

// ── CraftEngine ─────────────────────────────────────────────────────// 只在装了解析成功一次并缓存。未装时缓存成 null，避免每挖一次都让 Rhino
// 去解析一个不存在的包；这种情况下 CE 是在本插件之后才启用的，
// 重载脚本（/jse reload）会重建运行态，届时重新解析。
var CE_TRIED = false;
var CE_API = null;

function craftEngine() {
  if (CE_TRIED) {
    return CE_API;
  }
  CE_TRIED = true;

  try {
    var candidate = Packages.net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;

    // 类找不到时 Rhino 交回的是一层"包"对象而不是类，用方法存在性区分，
    // 否则会拿到一个真值却调不动的对象。
    if ("function" === typeof candidate.isCustomBlock
        && "function" === typeof candidate.getCustomBlockState) {
      CE_API = candidate;
    }
  } catch (e) {
    // 没装 CE、沙箱没放行这个包、或 API 版本对不上：一律当成"没有 CE"。
    CE_API = null;
  }

  return CE_API;
}

// 方块的 CE id（形如 betterend:amber_ore）；不是自定义方块、或拿不到时返回 null。
// 任何一步失败都返回 null 而不抛错：连锁采集不能因为一个可选依赖就把整条连锁中断。
function customBlockId(block) {
  var api = craftEngine();
  if (null == api) {
    return null;
  }

  try {
    if (!api.isCustomBlock(block)) {
      return null;
    }

    var state = api.getCustomBlockState(block);
    if (null == state) {
      return null;
    }

    var wrapper = state.customBlockState();
    if (null == wrapper) {
      return null;
    }

    var key = wrapper.ownerId();
    if (null == key) {
      return null;
    }

    // Key#toString() 就是 "namespace:value"。
    return ("" + key).toLowerCase();
  } catch (e) {
    return null;
  }
}

// 只发给触发玩家的粒子。Player#spawnParticle 是单播，其他玩家收不到这个包，
// 因此不需要任何额外的可见性控制。
function highlight(player, block) {
  if (!HIGHLIGHT) {
    return;
  }

  try {
    var particle = org.bukkit.Particle[HIGHLIGHT_PARTICLE];
    if (!particle) {
      return;
    }

    var center = block.getLocation().add(0.5, 0.5, 0.5);
    player.spawnParticle(
        particle,
        center,
        HIGHLIGHT_COUNT,
        HIGHLIGHT_SPREAD,
        HIGHLIGHT_SPREAD,
        HIGHLIGHT_SPREAD,
        0.0
    );
  } catch (e) {
    // 粒子类型在当前服务端不存在、或玩家已离线：静默跳过，不影响破坏本身。
  }
}

// 广度优先：一层层向外扩，先挖近的，达到上限就停，不会沿矿脉无边界地扫。
// 返回真正被挖掉的块数——代价按它结算，所以这里不能只数"碰到了几块"。
function chain(origin, fingerprint, limit, tool, player) {
  var broken = 0;

  collect(origin, fingerprint, limit, function (block) {
    // 先刷光再挖：方块消失时粒子已经在原位了，否则玩家只会看到方块凭空不见。
    highlight(player, block);

    try {
      if (block.breakNaturally(tool)) {
        broken++;

        return true;
      }
    } catch (e) {
      // 跨区方块或已被别的插件占用：跳过这一块，继续处理队列里剩下的。
    }

    return false;
  });

  return broken;
}

// 遍历会连锁到的方块，逐个交给 consumer；consumer 返回 true 才计入上限。
// 挖掘与预览共用这一份遍历，两者的范围因此永远一致——
// 预览显示什么，挖下去就真的是什么。
function collect(origin, fingerprint, limit, consumer) {
  var queue = [[0, 0, 0]];
  var seen = {"0,0,0": true};
  var found = 0;

  while (queue.length > 0 && found < limit) {
    var current = queue.shift();

    for (var d = 0; d < DIRECTIONS.length && found < limit; d++) {
      var x = current[0] + DIRECTIONS[d][0];
      var y = current[1] + DIRECTIONS[d][1];
      var z = current[2] + DIRECTIONS[d][2];

      if (Math.abs(x) > SEARCH_RADIUS || Math.abs(y) > SEARCH_RADIUS || Math.abs(z) > SEARCH_RADIUS) {
        continue;
      }

      var key = x + "," + y + "," + z;
      if (seen[key]) {
        continue;
      }
      seen[key] = true;

      var relative;
      try {
        relative = origin.getRelative(x, y, z);
      } catch (e) {
        continue;
      }
      if (null == relative) {
        continue;
      }

      // 只吃指纹完全相同的方块：铁矿连锁不会顺带把旁边的石头挖了，
      // 自定义方块也只会连它自己那一种。
      if (identity(relative) !== fingerprint) {
        continue;
      }

      // 先入队再处理：即使这一块因为跨区失败，也不会断开它后面的通路。
      queue.push([x, y, z]);

      if (consumer(relative)) {
        found++;
      }
    }
  }
}

// ── 代价：耐久与饱食度 ──────────────────────────────────────────────

function requireSneak(ctx) {
  return optionFlag(ctx, SNEAK_OPTION, DEFAULT_REQUIRE_SNEAK);
}

function durabilityMultiplier(ctx) {
  var value = optionNumber(ctx, DURABILITY_OPTION, DEFAULT_DURABILITY_MULTIPLIER);

  // 配成 0 或负数等于"连锁白送"，那一定不是想要的结果；落回默认值。
  return value > 0 ? value : DEFAULT_DURABILITY_MULTIPLIER;
}

function hungerPerBlock(ctx) {
  var value = optionNumber(ctx, HUNGER_OPTION, DEFAULT_HUNGER_PER_BLOCK);

  return value > 0 ? value : 0.0;
}

function rawOption(ctx, key) {
  try {
    return ctx.option(key);
  } catch (e) {
    return null;
  }
}

// yml 里的数字到了脚本侧是 Java 的 Integer/Double 包装对象，不是 JS 原生 number。
// 统一走字符串再 parseFloat，不赌 Rhino 会不会自动拆箱。
function optionNumber(ctx, key, fallback) {
  var raw = rawOption(ctx, key);
  if (null == raw) {
    return fallback;
  }

  var value = parseFloat("" + raw);

  return isNaN(value) ? fallback : value;
}

function optionFlag(ctx, key, fallback) {
  var raw = rawOption(ctx, key);
  if (null == raw) {
    return fallback;
  }

  var text = ("" + raw).toLowerCase();
  if (text === "true") {
    return true;
  }
  if (text === "false") {
    return false;
  }

  return fallback;
}

// 工具还能挨几下；拿不到时返回 -1（空手、不可损耗物品、工具已被挖断）。
function durabilityLeft(item) {
  if (null == item) {
    return -1;
  }

  try {
    var max = item.getType().getMaxDurability();
    if (max <= 0) {
      return -1;
    }

    var meta = item.getItemMeta();
    if (null == meta) {
      return -1;
    }

    return max - meta.getDamage();
  } catch (e) {
    // 不是可损耗物品（没有 getDamage），或物品正在被别的线程改写。
    return -1;
  }
}

// 连锁 N 块按 multiplier 倍算总耐久，减掉已经扣掉的 charged，只补差额。
// 纯函数，可以脱离服务端单测（见 VeinMinerWhitelistTest）。
function extraWear(blocks, charged, multiplier) {
  if (blocks <= 0) {
    return 0;
  }

  var target = Math.floor(blocks * multiplier);
  var extra = target - charged;

  return extra > 0 ? extra : 0;
}

function chargeWear(player, before, blocks, multiplier) {
  if (before < 0 || blocks <= 0) {
    return;
  }

  var now = durabilityLeft(player.getInventory().getItemInMainHand());

  // 主手已经空了说明工具在连锁中途被挖断，剩余耐久按"扣光"计：
  // 再往一件不存在的物品上补扣没有意义。
  var charged = (now < 0) ? before : (before - now);
  if (charged < 0) {
    charged = 0;
  }

  var extra = extraWear(blocks, charged, multiplier);
  if (extra <= 0) {
    return;
  }

  try {
    player.damageItemStack(org.bukkit.inventory.EquipmentSlot.HAND, extra);
  } catch (e) {
    // 创造模式，或服务端没有这条 API：静默跳过。连锁本身已经生效，不值得为此抛错。
  }
}

// 饱食度按"每块 perBlock 点"扣。饱食度是整数扣不动小数，
// 所以折算成原版疲劳值累加上去，由原版自己结算成掉饱食度/掉饱和度。
function chargeHunger(player, blocks, perBlock) {
  if (blocks <= 0 || perBlock <= 0) {
    return;
  }

  try {
    player.setExhaustion(player.getExhaustion() + blocks * perBlock * EXHAUSTION_PER_FOOD_POINT);
  } catch (e) {
    // 玩家正被移除：静默跳过。
  }
}

// ── 挖前预览 ────────────────────────────────────────────────────────
// 准星指向方块时由插件调用（每 4 tick 一次，仅当目标变化），把会连锁到的方块
// 交给插件画成只对自己可见的 BlockDisplay。
//
// 这里不做任何破坏，也不消费冷却：预览是观察，不是生效。
function onHover(ctx) {
  var player;
  try {
    player = ctx.player();
  } catch (e) {
    return;
  }
  if (null == player) {
    return;
  }

  // 站着不贴描边：预览和实际能不能连锁必须是同一个条件，
  // 否则玩家会对着明明挖不动的矿脉盯半天。
  if (requireSneak(ctx) && !player.isSneaking()) {
    return;
  }

  var target;
  try {
    target = ctx.targetBlock();
  } catch (e) {
    return;
  }
  if (null == target) {
    return;
  }

  // 对着不能连锁的方块（泥土、石头、空气）时不显示任何东西。
  if (!canStartChain(ctx, target)) {
    return;
  }

  var level = Math.min(Math.max(ctx.level(), 1), MAX_BLOCKS.length);
  var limit = MAX_BLOCKS[level - 1];
  var fingerprint = identity(target);

  // 准星指的那一块自己也会被挖掉，一起标上。
  ctx.highlight(target);

  collect(target, fingerprint, limit - 1, function (block) {
    ctx.highlight(block);

    return true;
  });
}
