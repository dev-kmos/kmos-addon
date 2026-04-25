package kmos.addon.enchanting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Exhaustive anvil planner for one base item plus any number of enchanted books.
 *
 * <p>The solver searches every sensible merge tree:
 * <ul>
 *     <li>book + book -> book</li>
 *     <li>item + book -> item</li>
 * </ul>
 * It does not generate trees that would lose the carrier item by placing it on the right side.
 *
 * <p>The output is intentionally Minecraft-agnostic: enchantments are identified by string ids and
 * every source is represented as a {@link Node}. That keeps the solver reusable for later UI and
 * chest-analysis layers.
 */
public final class EnchantingPlanSolver {
    private static final Map<String, CostMultiplier> MULTIPLIER = createMultipliers();
    private static final Map<String, Integer> MAX_LEVEL = createMaxLevels();
    private static final List<Set<String>> CONFLICTS = createConflicts();

    private EnchantingPlanSolver() {
    }

    public static boolean isSupportedEnchant(String enchant) {
        if (enchant == null) return false;
        return MAX_LEVEL.containsKey(normalizeEnchant(enchant));
    }

    public static int getMaxLevel(String enchant) {
        Integer max = enchant == null ? null : MAX_LEVEL.get(normalizeEnchant(enchant));
        return max == null ? 0 : max;
    }

    public static SearchResult findLowestCostPlan(Node baseItem, List<Node> books) {
        return findLowestCostPlan(baseItem, books, false);
    }

    public static SearchResult findLowestCostPlan(Node baseItem, List<Node> books, boolean rename) {
        validateBaseItem(baseItem);
        validateBooks(books);

        Memo memo = new Memo(baseItem, books, rename, 0);
        Map<StateKey, Plan> plans = memo.solveItem(memo.fullMask);
        Plan best = plans.values().stream()
            .min(Comparator.comparingInt(Plan::totalCost)
                .thenComparingInt(plan -> plan.result().anvilUseCount()))
            .orElseThrow(() -> new IllegalStateException("No valid enchanting plan found."));
        return new SearchResult(best, new ArrayList<>(plans.values()));
    }

    public static SearchResult findLowestCostPlanForTarget(Node baseItem, List<Node> books, Map<String, Integer> target) {
        return findLowestCostPlanForTarget(baseItem, books, target, false);
    }

    public static SearchResult findLowestCostPlanForTarget(Node baseItem, List<Node> books, Map<String, Integer> target, boolean rename) {
        return findLowestCostPlanForTarget(baseItem, books, target, rename, 0);
    }

    public static SearchResult findLowestCostPlanForTarget(Node baseItem, List<Node> books, Map<String, Integer> target, boolean rename, long timeoutMillis) {
        validateBaseItem(baseItem);
        validateBooks(books);
        if (target == null || target.isEmpty()) throw new IllegalArgumentException("Target enchant set cannot be empty.");

        Memo memo = new Memo(baseItem, books, rename, timeoutMillis);
        Map<StateKey, Plan> plans = memo.solveItem(memo.fullMask);
        Plan best = plans.values().stream()
            .filter(plan -> matchesTarget(plan.result().enchants(), target))
            .min(Comparator.comparingInt(Plan::totalCost)
                .thenComparingInt(plan -> plan.result().anvilUseCount()))
            .orElseThrow(() -> new IllegalStateException("No valid enchanting plan matches the requested target."));
        List<Plan> matching = plans.values().stream()
            .filter(plan -> matchesTarget(plan.result().enchants(), target))
            .sorted(Comparator.comparingInt(Plan::totalCost))
            .toList();
        return new SearchResult(best, matching);
    }

    private static boolean matchesTarget(Map<String, Integer> actual, Map<String, Integer> target) {
        for (Map.Entry<String, Integer> entry : target.entrySet()) {
            if (actual.getOrDefault(normalizeEnchant(entry.getKey()), 0) < entry.getValue()) return false;
        }
        return true;
    }

    private static void validateBaseItem(Node baseItem) {
        if (baseItem == null) throw new IllegalArgumentException("Base item cannot be null.");
        if (baseItem.isBook()) throw new IllegalArgumentException("Base node must be an item, not a book.");
        validateEnchants(baseItem.enchants());
    }

