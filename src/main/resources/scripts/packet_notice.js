// 以太回声：用 PacketEvents 直接给触发者发一条 ActionBar。
//
// 这条示例存在的意义是证明两件事同时成立：
//   1) 自定义事件（PlayerToggleSneakEvent）真的被派发到了脚本；
//   2) 脚本能直接构造协议包并交给 PacketEvents——config.yml 的 scripts.allowed-packages
//      放行了 com.github.retrooper.packetevents.*，plugin.yml 的 softdepend 让这些类在本插件的
//      类加载器里真的可见。两者缺一，脚本写对了也不会生效。
//
// 为什么用 ActionBar 而不是聊天栏：ActionBar 是纯客户端显示，不写服务端聊天记录，
// 也不受 /gamerule sendCommandFeedback 影响，是「只给一个人看」最干净的载体。
//
// 为什么这里到处都写日志：这一版之前是空 catch，于是「PacketEvents 没装」「类解析不到」
// 「发包抛异常」三种完全不同的故障全都表现成游戏里看不到东西、日志一行没有，
// 只能靠猜。现在每种故障都带明确原因，首次把包交出去也写一行 info——
// 「没报错」不再等于「包已经发出去了」。
//
// 另一个刻意的措辞：「已提交」而不是「已送达」。sendPacket 只是把包交给 PacketEvents
// 的写入链路（并最终交给网络层），服务端从这里再也看不到后续；客户端有没有渲染出来
// 不在本进程的可证范围内，日志不能替客户端作证。

// 每执行线程一份的状态：作用域按线程隔离，见 ScriptEnginePool。
var notice = {
  invoked: false,
  submitted: false,
  warnedAt: 0
};

function describe(cause) {
  return null == cause ? "" : String(cause);
}

// 所有故障合计每 5 秒最多记一条（不是每类一条）：潜行来回切也不至于把日志刷满。
function warn(reason, cause) {
  var now = Date.now();

  if (now - notice.warnedAt < 5000) {
    return;
  }

  notice.warnedAt = now;

  var detail = describe(cause);
  console.warn("[packet_notice] ActionBar 未发送: " + reason + ("" == detail ? "" : "（" + detail + "）"));
}

// 提交日志用的名字：读不到就退回固定文案。它只是一条诊断字段，
// 绝不允许它的失败影响「包到底有没有交出去」这个判断。
function displayName(player) {
  try {
    var name = player.getName();

    return null == name ? null : String(name);
  } catch (e) {
    return null;
  }
}

// 提交日志：刻意做成不依赖任何 API 状态的独立步骤（不用 api.getVersion() 之类的诊断调用），
// 这样它出问题也不可能被算进「发送失败」。
function logSubmitted(player) {
  var name = displayName(player);

  console.log(
    "[packet_notice] ActionBar 单播已提交：" + (null == name ? "触发者" : name)
      + "（仅表示已交给 PacketEvents，客户端是否收到无法在服务端证实）"
  );
}

// 返回可用的 API，或 null。每一种「不可用」都单独报，混成一句等于没报。
function packetEvents() {
  var api;

  try {
    api = com.github.retrooper.packetevents.PacketEvents.getAPI();
  } catch (e) {
    // 最常见的一种：类没进本插件的类加载器（plugin.yml 缺 softdepend），
    // 或者 config.yml 没放行这个包前缀（沙箱拒绝）。
    warn("访问 com.github.retrooper.packetevents.PacketEvents 失败", e);

    return null;
  }

  if (null == api) {
    warn("PacketEvents.getAPI() 返回 null", null);

    return null;
  }

  // 先初始化再加载：两个标志位对应不同阶段，分开报才知道卡在哪一步。
  if (!api.isInitialized()) {
    warn("PacketEvents 尚未初始化", null);

    return null;
  }

  if (!api.isLoaded()) {
    warn("PacketEvents 尚未加载完成", null);

    return null;
  }

  return api;
}

function onTrigger(ctx) {
  // 文案与调试日志开关都在 yml 的 options 里（message / verbose）。
  // verbose 默认开：这两条日志是区分「事件没触发」和「发包失败」的唯一线索。
  var verbose = ctx.optionFlag("verbose", true);

  // 「事件到底有没有派发到脚本」必须能在日志里看到：没有这一行，发包失败和事件没触发
  // 在外部表现完全一样（游戏里都没变化）。
  if (verbose && !notice.invoked) {
    notice.invoked = true;
    console.log("[packet_notice] PlayerToggleSneakEvent 已派发到脚本（首次）");
  }

  var player;

  try {
    player = ctx.player();
  } catch (e) {
    warn("ctx.player() 取不到触发者", e);

    return;
  }

  if (null == player) {
    warn("事件里没有玩家", null);

    return;
  }

  var api = packetEvents();

  if (null == api) {
    return;
  }

  // 这个 try 里只放「构造包 + 交出去」：这里的 catch 必须严格等价于「包没交出去」，
  // 所以日志、读玩家名、查版本号这些诊断动作一个都不许进来——它们一旦抛异常，
  // 就会出现「包其实已经提交了，日志却报发送失败」的假故障。
  try {
    var text = net.kyori.adventure.text.Component.text(ctx.optionText("message", "以太回声 · 已听见"));
    var packet = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar(text);

    // 单播：包只写进这一名玩家的连接，其他客户端收不到。
    api.getPlayerManager().sendPacket(player, packet);
  } catch (e) {
    warn("发送 ActionBar 失败", e);

    return;
  }

  // 提交日志是发送之后的独立步骤：sendPacket 返回只表示「已交给 PacketEvents 的写入链路」，
  // 客户端是否收到不在服务端可证的范围内，所以这里只敢说「已提交」。
  if (!notice.submitted) {
    notice.submitted = true;

    // 这一行若都写不出去，异常交给派发层记录——发送 try 已经结束，它不会被误认成发送失败。
    if (verbose) {
      logSubmitted(player);
    }
  }
}
