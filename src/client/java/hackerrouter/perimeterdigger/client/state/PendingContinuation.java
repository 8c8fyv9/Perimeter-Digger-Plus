package hackerrouter.perimeterdigger.client.state;

/** One-shot continuation; explicit user cancellation always wins. */
final class PendingContinuation {
    private Runnable task;
    void schedule(Runnable task) { this.task = task; }
    boolean pending() { return task != null; }
    void cancel() { task = null; }
    void run() { Runnable next = task; task = null; if (next != null) next.run(); }
}
