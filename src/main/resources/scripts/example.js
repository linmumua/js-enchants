// 写法示例：没有挂到任何附魔上，复制成新脚本、再写一份 enchants/*.yml 引用它即可使用。
//
// 演示三件事：
//   1) options 取值：ctx.optionNumber / ctx.optionText，yml 里没写时用这里给的默认值；
//   2) 持久状态：ctx.addState 把计数存进玩家 PDC，按附魔 id 隔离，重启后仍在；
//   3) 延迟回调：ctx.delay 传「顶层函数名」，不传匿名函数（Rhino 函数对象不能跨线程）。
//
// 对应的 yml 片段：
//   trigger: ATTACK
//   options:
//     every: 5                 # 每命中几次触发一次提示
//     message: "连击达成"
//     echo-delay-ticks: 20     # 提示后多久再补一句

function onTrigger(ctx) {
  var every = Math.max(1, Math.floor(ctx.optionNumber("every", 5)));
  var hits = ctx.addState("hits", 1);

  if (hits % every != 0) {
    return;
  }

  ctx.actionBar(ctx.optionText("message", "连击达成") + " ×" + hits);

  // 回调参数会原样传给 echo。参数一般只放数字/字符串；ctx 是例外：它是不可变的 Java 对象，
  // 且 actionBar 总会投递到触发者自己的区域线程。回调里不要再读 ctx.event() 或 ctx.victim()，
  // 到期时它们早已过期（事件已结算、目标可能在别的区域）。
  var delayTicks = Math.max(1, Math.floor(ctx.optionNumber("echo-delay-ticks", 20)));
  ctx.delay(delayTicks, "echo", ctx, hits);
}

// 延迟回调必须是顶层具名函数：到期时脚本池按名字在执行线程自己的作用域里重查。
function echo(ctx, hits) {
  ctx.actionBar("余韵 · 第 " + hits + " 击");
}
