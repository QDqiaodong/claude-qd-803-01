package com.dyeing.mill.service.schedule;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.dyeing.mill.domain.SchedulePlan;

import jakarta.annotation.PreDestroy;

/**
 * 批量重算的异步编排：
 * - 单线程串行计算，任何时刻最多一个重算在跑，后来的修改请求合并成一次「按最新输入重算」；
 * - 计算结果落库前再次比对输入指纹，过期结果丢弃，保证最终草案与最新输入一致；
 * - 页面提交后立即拿到 RUNNING 状态并轮询，不会因为批量布车/染缸长时间失去响应；
 * - 连续修改交期或锁定段时，前端防抖 + 后端合并双保险，不产生一堆无效中间计算。
 */
@Component
public class RecomputeManager {

    /** PENDING 已请求待计算 / RUNNING 计算中 / DONE 最新结果已落草案 / STALE 结果因输入过期被丢弃 / FAILED */
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String STALE = "STALE";
    public static final String FAILED = "FAILED";

    private final ScheduleService scheduleService;
    private final Worker worker;

    private volatile Job current;
    private final AtomicLong seq = new AtomicLong(0);

    public RecomputeManager(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
        this.worker = new Worker();
        this.worker.start();
    }

    /**
     * 请求一次重算。若已有作业在跑/排队，只把「需要再跑一次」标记置位（请求合并）：
     * 当前作业跑完后会按最新输入再跑一次，中间状态不会落库。
     */
    public synchronized Job requestRecompute(String operator) {
        if (current == null || isFinished(current.status)) {
            current = new Job(seq.incrementAndGet(), operator);
            current.status = PENDING;
            notifyAll();
            return current;
        }
        current.anotherRequested = true;
        current.operator = operator;
        current.requestTime = LocalDateTime.now();
        return current;
    }

    public synchronized Job status() {
        return current;
    }

    private boolean isFinished(String status) {
        return DONE.equals(status) || STALE.equals(status) || FAILED.equals(status);
    }

    @PreDestroy
    public void shutdown() {
        worker.interrupt();
    }

    /** 计算线程：循环「等待请求 → 按最新上下文计算 → 指纹校验落库 → 有合并请求则立即再跑」 */
    private class Worker extends Thread {
        Worker() {
            super("schedule-recompute");
            setDaemon(true);
        }

        @Override
        public void run() {
            while (!isInterrupted()) {
                Job job;
                synchronized (RecomputeManager.this) {
                    while (current == null || isFinished(current.status)) {
                        try {
                            RecomputeManager.this.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    job = current;
                    job.status = RUNNING;
                    job.startTime = LocalDateTime.now();
                    job.anotherRequested = false;
                }
                try {
                    // 始终在计算开始那一刻重新构建上下文，拿到的是最新输入
                    ScheduleService.ScheduleContext context = scheduleService.buildContext();
                    String latestFingerprint = context.getFingerprint();
                    job.ruleVersionCode = context.getRuleVersion().getVersionCode();

                    ScheduleService.ComputedPlan computed = scheduleService.compute(context);
                    String afterFingerprint = scheduleService.buildContext().getFingerprint();
                    if (!latestFingerprint.equals(afterFingerprint)) {
                        // 计算期间输入又变了：本次结果过期，标记并由合并请求触发下一轮
                        synchronized (RecomputeManager.this) {
                            job.status = STALE;
                            job.finishTime = LocalDateTime.now();
                            job.message = "计算期间输入发生变化，本次中间结果已丢弃";
                            if (job.anotherRequested) {
                                Job next = new Job(seq.incrementAndGet(), job.operator);
                                next.status = PENDING;
                                current = next;
                                notifyAll();
                            }
                        }
                        continue;
                    }
                    SchedulePlan draft = scheduleService.upsertDraft(computed);
                    synchronized (RecomputeManager.this) {
                        job.draftPlanId = draft.getId();
                        job.fingerprint = latestFingerprint;
                        job.finishTime = LocalDateTime.now();
                        if (job.anotherRequested) {
                            job.status = STALE;
                            job.message = "计算期间又有输入修改，已排队按最新输入重算";
                            Job next = new Job(seq.incrementAndGet(), job.operator);
                            next.status = PENDING;
                            current = next;
                            notifyAll();
                        } else {
                            job.status = DONE;
                            job.message = "已按最新输入更新工作草案";
                        }
                    }
                } catch (Exception e) {
                    synchronized (RecomputeManager.this) {
                        job.status = FAILED;
                        job.message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        job.finishTime = LocalDateTime.now();
                        notifyAll();
                    }
                }
            }
        }
    }

    /** 作业状态视图（Controller 直接返回） */
    public static class Job {
        public final long id;
        public String operator;
        public volatile String status;
        public volatile LocalDateTime requestTime;
        public volatile LocalDateTime startTime;
        public volatile LocalDateTime finishTime;
        public volatile Long draftPlanId;
        public volatile String ruleVersionCode;
        public volatile String fingerprint;
        public volatile String message;
        volatile boolean anotherRequested;

        Job(long id, String operator) {
            this.id = id;
            this.operator = operator;
            this.requestTime = LocalDateTime.now();
        }
    }
}
