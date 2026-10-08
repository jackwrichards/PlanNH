package com.gtnhplanner.power;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.gtnhplanner.power.PowerModel.Flow;

/**
 * The power picker's search (the website's power-search.ts): a query matches a source by NAME, or by anything the
 * machine TAKES or MAKES under any single setting choice - so "benzene" surfaces the Gas Turbine, the SOFCs and the
 * XL Gas Turbine, and picking one places the card with that setting already dialed in.
 *
 * <p>
 * The index is built once, lazily: every select option of every source is computed with the other settings at their
 * defaults, and each flow name it introduces remembers the choice that produced it.
 */
public final class PowerSearch {

    /**
     * One flow a query matched (PowerFlowMatch): {@code takes} for an input, false for an output. {@code name} is the
     * flow's display name, resolved through the resource map when known. {@code settingId} and {@code optionKey} are
     * the choice that produces this flow; null when the defaults already do.
     */
    public record Match(boolean takes, String name, @Nullable String settingId, @Nullable String optionKey) {

        /** The website's word for the side: "takes" or "makes". */
        public String direction() {
            return side(takes);
        }
    }

    /** A picker hit. {@code via} is set when the query matched a flow rather than the machine itself. */
    public record Hit(PowerSource source, @Nullable Match via) {

        /**
         * The settings this hit should be placed with: the dialed choice, if any (hitPlacementSettings); empty, never
         * null, when there is nothing to set.
         */
        public Map<String, String> settings() {
            if (via != null && truthy(via.settingId()) && truthy(via.optionKey())) {
                return Map.of(via.settingId(), via.optionKey());
            }
            return Map.of();
        }
    }

    /** How a side's stencil conditions combine; ONLY reads as ALL here. */
    public enum Op {
        ANY,
        ALL,
        ONLY
    }

    /**
     * The id the recipe search's stencil uses for its "makes power" condition. Not a dataset resource: no recipe map
     * answers it, so the recipe side of the search naturally comes back empty and only generators respond.
     */
    public static final String POWER_EU_CLAUSE_ID = "gtnh-power:eu";

    /**
     * One stencil condition: a resource ({@code kind} "item" or "fluid", the website's id) the machine takes, or makes
     * when {@code takes} is false.
     */
    public record StencilClause(boolean takes, String kind, String id) {}

    /**
     * A generator answering a stencil. {@code settings} is every dialed choice the matched clauses need, merged (null
     * when none); {@code matches} has one entry per matched clause, for captions.
     */
    public record StencilHit(PowerSource source, @Nullable Map<String, String> settings, List<Match> matches) {

        public StencilHit {
            settings = settings == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
            matches = List.copyOf(matches);
        }
    }

    private static final class SourceIndex {

        final PowerSource source;
        final String nameText;
        /** lowercased flow name -> the first (most default) way to get it. */
        final Map<String, Match> flows = new LinkedHashMap<>();
        /** "direction:kind:id" (resolved resource) -> same, for exact clause hits. */
        final Map<String, Match> flowsById = new LinkedHashMap<>();
        /**
         * The full flow-id set of each single-option sweep ("settingId key"; "" is the defaults), so a permutation
         * can be checked against the whole stencil instead of guessed at.
         */
        final Map<String, Set<String>> optionFlowSets = new LinkedHashMap<>();

        SourceIndex(final PowerSource source, final String nameText) {
            this.source = source;
            this.nameText = nameText;
        }
    }

    @Nullable
    private static List<SourceIndex> indexCache;

    private static synchronized List<SourceIndex> index() {
        if (indexCache == null) indexCache = buildIndex();
        return indexCache;
    }

    private static String side(final boolean takes) {
        return takes ? "takes" : "makes";
    }

    private static boolean truthy(@Nullable final String value) {
        return value != null && !value.isEmpty();
    }

