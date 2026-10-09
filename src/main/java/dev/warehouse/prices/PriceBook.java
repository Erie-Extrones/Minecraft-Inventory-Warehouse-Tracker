package dev.warehouse.prices;

import dev.warehouse.config.ConfigIO;
import dev.warehouse.storage.JsonStore;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** All price observations for the current server, and the median-based statistics derived from them. */
public final class PriceBook {
    /** Summary for one price key and side. */
    public record Stats(double median, double min, double max, int count, long newestEpochMs, boolean sell) {}

    private static final long DEDUPE_WINDOW_MS = 10 * 60_000L;

    private final JsonStore<List<PriceObservation>> store;
    private final List<Runnable> listeners = new ArrayList<>();

    public PriceBook(JsonStore<List<PriceObservation>> store) {
        this.store = store;
    }

    public void onChange(Runnable r) {
        listeners.add(r);
    }

    public List<PriceObservation> all() {
        return store.get();
    }

    /** Add an observation; an identical price from the same shop within ten minutes only refreshes the timestamp. Returns true if new. */
    public boolean add(PriceObservation o) {
        long now = System.currentTimeMillis();
        for (PriceObservation x : store.get()) {
            if (x.sell == o.sell && x.priceKey.equals(o.priceKey) && eq(x.shop, o.shop) && Math.abs(x.unitPrice - o.unitPrice) < 0.005 && now - x.whenEpochMs < DEDUPE_WINDOW_MS) {
                x.whenEpochMs = now;
                x.quantity = Math.max(x.quantity, o.quantity);
                store.markDirty();
                return false;
            }
        }
        store.get().add(o);
        store.markDirty();
        fire();
        return true;
    }

    public List<PriceObservation> observations(String priceKey) {
        List<PriceObservation> out = new ArrayList<>();
        for (PriceObservation o : store.get()) if (priceKey.equals(o.priceKey)) out.add(o);
        out.sort((a, b) -> Long.compare(b.whenEpochMs, a.whenEpochMs));
        return out;
    }

    public int clear(String priceKey) {
        int before = store.get().size();
        store.get().removeIf(o -> priceKey.equals(o.priceKey));
        int n = before - store.get().size();
        if (n > 0) {
            store.markDirty();
            fire();
        }
        return n;
    }

    /** Median over observations of the given keys and side that are not older than {@code priceMaxAgeDays}; null if none. */
    public @Nullable Stats stats(List<String> priceKeys, boolean sell) {
        long cutoff = System.currentTimeMillis() - Math.max(1, ConfigIO.get().priceMaxAgeDays) * 86_400_000L;
        List<Double> values = new ArrayList<>();
        double min = Double.MAX_VALUE, max = 0;
        long newest = 0;
        for (PriceObservation o : store.get()) {
            if (o.sell != sell || o.whenEpochMs < cutoff || !priceKeys.contains(o.priceKey)) continue;
            values.add(o.unitPrice);
            min = Math.min(min, o.unitPrice);
            max = Math.max(max, o.unitPrice);
            newest = Math.max(newest, o.whenEpochMs);
        }
        if (values.isEmpty()) return null;
        Collections.sort(values);
        int n = values.size();
        double median = n % 2 == 1 ? values.get(n / 2) : (values.get(n / 2 - 1) + values.get(n / 2)) / 2.0;
        return new Stats(median, min, max, n, newest, sell);
    }

    private static boolean eq(@Nullable String a, @Nullable String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b == null ? "" : b);
    }

    private void fire() {
        for (Runnable r : listeners) r.run();
    }
}
