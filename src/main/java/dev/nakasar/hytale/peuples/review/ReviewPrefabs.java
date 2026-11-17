package dev.nakasar.hytale.peuples.review;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.hypixel.hytale.builtin.buildertools.prefabeditor.saving.PrefabSaver;
import com.hypixel.hytale.builtin.buildertools.prefabeditor.saving.PrefabSaverSettings;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import dev.nakasar.hytale.peuples.village.Blocks;
import dev.nakasar.hytale.peuples.village.Terrain;
import dev.nakasar.hytale.peuples.workshop.Workshops;
import org.joml.Vector3i;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Carte de revue comme éditeur de prefabs (Kevin, 17 novembre 2026 : « le but c'est de pouvoir modifier complètement
 * les prefab pour pouvoir faciliter leur mise à jour dans le mod, comme un éditeur ») : {@code /village revue --sauver},
 * debout dans un rectangle retouché à la main, enregistre ce palier comme prefab du mod.
 *
 * <ul>
 *   <li>Zone enregistrée : l'intérieur du rectangle (sans le liseré), de {@value #DEPTH} blocs sous le sol au haut du
 *   rectangle, origine = point de collage du palier (les mêmes coordonnées que le prefab d'origine) ; les couches du
 *   monde plat (herbe, terre, pierre) et le panneau muet de la carte n'en font pas partie ;</li>
 *   <li>le panneau de métier n'est pas dans le prefab : il est relevé à part (place gardée dans le relevé des
 *   panneaux) puis remis ;</li>
 *   <li>sortie dans {@code nakasar-revue/} à côté des mondes du serveur : {@code prefabs/Nakasar/Peuples/Retouches/...}
 *   et la liste {@value #LIST_FILE}, à copier dans le dépôt ({@code src/main/resources/Server/Prefabs/} et
 *   {@code tools/retouches-prefabs.json}). Les générateurs prennent alors ce prefab pour ce palier, en jeu et sur la
 *   carte.</li>
 * </ul>
 */
public final class ReviewPrefabs {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** Profondeur enregistrée sous le sol (fondations, caves des prefabs vanilla). */
    static final int DEPTH = 16;
    public static final String LIST_FILE = "retouches-prefabs.json";
    public static final String ROOT = "Nakasar/Peuples/Retouches";

    private ReviewPrefabs() {
    }

    /** Un palier retouché : prefab du mod qui le remplace. */
    static final class Retouch {
        @SerializedName("Key")
        String key;
        @SerializedName("People")
        String people;
        @SerializedName("Category")
        String category;
        @SerializedName("Id")
        String id;
        @SerializedName("Tier")
        int tier;
        @SerializedName("Prefab")
        String prefab;
    }

    static final class ListFile {
        @SerializedName("$Comment")
        String comment;
        @SerializedName("Prefabs")
        List<Retouch> prefabs = new ArrayList<>();
    }

    /** Résultat : rectangle enregistré, chemin du prefab (id du mod), fichier écrit. */
    public record Saved(String building, int tier, String prefab, Path file) {
    }

    /** Dossier des sorties de la carte (relevés, prefabs), à côté des mondes du serveur. */
    public static Path dir() {
        return ReviewMap.captureFile().getParent();
    }

