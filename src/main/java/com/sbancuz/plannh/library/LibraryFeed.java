package com.sbancuz.plannh.library;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.sbancuz.plannh.PlanNH;

/**
 * The setups the library shows: one query, its pages as they arrive, and whether more are coming. Fetches run on one
 * background thread; their results wait in a queue until {@link #poll} hands them to the client thread, so the UI
 * only ever sees this from there. A newer query drops whatever an older one was still fetching.
 */
public final class LibraryFeed {

    public static final int PAGE_SIZE = 48;

    /** How long a typed search waits for the typing to stop. */
    private static final long SEARCH_SETTLE_MS = 350;

    private static final ExecutorService NET = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "PlanNH library");
        t.setDaemon(true);
        return t;
    });

    private final Queue<Runnable> arrived = new ConcurrentLinkedQueue<>();

    private CommunityApi.Query query = CommunityApi.Query.start();
    private final List<CommunityApi.Setup> setups = new ArrayList<>();
    private List<String> gameVersions = List.of();
    private int total = -1, loadedPages;
    private boolean loading;
    @Nullable
    private String error;
    /** Bumped by every new query: answers for an older one are dropped. */
    private int generation;
    private long searchDue = -1;

    public CommunityApi.Query query() {
        return query;
    }

    public List<CommunityApi.Setup> setups() {
        return setups;
    }

    public List<String> gameVersions() {
        return gameVersions;
    }

    /** The number of setups the query matches; -1 before the first page lands. */
    public int total() {
        return total;
    }

    public boolean loading() {
        return loading;
    }

    @Nullable
    public String error() {
        return error;
    }

    public boolean started() {
        return loadedPages > 0 || loading || error != null;
    }

    /** A new query: starts over from its first page. */
    public void set(final CommunityApi.Query q) {
        if (q.equals(query) && started()) return;
        query = q;
        searchDue = -1;
        restart();
    }

    /** The search text as it is typed: the list follows once the typing stops. */
    public void type(final String search) {
        if (search.equals(query.search())) {
            searchDue = -1;
            return;
        }
        query = query.withSearch(search);
        searchDue = System.currentTimeMillis() + SEARCH_SETTLE_MS;
    }

    /** Starts over with the same query (after an error, or to see what is new). */
    public void restart() {
        generation++;
        setups.clear();
        total = -1;
        loadedPages = 0;
        error = null;
        loading = false;
        fetch(1);
    }

    /** The next page, when there is one and nothing is on its way. */
    public void more() {
        if (loading || error != null || total < 0 || setups.size() >= total) return;
        fetch(loadedPages + 1);
    }

    /** Hands arrived pages to the client thread; call every tick from there. */
    public void poll() {
        if (searchDue > 0 && System.currentTimeMillis() >= searchDue) {
            searchDue = -1;
            restart();
        }
        for (Runnable r; (r = arrived.poll()) != null;) r.run();
    }

    private void fetch(final int page) {
        loading = true;
        final int gen = generation;
        final CommunityApi.Query q = query;
        NET.execute(() -> {
            try {
                final CommunityApi.Page p = CommunityApi.list(q, page, PAGE_SIZE);
                arrived.add(() -> {
                    if (gen != generation) return;
                    loading = false;
                    setups.addAll(p.setups());
                    total = p.total();
                    loadedPages = page;
                    if (!p.gameVersions()
                        .isEmpty()) gameVersions = p.gameVersions();
                });
            } catch (final Exception e) {
                PlanNH.LOG.info("Library: could not list setups", e);
                arrived.add(() -> {
                    if (gen != generation) return;
                    loading = false;
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                });
            }
        });
    }

    /**
     * Fetches a setup's plan in the background; {@code done} gets it, or {@code failed} why not, on the client thread.
     */
    public void download(final CommunityApi.Setup setup, final Consumer<CommunityApi.Download> done,
        final Consumer<String> failed) {
        NET.execute(() -> {
            try {
                final CommunityApi.Download d = CommunityApi.download(setup.id());
                arrived.add(() -> done.accept(d));
            } catch (final Exception e) {
                PlanNH.LOG.info("Library: could not download setup {}", setup.id(), e);
                arrived.add(() -> failed.accept(e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        });
    }
}
