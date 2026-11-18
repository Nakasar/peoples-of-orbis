package dev.nakasar.hytale.peuples.village;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * La hauteur où un bâtiment du plan a été posé (ancre du prefab, au niveau du sol aplani), gardée pour le rebâtir, le
 * brûler et poser son échafaudage (étape 5). Les villages de l'étape 2 n'en ont pas : la hauteur se retrouve alors sur
 * le sol de l'emprise ({@link Chantiers#siteHeight}).
 */
public final class Site {

    public static final BuilderCodec<Site> CODEC = BuilderCodec.builder(Site.class, Site::new)
            .append(new KeyedCodec<>("Id", Codec.STRING), (s, v) -> s.id = v, s -> s.id).add()
            .append(new KeyedCodec<>("Y", Codec.INTEGER), (s, v) -> s.y = v == null ? 0 : v, s -> s.y).add()
            .append(new KeyedCodec<>("Palier", Codec.INTEGER), (s, v) -> s.palier = v == null ? 0 : v, s -> s.palier)
            .documentation("Palier d'apparence (1 à 4) du prefab collé ; 0 : bâti avant les paliers, donc le palier 1.")
            .add()
            .build();

    String id;
    int y;
    int palier;

    Site() {
    }

    Site(String id, int y) {
        this.id = id;
        this.y = y;
    }

    public String id() {
        return id;
    }

    public int y() {
        return y;
    }
}
