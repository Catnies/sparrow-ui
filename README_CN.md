<h1 align="center">Sparrow UI</h1>

<p align="center">
  面向 Paper 与 Folia 的响应式 UI 框架
</p>

<p align="center">
  <a href="https://github.com/Catnies/sparrow-ui/stargazers"><img alt="GitHub Stars" src="https://img.shields.io/github/stars/Catnies/sparrow-ui?color=ffb02e"></a>
  <a href="https://github.com/Catnies/sparrow-ui/issues"><img alt="GitHub Issues" src="https://img.shields.io/github/issues/Catnies/sparrow-ui"></a>
  <img alt="Version" src="https://img.shields.io/badge/version-beta.21-7c5cff">
  <img alt="Java" src="https://img.shields.io/badge/Java-21-ef6c00">
  <img alt="Platform" src="https://img.shields.io/badge/Paper%20%7C%20Folia-supported-2f80ed">
  <img alt="Minecraft 1.21.8 - 26.2" src="https://img.shields.io/badge/Minecraft-1.21.8--26.2-3c8527">
</p>

<p align="center">
  <a href="./README.md">English</a> · <strong>简体中文</strong>
</p>

Sparrow UI 是面向 Paper 与 Folia 的响应式库存 UI 库。它将菜单状态、库存交互、渲染与会话管理放进一套可复用的组件模型，让同一组 Item、Pane 和 Inventory 能够安全地服务多名玩家。

> [!IMPORTANT]
> Sparrow UI 目前仍处于 Beta 阶段，公开 API 在正式版之前可能继续调整。

## 主要特性

- **全面的原生菜单 API。** 支持箱子、铁砧、酿造台、制图台、合成器、工作台、发射器、投掷器、附魔台、三种熔炉、砂轮、漏斗、商人交易、锻造台与切石机。每种菜单都有独立的 Window API，可以控制进度、按钮、配方书、交易和地图预览等原生功能。

- **菜单物品异步渲染。** Item 支持同步、异步和懒加载三种模式，覆盖即时菜单、数据库来源菜单和只需准备一次的公共资源。异步任务执行期间可以显示占位物品，过期结果不会覆盖新内容。

- **可被复用和多个观察者访问的 UI 组件。** 同一个 Item、Pane 或 Inventory 可以同时用于多扇 Window，并展示给多名玩家，无需重复构建公共组件。组件变化后，所有观察者都会收到通知，每扇 Window 只刷新受影响的槽位。

- **虚拟容器与映射容器。** `VirtualInventory` 可以像普通箱子一样接受放入、取出、拖拽、Shift 转移和数字键交换，并支持槽位规则、堆叠上限与持久化。`ReferencingInventory` 可以把玩家背包、Bukkit 容器或自定义存储接入菜单，共享容器也支持多名玩家并发操作。

- **容器交互事务系统。** 一次点击会被整理成完整事务，包含相关 Inventory、槽位变化、光标、副手和掉落物。系统兼容 Bukkit 的 `InventoryClickEvent` 与 `InventoryDragEvent`，并提供可以查看、修改或取消整笔操作的 Sparrow 事件；事件取消或并发冲突时不会写入部分结果。

- **多 Window 菜单会话。** `WindowSession` 提供栈、保留栈和树三种结构，统一处理前进、后退、ESC 返回、异步构建下一扇 Window 和会话结束。重新打开被保留的 Window 时，原有页码、滚动位置和菜单数据仍然存在。

- **分层视觉与动画系统。** Inventory、Pane、Window、光标和标题拥有各自的视觉层，画面变化不会修改真实库存内容。设置在 Inventory 或 Pane 上的效果会被所有观察者看到，设置在 Window 上则只影响当前玩家，多个动画也可以叠加播放。

- **弱订阅的 Signal 响应式系统。** Signal 支持映射、组合、异步装载、轮询、debounce、throttle，以及 List、Set、Map 和按玩家分区的状态。Item 与 Pane 可以直接声明依赖，没有观察者时派生链和轮询任务会自动停止并等待回收。

- **基于 Signal 的翻页系统。** Page 将当前内容、页码、总页数和实际条目数公开为 Signal，支持动态列表、按页分区数据和数据库异步加载，并提供缓存、刷新与预取。同一套模型也延伸到了 Scroll 和 Tab。

- **面向 Folia 的线程安全设计。** Window 的生命周期与协议操作会在玩家实体线程中串行执行，共享组件和虚拟容器允许来自不同玩家线程的并发访问。事务系统通过固定锁序和版本校验避免死锁与过期写入，同时保留 Bukkit 对实体与区域线程的原有约束。

## 兼容性

Sparrow UI 使用同一个构件支持 Paper 与 Folia `1.21.8` ~ `26.2`，无需按 Minecraft 版本分别引入依赖。

| Minecraft 版本 | Paper | Folia |
| :---: | :---: | :---: |
| `1.21.8` ~ `26.2` | ✅ | ✅ |

> [!NOTE]
> Sparrow UI 以 Java 21 编译。实际运行时请使用对应 Paper 或 Folia 版本要求的 Java 环境。

## Gradle

Sparrow UI 作为库，需要随你的插件一起打入最终构件，服务端不用另外安装一份。

```kotlin
repositories {
    maven("https://repo.momirealms.net/snapshots")
}

dependencies {
    implementation("net.momirealms:sparrow-ui:beta.21")
}
```

---

## 示例展示

<p align="center">
  <a href="./assets/readme/live-search.gif"><img src="./assets/readme/live-search.gif" width="420" alt="实时搜索"></a>
  <a href="./assets/readme/cartography-gallery.gif"><img src="./assets/readme/cartography-gallery.gif" width="420" alt="制图台画廊"></a>
</p>

<p align="center">
  <a href="./assets/readme/skill-tree.gif"><img src="./assets/readme/skill-tree.gif" width="420" alt="技能树"></a>
  <a href="./assets/readme/custom-frame-animation.gif"><img src="./assets/readme/custom-frame-animation.gif" width="420" alt="自定义帧动画"></a>
</p>

<p align="center">
  <a href="./assets/readme/stone-appraisal.gif"><img src="./assets/readme/stone-appraisal.gif" width="420" alt="石头鉴定"></a>
</p>

## 后记

Sparrow UI 最初借鉴了 [InvUI](https://github.com/NichtStudioCode/InvUI) 对 Window、布局和 VirtualInventory 的划分，随后按自己的目标实现了 Signal、事务、视觉层和会话系统。[CraftEngine](https://github.com/Xiao-MoMi/craft-engine) 的跨版本工程与协议处理也给了这个项目不少参考。