    private static String lower(final String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    private static String flowDisplayName(final String rawName) {
        final PowerResources.Ref resource = PowerResources.resolve(rawName);
        return resource != null && resource.displayName != null ? resource.displayName : rawName;
    }

    private static void recordFlows(final SourceIndex entry, final PowerModel model, @Nullable final String settingId,
        @Nullable final String optionKey) {
        final String setKey = truthy(settingId) ? settingId + " " + optionKey : "";
        final Set<String> flowSet = new HashSet<>();
        entry.optionFlowSets.put(setKey, flowSet);
        for (final Flow flow : model.inputs()) {
            record(entry, flowSet, true, flow.name(), settingId, optionKey);
        }
        for (final Flow flow : model.outputs()) {
            record(entry, flowSet, false, flow.name(), settingId, optionKey);
        }
        if (model.euPerTick() > 0) {
            flowSet.add("makes:power:eu");
            entry.flows.putIfAbsent("makes:eu", new Match(false, "EU", settingId, optionKey));
            entry.flowsById.putIfAbsent("makes:power:eu", new Match(false, "EU", settingId, optionKey));
        }
        // Parasitic draw is deliberately NOT recorded as a takes flow: nearly every recipe takes power too, so "what
        // takes EU" cannot be answered from generators alone. The search says so instead.
    }

    private static void record(final SourceIndex entry, final Set<String> flowSet, final boolean takes,
        final String rawName, @Nullable final String settingId, @Nullable final String optionKey) {
        final String name = flowDisplayName(rawName);
        entry.flows.putIfAbsent(side(takes) + ":" + lower(name), new Match(takes, name, settingId, optionKey));
        final PowerResources.Ref resource = PowerResources.resolve(rawName);
        if (resource != null) {
            final String idKey = side(takes) + ":" + resource.kind + ":" + resource.id;
            flowSet.add(idKey);
            entry.flowsById.putIfAbsent(idKey, new Match(takes, name, settingId, optionKey));
        }
    }

    private static List<SourceIndex> buildIndex() {
        final List<SourceIndex> index = new ArrayList<>();
        for (final PowerSource source : PowerRegistry.sources()) {
            // Name and tier only - the blurbs NAME fuels ("burns benzene..."), and a blurb match would swallow the
            // flow match that knows which setting to dial in.
            final SourceIndex entry = new SourceIndex(
                source,
                lower(source.name() + " " + (source.unlock() != null ? source.unlock() : "")));
            // Defaults first, so a fuel the card already burns wins over a dialed one.
            try {
                recordFlows(entry, source.compute(new SettingsReader(source, null)), null, null);
            } catch (final RuntimeException e) {
                // A source that cannot compute its defaults still lists by name.
            }
            for (final PowerSetting setting : source.settings()) {
                if (!(setting instanceof final PowerSetting.Select select)) {
                    continue;
                }
                for (final PowerSetting.Option option : select.options()) {
                    if (option.key()
                        .equals(select.defaultKey())) {
                        continue;
                    }
                    try {
                        recordFlows(
                            entry,
                            source.compute(Map.of(select.id(), option.key())),
                            select.id(),
                            option.key());
                    } catch (final RuntimeException e) {
                        // One bad option must not take the machine out of the index.
                    }
                }
            }
            index.add(entry);
        }
        return index;
    }

    /**
     * The picker's search (searchPowerSources): name hits first, then flow hits, each in registry order; a blank query
     * returns every source.
     */
    public static List<Hit> search(final String query) {
        final List<SourceIndex> index = index();
        final String trimmed = lower(query.strip());
        final List<Hit> hits = new ArrayList<>();
        if (trimmed.isEmpty()) {
            for (final SourceIndex entry : index) hits.add(new Hit(entry.source, null));
            return hits;
        }

        final List<Hit> flowHits = new ArrayList<>();
        for (final SourceIndex entry : index) {
            if (entry.nameText.contains(trimmed)) {
                hits.add(new Hit(entry.source, null));
                continue;
            }
            // The best flow match: a default-settings flow beats a dialed one, and makes beats takes when both
            // answer (players search for products).
            Match best = null;
            for (final Map.Entry<String, Match> flow : entry.flows.entrySet()) {
                final String key = flow.getKey();
                if (!key.substring(key.indexOf(':') + 1)
                    .contains(trimmed)) {
                    continue;
                }
                final Match match = flow.getValue();
                if (best == null || (best.settingId() != null && match.settingId() == null)
                    || (best.takes() && !match.takes() && (best.settingId() == null) == (match.settingId() == null))) {
                    best = match;
                }
            }
            if (best != null) {
                flowHits.add(new Hit(entry.source, best));
            }
        }
        hits.addAll(flowHits);
        return hits;
    }

    private static String clauseKey(final StencilClause clause) {
        if (POWER_EU_CLAUSE_ID.equals(clause.id())) {
            return side(clause.takes()) + ":power:eu";
        }
        return side(clause.takes()) + ":" + clause.kind() + ":" + clause.id();
    }

    private static List<StencilClause> clausesOn(final List<StencilClause> clauses, final boolean takes) {
        final List<StencilClause> side = new ArrayList<>();
        for (final StencilClause clause : clauses) {
            if (clause.takes() == takes) side.add(clause);
        }
        return side;
    }

    /**
     * The recipe search's view of the generators (searchPowerSourcesForStencil): a source answers the stencil when its
     * flows - under ANY single setting choice - satisfy each side's conditions under that side's op (only reads as
     * all; a generator's housekeeping flows are not what "nothing else" is policing). The dialed choices merge into
     * the settings the card should be placed with; two conditions that need the same knob at different positions
     * cannot both be true, so that source drops out. A name query narrows by machine name, exactly as it narrows the
     * recipes.
     */
    public static List<StencilHit> searchForStencil(final List<StencilClause> clauses, final Op takesOp,
        final Op makesOp, final String query) {
        final List<SourceIndex> index = index();
        final String trimmed = lower(query.strip());

        if (clauses.isEmpty()) {
            // No conditions: only a typed name (or the power keyword) brings generators into the recipe search.
            final List<StencilHit> hits = new ArrayList<>();
            if (trimmed.isEmpty()) {
                return hits;
            }
            final boolean wantsPower = trimmed.length() >= 2
                && ("power".startsWith(trimmed) || "energy".startsWith(trimmed));
            for (final SourceIndex entry : index) {
                if (entry.nameText.contains(trimmed)) {
                    hits.add(new StencilHit(entry.source, null, List.of()));
                } else if ((wantsPower || trimmed.equals("eu")) && entry.flowsById.containsKey("makes:power:eu")) {
                    hits.add(new StencilHit(entry.source, null, List.of(entry.flowsById.get("makes:power:eu"))));
                }
            }
            return hits;
        }

        final List<StencilHit> hits = new ArrayList<>();
        for (final SourceIndex entry : index) {
            if (!trimmed.isEmpty() && !entry.nameText.contains(trimmed)) {
                continue;
            }
            final List<Match> matches = new ArrayList<>();
            final Map<String, String> settings = new LinkedHashMap<>();
            boolean rejected = false;
            boolean matchedAny = false;
            for (final boolean takes : new boolean[] { true, false }) {
                final List<StencilClause> side = clausesOn(clauses, takes);
                if (side.isEmpty()) {
                    continue;
                }
                final List<Match> sideMatches = new ArrayList<>();
                for (final StencilClause clause : side) sideMatches.add(entry.flowsById.get(clauseKey(clause)));
                if ((takes ? takesOp : makesOp) == Op.ANY) {
                    // One condition is enough: take the best non-conflicting match, an undialed flow before a dialed
                    // one.
                    final List<Match> candidates = new ArrayList<>();
                    for (final Match match : sideMatches) {
                        if (match != null) candidates.add(match);
                    }
                    candidates.sort(Comparator.comparingInt(match -> truthy(match.settingId()) ? 1 : 0));
                    boolean picked = false;
                    for (final Match match : candidates) {
                        if (apply(match, settings, matches)) {
                            picked = true;
                            break;
                        }
                    }
                    if (!picked) {
                        rejected = true;
                        break;
                    }
                } else {
                    // all (and only, read as all): every condition must hold at once.
                    if (sideMatches.contains(null)) {
                        rejected = true;
                        break;
                    }
                    for (final Match match : sideMatches) {
                        if (!apply(match, settings, matches)) {
                            rejected = true;
                            break;
                        }
                    }
                    if (rejected) {
                        break;
                    }
                }
                matchedAny = true;
            }
            if (rejected || !matchedAny) {
                continue;
            }
            hits.add(new StencilHit(entry.source, settings.isEmpty() ? null : settings, matches));
        }
        // Ready-as-is machines first: a card that needs no dialing is the closer fit.
        hits.sort(Comparator.comparingInt(hit -> hit.settings() != null ? 1 : 0));

        // PERMUTATIONS: a machine whose fuel knob the search did not pin is one recipe per fuel (a Large Steel Boiler
        // making steam burns any of its fuels; showing only the first implies it needs that one). Each such hit
        // expands into one card per option of its fuel-like select, NEI-style. The pure makes-power catalog query
        // stays one card per machine.
        boolean asksAboutResources = false;
        for (final StencilClause clause : clauses) {
            if (!POWER_EU_CLAUSE_ID.equals(clause.id())) asksAboutResources = true;
        }
        if (!asksAboutResources) {
            return hits;
        }
        final List<StencilHit> expanded = new ArrayList<>();
        for (final StencilHit hit : hits) {
            SourceIndex entry = null;
            for (final SourceIndex candidate : index) {
                if (candidate.source == hit.source()) {
                    entry = candidate;
                    break;
                }
            }
            final PowerSetting.Select fuelSelect = entry != null ? fuelSelectFor(entry) : null;
            if (entry == null || fuelSelect == null) {
                expanded.add(hit);
                continue;
            }
            // Every option of the fuel knob that satisfies the WHOLE stencil gets its own card. Benzene pinned by a
            // clause keeps exactly the benzene option; steam asked of a boiler keeps them all.
            boolean any = false;
            for (final PowerSetting.Option option : fuelSelect.options()) {
                if (option.key()
                    .equals("None")) {
                    continue;
                }
                final boolean isDefault = option.key()
                    .equals(fuelSelect.defaultKey());
                final Set<String> flowSet = entry.optionFlowSets
                    .get(isDefault ? "" : fuelSelect.id() + " " + option.key());
                if (flowSet == null || !sideSatisfied(clauses, flowSet, true, takesOp)
                    || !sideSatisfied(clauses, flowSet, false, makesOp)) {
                    continue;
                }
                any = true;
                final Map<String, String> settings;
                if (isDefault) {
                    settings = hit.settings();
                } else {
                    settings = new LinkedHashMap<>();
                    if (hit.settings() != null) settings.putAll(hit.settings());
                    settings.put(fuelSelect.id(), option.key());
                }
                expanded.add(new StencilHit(hit.source(), settings, hit.matches()));
            }
            if (!any) {
                expanded.add(hit);
            }
        }
        return expanded;
    }

    /** Dials a match's choice into the merged settings; false when a standing choice of the same knob differs. */
    private static boolean apply(final Match match, final Map<String, String> settings, final List<Match> matches) {
        if (truthy(match.settingId()) && truthy(match.optionKey())) {
            final String standing = settings.get(match.settingId());
            if (standing != null && !standing.equals(match.optionKey())) {
                return false;
            }
            settings.put(match.settingId(), match.optionKey());
        }
        matches.add(match);
        return true;
    }

    /** Which side ops each side needs, restated over a plain flow set. */
    private static boolean sideSatisfied(final List<StencilClause> clauses, final Set<String> flowSet,
        final boolean takes, final Op op) {
        final List<StencilClause> side = clausesOn(clauses, takes);
        if (side.isEmpty()) {
            return true;
        }
        int matched = 0;
        for (final StencilClause clause : side) {
            if (flowSet.contains(clauseKey(clause))) matched++;
        }
        return matched >= (op == Op.ANY ? 1 : side.size());
    }

    /**
     * The select that decides WHAT the machine eats - the one whose options each introduced their own takes-flow in
     * the index. Rotors, sizes and boosts scale numbers without renaming an ingredient, so they never qualify.
     */
    @Nullable
    private static PowerSetting.Select fuelSelectFor(final SourceIndex entry) {
        PowerSetting.Select best = null;
        int bestCount = 1;
        for (final PowerSetting setting : entry.source.settings()) {
            if (!(setting instanceof final PowerSetting.Select select)) {
                continue;
            }
            int count = 0;
            for (final Match match : entry.flows.values()) {
                if (select.id()
                    .equals(match.settingId()) && match.takes()) {
                    count += 1;
                }
            }
            if (count > bestCount) {
                best = select;
                bestCount = count;
            }
        }
        return best;
    }

    private PowerSearch() {}
}
