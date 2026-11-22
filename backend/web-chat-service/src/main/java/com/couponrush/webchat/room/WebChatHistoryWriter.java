package com.couponrush.webchat.room;

import com.couponrush.webchat.config.WebChatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 방에 보낸 메시지를 모아서 따로 저장한다 (D6). <b>저장 때문에 대화가 느려지거나 멈추지 않게</b>:
 * <ul>
 *   <li>{@link #enqueue} 는 메모리 큐에 넣기만 한다 (DB 를 기다리지 않음, 큐가 차면 저장만 포기하고 WARN)</li>
 *   <li>전용 스레드 하나가 flush-interval 마다 batch-size 씩 묶어 INSERT — 메시지마다 왕복하지 않음</li>
 *   <li>DB 실패: 그 묶음을 들고 있다가 retry-delay(시도마다 2배) 뒤 다시. max-attempts 를 넘기면 버리고 ERROR
 *       (영원히 붙잡으면 저장할 수 없는 묶음 하나가 뒤 메시지 저장을 모두 막는다)</li>
 * </ul>
 * 브로커 하트비트 스케줄러(@Scheduled 가 쓰는 스레드)를 쓰지 않고 전용 스레드를 둔다 — DB 가 느릴 때 하트비트까지 늦어지지 않게.
 * <p>
 * 한계: 메모리 큐라 서버가 갑자기 죽으면 아직 저장 안 된 메시지(최대 flush-interval + 재시도 중인 묶음)를 잃는다.
 * 정상 종료(stop)는 남은 메시지를 한 번 저장하고 끝낸다. 채팅은 잃어도 되는 데이터로 보고 이 단순함을 택했다
 * (잃으면 안 되면 Kafka 같은 내구성 있는 큐를 거쳐 저장).
 */
@Component
public class WebChatHistoryWriter implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WebChatHistoryWriter.class);

    /** WebSocket 처리기·브로커(Integer.MAX_VALUE)보다 늦게 멈춘다 — 종료 중에 들어온 메시지까지 마지막 저장에 넣으려고. */
    private static final int PHASE = Integer.MAX_VALUE - 1000;

    private final WebChatMessageRepository repository;
    private final WebChatProperties.History properties;
    private final Clock clock;
    private final BlockingQueue<WebChatMessage> queue;

    private final AtomicLong saved = new AtomicLong();
    private final AtomicLong failedAttempts = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    // 저장 스레드만 바꾼다 (flush 는 synchronized)
    private List<WebChatMessage> retrying = List.of();
    private int attempts;
    private Instant retryAt = Instant.MIN;

    private ScheduledExecutorService executor;
    private volatile boolean running;

    public WebChatHistoryWriter(WebChatMessageRepository repository, WebChatProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties.history();
        this.clock = clock;
        this.queue = new ArrayBlockingQueue<>(this.properties.queueCapacity());
    }

    /** 저장 대기열에 넣는다. 기다리지 않고, 실패를 던지지 않는다 (대화는 이미 방에 보냈다). */
    public void enqueue(WebChatMessage message) {
        if (!queue.offer(message)) {
            long count = dropped.incrementAndGet();
            if (count == 1 || count % 1000 == 0) { // 넘칠 때 로그가 메시지마다 쏟아지지 않게
                log.warn("채팅 저장 대기열이 가득 차서 저장하지 못한 메시지 누적 {}건 (DB 상태 확인)", count);
            }
        }
    }

    /**
     * 쌓인 메시지를 저장한다 (저장 스레드가 부르고, 테스트는 직접 부른다).
     * 재시도 중인 묶음이 있으면 그것부터 — 재시도 시각 전이면 아무것도 하지 않는다(뒤 메시지가 앞지르지 않게).
     */
    synchronized void flush() {
        while (true) {
            if (!retrying.isEmpty() && clock.instant().isBefore(retryAt)) {
                return;
            }
            List<WebChatMessage> batch = retrying.isEmpty() ? drain() : retrying;
            if (batch.isEmpty()) {
                return;
            }
            try {
                repository.saveAll(batch);
            } catch (RuntimeException e) {
                onFailure(batch, e);
                return;
            }
            saved.addAndGet(batch.size());
            retrying = List.of();
            attempts = 0;
        }
    }

    private List<WebChatMessage> drain() {
        List<WebChatMessage> batch = new ArrayList<>(Math.min(queue.size(), properties.batchSize()));
        queue.drainTo(batch, properties.batchSize());
        return batch;
    }

    private void onFailure(List<WebChatMessage> batch, RuntimeException e) {
        failedAttempts.incrementAndGet();
        attempts++;
        if (attempts >= properties.maxAttempts()) {
            dropped.addAndGet(batch.size());
            log.error("채팅 메시지 {}건을 {}번 시도했지만 저장하지 못해 버립니다", batch.size(), attempts, e);
            retrying = List.of();
            attempts = 0;
            return;
        }
        Duration delay = properties.retryDelay().multipliedBy(1L << Math.min(attempts - 1, 10)); // 최대 1024배
        log.warn("채팅 메시지 {}건 저장 실패 ({}/{}회), {} 뒤 다시 시도: {}",
                batch.size(), attempts, properties.maxAttempts(), delay, e.toString());
        retrying = batch;
        retryAt = clock.instant().plus(delay);
    }

    private void flushSafely() {
        try {
            flush();
        } catch (RuntimeException e) {
            log.error("채팅 저장 스레드 오류", e); // 예외로 스케줄이 멈추지 않게
        }
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "web-chat-history-writer");
            thread.setDaemon(true);
            return thread;
        });
        long interval = properties.flushInterval().toMillis();
        executor.scheduleWithFixedDelay(this::flushSafely, interval, interval, TimeUnit.MILLISECONDS);
        running = true;
    }

    /** 남은 메시지를 한 번 더 저장하고 멈춘다 (재시도 대기는 무시하고 한 번만 시도). */
    @Override
    public void stop() {
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            retryAt = Instant.MIN;
            flushSafely();
        }
        int left = queue.size() + retrying.size();
        if (left > 0) {
            log.error("종료하면서 채팅 메시지 {}건을 저장하지 못했습니다", left);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /** 저장한 메시지 수 (누적). */
    public long savedCount() {
        return saved.get();
    }

    /** 실패한 저장 시도 수 (누적, 묶음 단위). */
    public long failedAttemptCount() {
        return failedAttempts.get();
    }

    /** 저장을 포기한 메시지 수 (대기열 넘침 + 시도 초과, 누적). */
    public long droppedCount() {
        return dropped.get();
    }
}
