# 印染厂 · 染缸与染色配方管理

以「染缸 + 染色配方 + 工艺曲线」为核心的印染车间管理系统。

## 启动

```bash
./start.sh          # 秒起（镜像已存在）
./start.sh --build  # 首次或改代码后重建
./stop.sh / ./resume.sh / ./clean.sh / ./nuke.sh
```

| 服务 | 端口 |
| --- | --- |
| 前端（Vue 3 + Element Plus + Vite / nginx） | 8283 |
| 后端（Spring Boot 3.3.0 + Java 17） | 8383 |
| MySQL 8（库名 `dyeing_mill`） | 3583 |
| Redis 7 | 6583 |

表结构与种子数据由 `mysql/schema.sql` 在构建期导入（`ddl-auto: none`，应用不建表）。

## 七个模块

1. **染缸台账** — 缸号、机台类型（溢喷/气流/卷染）、容量、浴比、六态流转；缸位温度环阵列 + 控温面板。
2. **染色配方** — 色号、客户、布种、LAB；染料阶梯表行内编辑 owf，合计必须 100%；助剂单列，自动核算元/kg 成本。
3. **工艺曲线** — 升温/保温/降温节点序列，SVG 上拖动节点改温度与时长，实时算各段 ℃/min。
4. **布车流转** — 三列看板（待排产/在染/已完成），HTML5 原生拖拽，落入「在染」时选染缸与时段。
5. **开缸执行单** — 串联已排缸布车、最新已发布配方和匹配曲线；待确认资料指纹复核，下达时保存完整快照并同步染缸目标温度/保温时长。
6. **染料与助剂库存** — 库存 / 安全库存 / 批次 / 效期，领用登记与库存预警。
7. **染缸换色排程台** — 待排布车 × 配方色值 × 染缸容量机型 × 维护时段 × 交期的跨缸计划，详见下节。

## 换色排程台（模块 7）

- **清洗矩阵版本化**：颜色族 + 深浅档（LIGHT/MID/DEEP）+ 特殊染料标记；四类切换口径——
  同色续染 / 浅转深 / 深转浅 / 特殊染料切换，各带清洗时长与成本；颜色族对可逐条专用覆写。
  保存即生成新版本（旧版本自动归档），新方案按新口径，旧方案带 `ruleVersionId` + 输入快照可按当时版本复算并显示原解释。
- **排程引擎（确定性、可解释）**：维护窗与人工锁定段是不可移动固定块，只调整未锁定未来区段；
  在「逐缸时间轴的全部可行位」上综合逾期（急单 60 元/分钟 ≫ 最深清洗 1200 元）、空档、清洗成本选位，
  不会为少清洗把急单长期压后；只按颜色/交期简单排序会破坏容量、维护、锁定约束，引擎不做简单排序。
- **每辆布车的解释**：已排入（缸位/时段/前序颜色/清洗分类与时长成本/是否逾期）、人工锁定、
  排不进 5 天视野而延后（最早可开缸时间 + 瓶颈缸）、排除（**配方资料不完整 / 工艺曲线缺失 /
  容量不足 / 机型不适配**，逐项具体原因单列）。
- **页面**：跨缸甘特时间轴（作业/清洗/维护/锁定分色）、染缸负载条（染色/清洗/维护/空档占时）、
  清洗成本 KPI、安排/延后/排除解释表、交期急单输入、维护与锁定管理、方案留存与两方案差异、规则版本编辑。
- **多方案**：工作草案随重算覆盖；可留存多个不可变快照，唯一一个可选为「车间参考」。
  **排程台绝不改写布车状态、缸位或开缸执行单**，布车侧只新增交期/急单两个排程输入字段。
- **性能与一致性**：重算走单线程异步任务（页面立即返回、可继续操作并轮询），
  连续修改在前端 800ms 防抖、后端任务合并；落库前再比输入指纹，过期结果丢弃，最终草案必与最新输入一致。


## 后端技术标识

- Hibernate 6 `@SQLRestriction` 做全局数据过滤：软删除（`deleted = 0`）、报废布车（`status <> 'SCRAPPED'`）、布种档案（`enabled = 1`）。
- `@OneToMany + @OrderBy` 维护有序明细：配方明细按 `seqNo`、曲线节点按 `seqNo`、领用记录按 `issueTime DESC`。
- 手写 JPQL：按色号统计缸次、按客户汇总米数、染料库存预警、染缸时段冲突、机台类型分布等。
- 业务规则放 service 并抛 `BizException`：染缸时段冲突、owf 合计 100%（报错给差值）、领用不得超过库存、升温速率超布种上限。
- 开缸执行单：创建时同时核对布种/色号、已发布最新版本、匹配曲线、染缸容量和机台温度上限；待确认单按资料指纹复核，配方/曲线/排缸变化自动失效；下达用布车行级锁 + `uk_issued_cart(active_cart_id)` 保证同车仅一张生效，并在同一事务内锁定快照、更新布车/染缸和 Redis 参数。
- 异常验收开关：后端设置 `EXECUTION_FAIL_AFTER_PARAMS=true` 可在 Redis 参数写入后模拟失败，用于确认执行单/布车/染缸回滚、Redis 参数恢复。

## 前端导航

九宫格启动台：默认全屏无侧栏，右上角菜单按钮弹出 9 格导航（5 个模块 + 4 个带预设参数的快捷入口），
选定后模块名显示在顶部标题条，内容区全屏铺开。主色 `#4a148c`。

## 主要接口

```
GET    /api/vats                  染缸列表
PATCH  /api/vats/{id}/control     控温（目标温度 + 保温时长）
POST   /api/vats/{id}/tick        推进一拍
GET    /api/recipes               配方（含明细）
PUT    /api/recipes/{id}          保存配方（校验 owf 合计 100%）
GET    /api/curves                工艺曲线（含节点）
PUT    /api/curves/{id}/nodes     保存节点（校验升温速率）
GET    /api/carts/board           布车看板三列
POST   /api/carts/{id}/assign     排缸（校验染缸时段冲突）
GET    /api/execution-orders       开缸执行单列表（自动复核待确认单）
POST   /api/execution-orders/preview 开缸前核对并返回具体差异
POST   /api/execution-orders       创建待确认执行单
POST   /api/execution-orders/{id}/issue 下达（快照 + 布车/染缸/Redis 同事务）
GET    /api/inventory/alerts      库存预警
POST   /api/inventory/issue       领用（校验库存与效期）

# 换色排程台
GET    /api/schedule/state                 总览（当前规则/输入快照/方案/重算作业）
POST   /api/schedule/recompute             异步批量重算（合并连续请求）
GET    /api/schedule/recompute/status      重算作业状态轮询
GET    /api/schedule/plans                 方案列表（草案 + 留存快照）
POST   /api/schedule/plans/save            留存为不可变快照
POST   /api/schedule/plans/{id}/reference  选定车间参考方案
GET    /api/schedule/plans/diff?a=&b=      两方案差异
POST   /api/schedule/plans/{id}/replay     按方案当时规则版本+冻结输入复算
GET    /api/schedule/rules                 规则版本列表
POST   /api/schedule/rules/publish         保存清洗矩阵新版本（旧版归档）
GET/POST/DELETE /api/schedule/maintenance  维护时段增删查（与锁定冲突校验）
GET/POST/DELETE /api/schedule/locks        人工锁定段（机型/容量/维护/同缸冲突全校验）
PATCH  /api/schedule/carts/{id}/schedule-input 只改交期/急单，不动布车状态
```