    private static void validateBooks(List<Node> books) {
        if (books == null) throw new IllegalArgumentException("Book list cannot be null.");
        for (Node book : books) {
            if (book == null) throw new IllegalArgumentException("Book entry cannot be null.");
            if (!book.isBook()) throw new IllegalArgumentException("Every secondary source must be a book.");
            validateEnchants(book.enchants());
        }
    }

    private static void validateEnchants(Map<String, Integer> enchants) {
        for (Map.Entry<String, Integer> entry : enchants.entrySet()) {
            String enchant = normalizeEnchant(entry.getKey());
            Integer max = MAX_LEVEL.get(enchant);
            if (max == null) throw new IllegalArgumentException("Unknown enchantment: " + entry.getKey());
            if (entry.getValue() < 1 || entry.getValue() > max) {
                throw new IllegalArgumentException("Invalid level for " + entry.getKey() + ": " + entry.getValue());
            }
        }
    }

    private static MergeResult merge(Node left, Node right, boolean rename) {
        int totalCost = priorWork(left.anvilUseCount()) + priorWork(right.anvilUseCount());
        CarrierType carrierType = right.isBook() ? CarrierType.Book : CarrierType.Item;

        Map<String, Integer> resultEnchants = new TreeMap<>(left.enchants());
        for (Map.Entry<String, Integer> entry : right.enchants().entrySet()) {
            String enchant = entry.getKey();
            int rightLevel = entry.getValue();
            int leftLevel = resultEnchants.getOrDefault(enchant, 0);

            if (conflictsWith(resultEnchants.keySet(), enchant) && leftLevel == 0) {
                totalCost += 1;
                continue;
            }

            int resultLevel = resultingLevel(leftLevel, rightLevel, MAX_LEVEL.get(enchant));
            totalCost += resultLevel * MULTIPLIER.get(enchant).forCarrier(carrierType);
            if (resultLevel > leftLevel) resultEnchants.put(enchant, resultLevel);
        }

        if (rename) totalCost += 1;

        Node result = new Node(
            left.isBook() && right.isBook(),
            resultEnchants,
            Math.max(left.anvilUseCount(), right.anvilUseCount()) + 1,
            left.label() + " + " + right.label()
        );
        return new MergeResult(totalCost, result);
    }

    private static int priorWork(int anvilUseCount) {
        return (1 << anvilUseCount) - 1;
    }

    private static boolean conflictsWith(Collection<String> existingEnchants, String enchant) {
        Set<String> existing = new HashSet<>(existingEnchants);
        for (Set<String> group : CONFLICTS) {
            if (!group.contains(enchant)) continue;
            Set<String> overlap = new HashSet<>(group);
            overlap.retainAll(existing);
            if (!overlap.isEmpty() && (overlap.size() > 1 || !overlap.contains(enchant))) return true;
        }
        return false;
    }

    private static int resultingLevel(int leftLevel, int rightLevel, int maxLevel) {
        if (leftLevel == rightLevel) return Math.min(leftLevel + 1, maxLevel);
        return Math.max(leftLevel, rightLevel);
    }

    private static String normalizeEnchant(String enchant) {
        return enchant.toLowerCase(Locale.ROOT).trim();
    }

