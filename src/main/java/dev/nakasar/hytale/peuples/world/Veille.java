package dev.nakasar.hytale.peuples.world;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import dev.nakasar.hytale.peuples.PeuplesPlugin;
import dev.nakasar.hytale.peuples.village.Village;
import dev.nakasar.hytale.peuples.village.Villages;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * Le monde qui vit seul (Kevin, 22 novembre 2026 : « un monde qui évolue seul, always on ») : sur un serveur allumé sans
 * joueur, le monde de Hytale continue de tourner et l'heure du jeu avance (serveur 0.7.7 : un monde sans joueur est
 * « au repos » mais son pas tourne toujours) ; seuls les chunks loin des joueurs sont déchargés. La veille fait donc
 * tourner la vie des villages et du monde sans joueur, en version hybride :
 * <ul>
 *   <li>tout ce qui n'est que des données tourne comme si les joueurs étaient loin (calendrier, commerce, besoins,
 *       voyageurs et caravanes virtuels, relations, générations) ;</li>
 *   <li>les blocs restent réels : un village qui a du travail (chantier qui s'ouvre, change de phase ou se finit,
 *       sentiers, recollages, projets de terrain, avant-poste) est <b>tenu</b> un court instant, ses chunks chargés et
 *       gardés chargés le temps du collage, puis relâchés. Un seul lieu tenu à la fois par monde, et un même lieu pas
 *       plus d'une fois toutes les {@value #COOLDOWN_MILLIS} ms ;</li>
 *   <li>chaque matin de jeu, le chunk du dépôt de chaque village est chargé le temps des prélèvements du jour
 *       (ateliers, besoins, livraisons en attente), puis relâché ;</li>
 *   <li>aucun PNJ n'est simulé sans joueur : villageois, bétail et caravanes réapparaissent au retour d'un joueur.</li>
 * </ul>
 * Pas de rattrapage : serveur éteint, l'heure du jeu ne passe pas et le monde est en pause.
 *
 * <p>Réglages par monde ({@code /monde veille}), gardés dans le registre du monde vivant ; sans réglage, les propriétés
 * JVM {@value #PROP_ON} et {@value #PROP_RAIDS} ({@code true} / {@code false}) donnent la valeur, sinon les deux sont
 * actives.
 */
public final class Veille {

    public static final String KEYS = WorldLife.KEYS + "veille.";
    public static final String PROP_ON = "nakasar.peuples.veille";
    public static final String PROP_RAIDS = "nakasar.peuples.veille.raids";

    private static final String KEY_ON = "veille";
    private static final String KEY_RAIDS = "veille.raids";

    /** Durée minimale d'une tenue, chunks gardés chargés. */
    static final long HOLD_MILLIS = 10_000L;
    /** Durée maximale d'une tenue (un collage qui traîne) ou d'un chargement qui ne répond pas. */
    static final long MAX_HOLD_MILLIS = 90_000L;
    /** Un même lieu n'est pas retenu plus souvent (travail qui ne peut pas se faire : matériaux absents...). */
    static final long COOLDOWN_MILLIS = 10 * 60_000L;

    private Veille() {
    }

    /** Un lieu tenu : ses chunks, chargés puis gardés jusqu'à la fin. */
    private static final class Hold {
        final String key;
        final long[] chunks;
        final long since = System.currentTimeMillis();
        final BooleanSupplier busy;
        final List<WorldChunk> kept = new ArrayList<>();
        final List<ChunkSection> keptSections = new ArrayList<>();
        boolean ready;
        /** Instant où les chunks ont fini de charger : la durée minimale de la tenue part de là. */
        long readyAt;

        Hold(String key, long[] chunks, BooleanSupplier busy) {
            this.key = key;
            this.chunks = chunks;
            this.busy = busy;
        }
    }

    private static final Map<World, Hold> HOLDS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<String, Long> LAST = Collections.synchronizedMap(new HashMap<>());
    /** Mondes dont les dépôts se chargent pour le matin. */
    private static final Set<World> MORNING = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    /** Chargement qui ne répond pas : abandonné passé ce délai (le matin se fait alors avec les dépôts déjà chargés). */
    static final long LOAD_TIMEOUT_MILLIS = 60_000L;
    /** Villages sans assez de matériaux au dépôt pour leur chantier, par jour de jeu (pas de nouvelle tenue ce jour). */
    private static final Map<String, Long> SHORT = Collections.synchronizedMap(new HashMap<>());

    // ---- Réglages ----

    /** Vrai si la vie tourne sans joueur dans ce monde. */
    public static boolean on(World world) {
        return flag(world, KEY_ON, PROP_ON);
    }

    /** Vrai si les raids peuvent se jouer sans témoin (bilan tiré par le Java, {@code Raids}). */
    public static boolean raids(World world) {
        return on(world) && flag(world, KEY_RAIDS, PROP_RAIDS);
    }

    public static void setOn(World world, boolean value) {
        WorldLife.registry(world).put(WorldLifeRegistry.Part.CALENDAR, KEY_ON, Boolean.toString(value));
        if (!value) {
            release(world);
        }
    }

    public static void setRaids(World world, boolean value) {
        WorldLife.registry(world).put(WorldLifeRegistry.Part.CALENDAR, KEY_RAIDS, Boolean.toString(value));
    }

    private static boolean flag(World world, String key, String property) {
        String v = WorldLife.registry(world).get(WorldLifeRegistry.Part.CALENDAR, key);
        if (v == null) {
            v = System.getProperty(property);
        }
        return v == null || !v.trim().equalsIgnoreCase("false");
    }

    /** Vrai si la vie du monde doit tourner : un joueur est là, ou la veille est active. */
    public static boolean runs(World world) {
        return !world.getPlayerRefs().isEmpty() || on(world);
    }

    // ---- Lieux tenus ----

    /** Vrai si le lieu `key` est tenu et ses chunks chargés : il travaille comme sous les yeux d'un joueur. */
    public static boolean held(World world, String key) {
        Hold h = HOLDS.get(world);
        return h != null && h.ready && h.key.equals(key);
    }

    public static boolean held(World world, Village village) {
        return held(world, villageKey(village));
    }

    public static String villageKey(Village village) {
        return "village:" + village.id();
    }

    /** Lieu tenu en ce moment (commande), ou null. */
    public static String current(World world) {
        Hold h = HOLDS.get(world);
        return h == null ? null : h.key + (h.ready ? "" : " (chargement)");
    }

    /** Vrai si le lieu `key` pourrait être tenu maintenant (rien de tenu dans ce monde, pas tenu récemment). */
    public static boolean free(World world, String key) {
        if (!on(world) || HOLDS.containsKey(world)) {
            return false;
        }
        Long last = LAST.get(key);
        return last == null || System.currentTimeMillis() - last >= COOLDOWN_MILLIS;
    }

    /**
     * Le lieu `key` a du travail sans joueur : s'il n'y a aucun lieu tenu dans ce monde et qu'il n'a pas été tenu
     * récemment, ses chunks (rectangle de blocs) sont chargés puis gardés ; il travaille aux pas suivants. `busy` dit si
     * un collage est encore en cours (la tenue dure alors jusqu'à sa fin). Rend vrai si la tenue commence. Thread du
     * monde.
     */
    public static boolean offer(World world, String key, int x0, int x1, int z0, int z1, BooleanSupplier busy) {
        if (!free(world, key)) {
            return false;
        }
        LAST.put(key, System.currentTimeMillis());
        long[] chunks = chunks(x0, x1, z0, z1);
        Hold h = new Hold(key, chunks, busy);
        HOLDS.put(world, h);
        List<int[]> blocks = depositsIn(world, x0, x1, z0, z1);
        load(world, chunks).thenCompose(ignored -> loadSections(world, blocks)).whenComplete((ignored, error) -> world.execute(() -> {
            if (HOLDS.get(world) != h) {
                return;
            }
            if (error != null) {
                PeuplesPlugin.get().getLogger().atWarning().withCause(error).log("Peuples (veille) : chunks de %s non chargés", key);
                HOLDS.remove(world);
                return;
            }
            keep(world, chunks, h.kept);
            keepSections(world, blocks, h.keptSections);
            h.ready = true;
            h.readyAt = System.currentTimeMillis();
        }));
        PeuplesPlugin.get().getLogger().atInfo().log("Peuples (veille) : %s tenu sans joueur (%d chunks)", key, chunks.length);
        return true;
    }

    /** Vrai pendant que les dépôts se chargent pour le matin : le reste du pas attend le matin. */
    public static boolean morningPending(World world) {
        return MORNING.contains(world);
    }

    /** Le village tenu n'a pas les matériaux de son chantier ce jour de jeu : il n'est plus tenu pour ça avant demain. */
    public static void markShort(Village village, long day) {
        SHORT.put(village.id(), day);
    }

    public static boolean isShort(Village village, long day) {
        Long d = SHORT.get(village.id());
        return d != null && d == day;
    }

    /** Fin des tenues finies (à chaque pas de la vie des villages). Thread du monde. */
    public static void step(World world) {
        Hold h = HOLDS.get(world);
        if (h == null) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean busy = h.busy != null && h.busy.getAsBoolean();
        if (now - h.since >= MAX_HOLD_MILLIS || (h.ready && now - h.readyAt >= HOLD_MILLIS && !busy)) {
            release(world);
        }
    }

    /** Relâche le lieu tenu : ses chunks se déchargeront comme les autres, loin des joueurs. */
    public static void release(World world) {
        Hold h = HOLDS.remove(world);
        if (h == null) {
            return;
        }
        for (WorldChunk c : h.kept) {
            c.removeKeepLoaded();
        }
        h.kept.clear();
        for (ChunkSection c : h.keptSections) {
            c.removeKeepLoaded();
        }
        h.keptSections.clear();
    }

    // ---- Dépôts du matin ----

    /**
     * Charge le chunk du dépôt de chaque village (et lieu-dit) et le garde le temps de `then` (prélèvements du matin,
     * livraisons en attente), puis le relâche. Rend faux si un chargement du matin est déjà en cours. Thread du monde.
     */
    public static boolean withDeposits(World world, Runnable then) {
        if (!MORNING.add(world)) {
            return false;
        }
        Set<Long> set = new LinkedHashSet<>();
        List<int[]> blocks = new ArrayList<>();
        for (Village v : Villages.registry(world).all()) {
            int[] d = v.deposit();
            if (d != null) {
                set.add(ChunkUtil.indexChunk(ChunkUtil.chunkCoordinate(d[0]), ChunkUtil.chunkCoordinate(d[2])));
                blocks.add(d);
            }
        }
        long[] chunks = set.stream().mapToLong(Long::longValue).toArray();
        // Le chargeur du monde charge les sections une à une : la colonne seule ne rend pas le coffre lisible.
        load(world, chunks).thenCompose(ignored -> loadSections(world, blocks)).whenComplete((ignored, error) -> world.execute(() -> {
            List<WorldChunk> kept = new ArrayList<>();
            List<ChunkSection> keptSections = new ArrayList<>();
            try {
                if (error != null) {
                    PeuplesPlugin.get().getLogger().atWarning().withCause(error).log("Peuples (veille) : dépôts non chargés pour le matin");
                }
                keep(world, chunks, kept);
                int read = keepSections(world, blocks, keptSections);
                PeuplesPlugin.get().getLogger().atInfo().log("Peuples (veille) : matin, %d dépôt(s) sur %d chargé(s)", read, blocks.size());
                then.run();
            } finally {
                for (WorldChunk c : kept) {
                    c.removeKeepLoaded();
                }
                for (ChunkSection c : keptSections) {
                    c.removeKeepLoaded();
                }
                MORNING.remove(world);
            }
        }));
        return true;
    }

    // ---- Chunks ----

    static long[] chunks(int x0, int x1, int z0, int z1) {
        List<Long> out = new ArrayList<>();
        for (int cx = ChunkUtil.chunkCoordinate(Math.min(x0, x1)); cx <= ChunkUtil.chunkCoordinate(Math.max(x0, x1)); cx++) {
            for (int cz = ChunkUtil.chunkCoordinate(Math.min(z0, z1)); cz <= ChunkUtil.chunkCoordinate(Math.max(z0, z1)); cz++) {
                out.add(ChunkUtil.indexChunk(cx, cz));
            }
        }
        return out.stream().mapToLong(Long::longValue).toArray();
    }

    private static CompletableFuture<Void> load(World world, long[] chunks) {
        ChunkStore store = world.getChunkStore();
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (long index : chunks) {
            futures.add(store.getChunkReferenceAsync(index));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .orTimeout(LOAD_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** Dépôts des villages dont le coffre est dans le rectangle de blocs (tenue d'un lieu). */
    private static List<int[]> depositsIn(World world, int x0, int x1, int z0, int z1) {
        List<int[]> out = new ArrayList<>();
        for (Village v : Villages.registry(world).all()) {
            int[] d = v.deposit();
            if (d != null && d[0] >= Math.min(x0, x1) && d[0] <= Math.max(x0, x1) && d[2] >= Math.min(z0, z1) && d[2] <= Math.max(z0, z1)) {
                out.add(d);
            }
        }
        return out;
    }

    /** Charge la section de chaque bloc (x, y, z) : les sections se chargent à part de leur colonne. */
    private static CompletableFuture<Void> loadSections(World world, List<int[]> blocks) {
        ChunkStore store = world.getChunkStore();
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (int[] b : blocks) {
            futures.add(store.getChunkSectionReferenceAtBlockAsync(b[0], b[1], b[2]));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .orTimeout(LOAD_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** Garde chargées les sections des blocs donnés ; rend le nombre de sections trouvées. */
    private static int keepSections(World world, List<int[]> blocks, List<ChunkSection> kept) {
        ChunkStore store = world.getChunkStore();
        int found = 0;
        for (int[] b : blocks) {
            Ref<ChunkStore> ref = store.getChunkSectionReferenceAtBlock(b[0], b[1], b[2]);
            ChunkSection c = ref == null || !ref.isValid() ? null : store.getStore().getComponent(ref, ChunkSection.getComponentType());
            if (c != null) {
                found++;
                if (!kept.contains(c)) {
                    c.addKeepLoaded();
                    kept.add(c);
                }
            }
        }
        return found;
    }

    private static void keep(World world, long[] chunks, List<WorldChunk> kept) {
        ChunkStore store = world.getChunkStore();
        for (long index : chunks) {
            WorldChunk c = store.getChunkComponent(index, WorldChunk.getComponentType());
            if (c != null) {
                c.addKeepLoaded();
                kept.add(c);
            }
        }
    }
}
