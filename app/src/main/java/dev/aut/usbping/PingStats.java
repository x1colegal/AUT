package dev.aut.usbping;

import java.util.Locale;

final class PingStats {
    private long sent;
    private long received;
    private long timedOut;
    private double last;
    private double minimum = Double.POSITIVE_INFINITY;
    private double maximum;
    private double total;
    private double jitterTotal;
    private double previous = Double.NaN;

    synchronized void sent() { sent++; }

    synchronized void timeout() { timedOut++; }

    synchronized void received(double milliseconds) {
        received++;
        last = milliseconds;
        minimum = Math.min(minimum, milliseconds);
        maximum = Math.max(maximum, milliseconds);
        total += milliseconds;
        if (!Double.isNaN(previous)) jitterTotal += Math.abs(milliseconds - previous);
        previous = milliseconds;
    }

    synchronized void reset() {
        sent = 0;
        received = 0;
        timedOut = 0;
        last = 0;
        minimum = Double.POSITIVE_INFINITY;
        maximum = 0;
        total = 0;
        jitterTotal = 0;
        previous = Double.NaN;
    }

    synchronized String format(String name) {
        double loss = sent == 0 ? 0 : timedOut * 100.0 / sent;
        if (received == 0) {
            return String.format(Locale.US,
                    "%s · waiting · sent %d · recv 0 · loss %.1f%%", name, sent, loss);
        }
        double average = total / received;
        double jitter = received < 2 ? 0 : jitterTotal / (received - 1);
        return String.format(Locale.US,
                "%s · %.2f ms · min/avg/max %.2f/%.2f/%.2f · jitter %.2f · "
                        + "sent %d · recv %d · loss %.1f%%",
                name, last, minimum, average, maximum, jitter, sent, received, loss);
    }
}
