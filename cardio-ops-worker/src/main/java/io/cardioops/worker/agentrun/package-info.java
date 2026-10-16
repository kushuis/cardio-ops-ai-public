/**
 * 共用的领取调度。心跳用自己的线程，不跟 poll 的调度线程挤在一起。
 */
package io.cardioops.worker.agentrun;
