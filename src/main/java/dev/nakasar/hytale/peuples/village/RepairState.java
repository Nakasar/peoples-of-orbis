package dev.nakasar.hytale.peuples.village;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * La réparation en cours d'un bâtiment brûlé ({@link Repairs}) : bâtiment du plan, début et fin en secondes de jeu, et
 * qui l'a lancée (UUID du joueur, ou {@code village} : le conseil du village).
 */
public final class RepairState {

    public static final BuilderCodec<RepairState> CODEC = BuilderCodec.builder(RepairState.class, RepairState::new)
            .append(new KeyedCodec<>("Building", Codec.STRING), (s, v) -> s.building = v, s -> s.building).add()
            .append(new KeyedCodec<>("Start", Codec.LONG), (s, v) -> s.start = v == null ? 0L : v, s -> s.start).add()
            .append(new KeyedCodec<>("End", Codec.LONG), (s, v) -> s.end = v == null ? 0L : v, s -> s.end).add()
            .append(new KeyedCodec<>("By", Codec.STRING), (s, v) -> s.by = v, s -> s.by)
            .documentation("UUID du joueur qui a lancé la réparation, ou « village » (le conseil).")
            .add()
            .build();

    String building;
    long start;
    long end;
    String by;

    RepairState() {
    }

    RepairState(String building, long start, long end, String by) {
        this.building = building;
        this.start = start;
        this.end = end;
        this.by = by;
    }

    public String building() {
        return building;
    }

    public long start() {
        return start;
    }

    public long end() {
        return end;
    }

    public String by() {
        return by;
    }

    RepairState copy() {
        return new RepairState(building, start, end, by);
    }
}