    private static Map<String, CostMultiplier> createMultipliers() {
        Map<String, CostMultiplier> out = new HashMap<>();
        out.put("sharpness", new CostMultiplier(1, 1));
        out.put("smite", new CostMultiplier(2, 1));
        out.put("bane_of_arthropods", new CostMultiplier(2, 1));
        out.put("knockback", new CostMultiplier(2, 1));
        out.put("fire_aspect", new CostMultiplier(4, 2));
        out.put("looting", new CostMultiplier(4, 2));
        out.put("sweeping_edge", new CostMultiplier(4, 2));
        out.put("efficiency", new CostMultiplier(1, 1));
        out.put("unbreaking", new CostMultiplier(2, 1));
        out.put("fortune", new CostMultiplier(4, 2));
        out.put("silk_touch", new CostMultiplier(8, 4));
        out.put("mending", new CostMultiplier(4, 2));
        out.put("protection", new CostMultiplier(1, 1));
        out.put("fire_protection", new CostMultiplier(2, 1));
        out.put("blast_protection", new CostMultiplier(4, 2));
        out.put("projectile_protection", new CostMultiplier(2, 1));
        out.put("feather_falling", new CostMultiplier(2, 1));
        out.put("respiration", new CostMultiplier(4, 2));
        out.put("aqua_affinity", new CostMultiplier(4, 2));
        out.put("depth_strider", new CostMultiplier(4, 2));
        out.put("frost_walker", new CostMultiplier(4, 2));
        out.put("luck_of_the_sea", new CostMultiplier(4, 2));
        out.put("lure", new CostMultiplier(4, 2));
        out.put("thorns", new CostMultiplier(8, 4));
        out.put("soul_speed", new CostMultiplier(8, 4));
        out.put("swift_sneak", new CostMultiplier(8, 4));
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, Integer> createMaxLevels() {
        Map<String, Integer> out = new HashMap<>();
        out.put("sharpness", 5);
        out.put("smite", 5);
        out.put("bane_of_arthropods", 5);
        out.put("knockback", 2);
        out.put("fire_aspect", 2);
        out.put("looting", 3);
        out.put("sweeping_edge", 3);
        out.put("efficiency", 5);
        out.put("unbreaking", 3);
        out.put("fortune", 3);
        out.put("silk_touch", 1);
        out.put("mending", 1);
        out.put("protection", 4);
        out.put("fire_protection", 4);
        out.put("blast_protection", 4);
        out.put("projectile_protection", 4);
        out.put("feather_falling", 4);
        out.put("respiration", 3);
        out.put("aqua_affinity", 1);
        out.put("depth_strider", 3);
        out.put("frost_walker", 2);
        out.put("luck_of_the_sea", 3);
        out.put("lure", 3);
        out.put("thorns", 3);
        out.put("soul_speed", 3);
        out.put("swift_sneak", 3);
        return Collections.unmodifiableMap(out);
    }

    private static List<Set<String>> createConflicts() {
        List<Set<String>> out = new ArrayList<>();
        out.add(Set.of("sharpness", "smite"));
        out.add(Set.of("sharpness", "bane_of_arthropods"));
        out.add(Set.of("smite", "bane_of_arthropods"));
        out.add(Set.of("fortune", "silk_touch"));
        out.add(Set.of("depth_strider", "frost_walker"));
        out.add(Set.of("protection", "fire_protection"));
        out.add(Set.of("protection", "blast_protection"));
        out.add(Set.of("protection", "projectile_protection"));
        out.add(Set.of("fire_protection", "blast_protection"));
        out.add(Set.of("fire_protection", "projectile_protection"));
        out.add(Set.of("blast_protection", "projectile_protection"));
        return Collections.unmodifiableList(out);
    }

    public record Node(boolean isBook, Map<String, Integer> enchants, int anvilUseCount, String label) {
        public Node {
            Map<String, Integer> normalized = new TreeMap<>();
            for (Map.Entry<String, Integer> entry : enchants.entrySet()) {
                normalized.put(normalizeEnchant(entry.getKey()), entry.getValue());
            }
            enchants = Collections.unmodifiableMap(normalized);
            label = label == null || label.isBlank() ? (isBook ? "book" : "item") : label;
        }

        public static Node item(String label, Map<String, Integer> enchants, int anvilUseCount) {
            return new Node(false, enchants, anvilUseCount, label);
        }

        public static Node book(String label, Map<String, Integer> enchants, int anvilUseCount) {
            return new Node(true, enchants, anvilUseCount, label);
        }
    }

    public record Plan(Node result, int totalCost, List<MergeStep> steps) {
    }

    public record MergeStep(Node left, Node right, Node result, int mergeCost) {
        public String summary() {
            return left.label() + " + " + right.label() + " -> " + result.label() + " (cost " + mergeCost + ")";
        }
    }

    public record SearchResult(Plan bestPlan, List<Plan> candidatePlans) {
    }

    private record MergeResult(int cost, Node result) {
    }

    private enum CarrierType {
        Item,
        Book
    }

    private record CostMultiplier(int item, int book) {
        private int forCarrier(CarrierType type) {
            return type == CarrierType.Book ? book : item;
        }
    }

    private record StateKey(boolean isBook, int anvilUseCount, Map<String, Integer> enchants) {
        private static StateKey from(Node node) {
            return new StateKey(node.isBook(), node.anvilUseCount(), new TreeMap<>(node.enchants()));
        }
    }

    private static final class Memo {
        private final Node baseItem;
        private final List<Node> books;
        private final boolean rename;
        private final int fullMask;
        private final long deadlineNanos;
        private final Map<Integer, Map<StateKey, Plan>> bookMemo = new HashMap<>();
        private final Map<Integer, Map<StateKey, Plan>> itemMemo = new HashMap<>();

        private Memo(Node baseItem, List<Node> books, boolean rename, long timeoutMillis) {
            this.baseItem = baseItem;
            this.books = List.copyOf(books);
            this.rename = rename;
            this.fullMask = (1 << books.size()) - 1;
            this.deadlineNanos = timeoutMillis > 0 ? System.nanoTime() + timeoutMillis * 1_000_000L : Long.MAX_VALUE;
        }

        private void checkBudget() {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Enchanting planner was interrupted.");
            if (System.nanoTime() > deadlineNanos) throw new IllegalStateException("Enchanting planner timed out.");
        }

        private Map<StateKey, Plan> solveBook(int mask) {
            checkBudget();
            Map<StateKey, Plan> cached = bookMemo.get(mask);
            if (cached != null) return cached;
            Map<StateKey, Plan> computed = computeBook(mask);
            bookMemo.put(mask, computed);
            return computed;
        }

        private Map<StateKey, Plan> solveItem(int mask) {
            checkBudget();
            Map<StateKey, Plan> cached = itemMemo.get(mask);
            if (cached != null) return cached;
            Map<StateKey, Plan> computed = computeItem(mask);
            itemMemo.put(mask, computed);
            return computed;
        }

        private Map<StateKey, Plan> computeBook(int mask) {
            checkBudget();
            Map<StateKey, Plan> best = new LinkedHashMap<>();

            if (Integer.bitCount(mask) == 1) {
                int index = Integer.numberOfTrailingZeros(mask);
                Node node = books.get(index);
                best.put(StateKey.from(node), new Plan(node, 0, List.of()));
                return best;
            }

            for (int leftMask = properSubmasks(mask); leftMask > 0; leftMask = nextSubmask(leftMask, mask)) {
                checkBudget();
                int rightMask = mask ^ leftMask;
                if (rightMask == 0) continue;
                if (leftMask > rightMask) continue;

                Map<StateKey, Plan> leftPlans = solveBook(leftMask);
                Map<StateKey, Plan> rightPlans = solveBook(rightMask);
                for (Plan left : leftPlans.values()) {
                    for (Plan right : rightPlans.values()) {
                        MergeResult merge = merge(left.result(), right.result(), false);
                        if (!merge.result().isBook()) continue;
                        Plan candidate = combinePlans(left, right, merge);
                        keepBest(best, candidate);
                    }
                }
            }

            return best;
        }

        private Map<StateKey, Plan> computeItem(int mask) {
            checkBudget();
            Map<StateKey, Plan> best = new LinkedHashMap<>();

            if (mask == 0) {
                best.put(StateKey.from(baseItem), new Plan(baseItem, 0, List.of()));
                return best;
            }

            for (int bookMask = mask; bookMask > 0; bookMask = (bookMask - 1) & mask) {
                checkBudget();
                int priorMask = mask ^ bookMask;
                Map<StateKey, Plan> priorPlans = solveItem(priorMask);
                Map<StateKey, Plan> bookPlans = solveBook(bookMask);
                for (Plan prior : priorPlans.values()) {
                    for (Plan bookPlan : bookPlans.values()) {
                        MergeResult merge = merge(prior.result(), bookPlan.result(), rename && mask == fullMask);
                        if (merge.result().isBook()) continue;
                        Plan candidate = combinePlans(prior, bookPlan, merge);
                        keepBest(best, candidate);
                    }
                }
            }

            return best;
        }

        private Plan combinePlans(Plan left, Plan right, MergeResult merge) {
            List<MergeStep> steps = new ArrayList<>(left.steps());
            steps.addAll(right.steps());
            steps.add(new MergeStep(left.result(), right.result(), merge.result(), merge.cost()));
            return new Plan(merge.result(), left.totalCost() + right.totalCost() + merge.cost(), Collections.unmodifiableList(steps));
        }

        private void keepBest(Map<StateKey, Plan> best, Plan candidate) {
            StateKey key = StateKey.from(candidate.result());
            Plan existing = best.get(key);
            if (existing == null || candidate.totalCost() < existing.totalCost()) {
                best.put(key, candidate);
            }
        }

        private int properSubmasks(int mask) {
            return (mask - 1) & mask;
        }

        private int nextSubmask(int current, int mask) {
            return (current - 1) & mask;
        }
    }
}
