package io.cerulean.regions;

/**
 * 区域 tick 调度器（B1 的骨架）。
 *
 * <p>两种实现共用同一套"区域 tick 体"（{@link #tickRegion} 的语义在
 * {@link RegionTickSchedulerSupport}）：</p>
 * <ul>
 *   <li>{@link SerialRegionTickScheduler}：**确定性**——主线程按区域 id 升序逐个 tick，
 *       顺序固定、可复现，用于与 C2 行为对拍；</li>
 *   <li>{@link EdfRegionTickScheduler}：用并发库的 {@code EDFSchedulerThreadPool}（与上游同一套），
 *       区域在自己的线程上 tick。</li>
 * </ul>
 *
 * <p><b>时间语义</b>（工程研究 notes/12 §2.2 点名的两条，已由 10 项单测钉住）：
 * catch-up 用 {@code tickCount = max(1, periodsAhead)}，收尾用
 * {@code completeTick}（先推进周期、再取 {@code max(tickEnd, 理想起点)}）。</p>
 */
public interface RegionTickScheduler {

    /** 启动（EDF 会起线程；串行实现为空操作）。 */
    void start();

    /** 主线程每 tick 调用一次：串行实现跑一轮；EDF 实现为空操作（区域由池线程自行 tick）。 */
    void driveMainThread();

    /** 关服/卸载世界时停止（EDF 会 halt + join）。 */
    void shutdown();

    /** 供诊断命令使用的一行摘要。 */
    String summary();

    /** 调度模式名（`serial` / `edf`）。 */
    String mode();
}