    /**
     * Enregistre le palier du rectangle qui contient (x, z) du monde de revue `world`. À appeler sur le thread du
     * monde. Échoue (IllegalStateException) hors d'un rectangle bâti.
     */
    public static CompletableFuture<Saved> save(CommandSender sender, World world, int x, int z) {
        ReviewMap.Spot s = ReviewMap.spotAt(world, x, z);
        if (s == null || !ReviewMap.loadState(world).contains(s.key)) {
            return CompletableFuture.failedFuture(new IllegalStateException("hors d'un rectangle bâti"));
        }
        ChunkStore chunks = world.getChunkStore();
        // Panneau de métier : relevé (s'il a bougé), retiré le temps d'enregistrer, remis ensuite.
        int[] board = ReviewMap.captureOne(world, s.key);
        String boardBlock = board == null ? null : ReviewMap.boardBlock(world, s.key);
        if (board != null) {
            Workshops.unhangBoard(chunks, new int[] {board[0], board[1], board[2]}, board[3]);
            Blocks.set(chunks, board[0], board[1], board[2], null, 0);
        }
        String rel = ROOT + "/" + s.section.people + "/" + s.row.category + "_" + s.row.id + "_P" + s.tier.tier;
        Path out = dir().resolve("prefabs").resolve(rel + ".prefab.json");
        // Nom unique : le magasin de prefabs garde en cache ce qu'il a lu par chemin.
        Path temp = dir().resolve("tmp").resolve(s.section.people + "_" + s.row.id + "_P" + s.tier.tier + "_"
                + System.currentTimeMillis() + ".prefab.json");
        Vector3i origin = new Vector3i(s.ox, ReviewMap.GROUND, s.oz);
        Vector3i min = new Vector3i(s.x0 + 1, ReviewMap.GROUND - DEPTH, s.z0 + 1);
        Vector3i max = new Vector3i(s.x1 - 1, ReviewMap.GROUND + s.height + ReviewMap.CLEAR_ABOVE, s.z1 - 1);
        PrefabSaverSettings settings = new PrefabSaverSettings();
        settings.setBlocks(true);
        settings.setEntities(false);
        settings.setEmpty(false);
        settings.setOverwriteExisting(true);
        try {
            Files.createDirectories(temp.getParent());
            Files.createDirectories(out.getParent());
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
        // Copie du serveur (états de blocs, remplissages), puis tri : sans les couches du monde plat ni le panneau muet.
        CompletableFuture<Path> copy = PrefabSaver.savePrefab(sender, world, temp, origin, min, max, origin,
                new Vector3i(0, 0, 0), settings);
        return copy.thenApplyAsync(path -> {
            BlockSelection raw = PrefabStore.get().getPrefab(path);
            BlockSelection kept = new BlockSelection();
            kept.setPosition(raw.getX(), raw.getY(), raw.getZ());
            kept.setAnchor(raw.getAnchorX(), raw.getAnchorY(), raw.getAnchorZ());
            int[] counts = {0, 0};
            raw.forEachBlock((bx, by, bz, h) -> {
                BlockType type = BlockType.getAssetMap().getAsset(h.blockId());
                String name = type == null ? null : type.getId();
                if (name == null || natural(name, by) || Blocks.is(name, ReviewMap.SIGN)) {
                    counts[1]++;
                    return;
                }
                kept.addBlockAtLocalPos(bx, by, bz, h.blockId(), h.rotation(), h.filler(), h.supportValue(), h.holder());
                counts[0]++;
            });
            raw.forEachFluid((bx, by, bz, fluid, level) -> kept.addFluidAtLocalPos(bx, by, bz, fluid, level));
            PrefabStore.get().savePrefab(out, kept, true);
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                LOGGER.atWarning().withCause(e).log("Nakasar Peuples : revue, copie temporaire %s laissée", path);
            }
            record(s, rel);
            LOGGER.atInfo().log("Nakasar Peuples : revue, %s enregistré (%d blocs, %d du monde plat écartés) → %s", s.key,
                    counts[0], counts[1], out);
            return new Saved(s.row.name, s.tier.tier, rel, out);
        }, world::execute).whenCompleteAsync((r, e) -> {
            if (board != null && boardBlock != null) {
                Blocks.set(chunks, board[0], board[1], board[2], boardBlock, board[3]);
                Workshops.hangBoard(chunks, s.section.people, board[0], board[1], board[2], board[3]);
            }
        }, world::execute);
    }

    /**
     * Vrai pour un bloc des couches du monde plat de revue à sa hauteur locale `y` (0 = le sol d'herbe) : il n'est pas au
     * bâtiment. Un bloc identique du prefab d'origine à cette place se confond avec le terrain, en jeu aussi.
     */
    static boolean natural(String name, int y) {
        if (y == 0) {
            return Blocks.is(name, ReviewMap.GRASS);
        }
        if (y < 0 && y >= -3) {
            return Blocks.is(name, Terrain.DIRT);
        }
        return y < -3 && Blocks.is(name, "Rock_Stone");
    }

    private static synchronized void record(ReviewMap.Spot s, String prefab) {
        Path file = dir().resolve(LIST_FILE);
        ListFile doc = null;
        try {
            if (Files.exists(file)) {
                doc = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), ListFile.class);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.atWarning().withCause(e).log("Nakasar Peuples : revue, liste %s illisible", file);
        }
        if (doc == null || doc.prefabs == null) {
            doc = new ListFile();
        }
        doc.prefabs.removeIf(r -> s.key.equals(r.key));
        Retouch r = new Retouch();
        r.key = s.key;
        r.people = s.section.people;
        r.category = s.row.category;
        r.id = s.row.id;
        r.tier = s.tier.tier;
        r.prefab = prefab;
        doc.prefabs.add(r);
        doc.prefabs.sort(Comparator.comparing(x -> x.key));
        doc.comment = "Paliers retouchés à la main sur la carte de revue (/village revue --sauver). Copier prefabs/ dans "
                + "src/main/resources/Server/Prefabs/ et ce fichier dans tools/retouches-prefabs.json du dépôt, puis "
                + "relancer tools/building_growth.py et tools/building_tiers.py : ce prefab remplace le palier, en jeu "
                + "et sur la carte.";
        try {
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(doc) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
