# FilterBox-Enhanced

通知滤盒（`com.catchingnow.np`）的 LSPosed 增强模块：**列表内容刷新时不再整行闪烁**。

纯 Java 编写，基于 **libxposed API 102**，无界面、无开关。

## ✨ 功能

### 消除列表刷新闪烁

- **现象**：滤盒列表里某条通知的内容或状态持续变化（正在进行的下载 / 生成进度、计时类内容等），那一行会闪一下。
- **效果**：变化的行**在原位置静默重绘**，不再闪。
- **只处理「内容变化」这一类**：列表**新增、删除、移动**条目的动画**全部保留**，观感与原来一致。
- **不碰滤盒的逻辑**：模块只作用于绘制与动画路径，不改变列表数据、排序与过滤判定。

## ⚙️ 开关

本模块**无界面、无开关**，启用即生效。

- **作用域已静态声明**（`app/src/main/resources/META-INF/xposed/scope.list`）：在 LSPosed 中启用模块即可，**无需手动勾选作用域**。
- 支持**热重载**：更新模块后不必重启滤盒。

## ⚠️ 边界

- 只在**滤盒进程**内运行，不注入系统进程、不改动系统行为。
- 只作用于**滤盒自己的列表**；滤盒内其它组件（如 ViewPager2、Material 组件自带的列表）内部的列表不受影响。
- 不绑定滤盒版本：模块针对的是列表控件（`androidx.recyclerview.widget.RecyclerView`）自身的行为，不依赖滤盒的业务类名。

## 📌 版本

| 项 | 值 |
|---|---|
| 当前版本 | **1.0.0** (versionCode 1) |
| Xposed API | libxposed **API 102**（minApiVersion 101） |
| minSdk / targetSdk | 26 / 37 |
| 语言 / 构建 | Java 17 · Gradle |
| 目标应用 | 通知滤盒 `com.catchingnow.np` |

## 安装

1. 安装并启用 [LSPosed](https://github.com/LSPosed/LSPosed)。
2. 安装本模块 APK。
3. 在 LSPosed 中启用 **FilterBox-Enhanced**（作用域已静态声明，无需手动勾选）。
4. 打开滤盒列表即可生效；模块更新后走「热重载」，无需重启滤盒。

> 下载见 [Releases](https://github.com/Vstory/FilterBox-Enhanced/releases)。

## 📄 许可证

本项目基于 **GNU AGPL-3.0** 协议开源。详见 [LICENSE](LICENSE)。
