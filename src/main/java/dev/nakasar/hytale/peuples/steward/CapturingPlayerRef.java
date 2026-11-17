package dev.nakasar.hytale.peuples.steward;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Le joueur tel que le voient les fonctions du jeu appelées par le gestionnaire ({@link StewardActions}) : tout est
 * rendu par le vrai joueur (uuid, nom, langue, position, permissions, référence, paquets pour les sons et les
 * notifications), sauf ses messages ({@link #sendMessage}), gardés : la fenêtre de dialogue les montre tels quels sous la
 * réponse du gestionnaire (un refus du jeu reste le texte du jeu, le modèle ne le reformule pas).
 */
final class CapturingPlayerRef extends PlayerRef {

    private final PlayerRef real;
    private final List<Message> captured = new ArrayList<>();

    CapturingPlayerRef(PlayerRef real) {
        super(null, real.getUuid(), real.getUsername(), real.getLanguage(), real.getPacketHandler(), real.getChunkTracker());
        this.real = real;
    }

    /** Messages reçus depuis le dernier appel, et remise à zéro. */
    List<Message> drain() {
        List<Message> out = List.copyOf(captured);
        captured.clear();
        return out;
    }

    @Override
    public void sendMessage(Message message) {
        if (message != null) {
            captured.add(message);
        }
    }

    @Override
    public Ref<EntityStore> getReference() {
        return real.getReference();
    }

    @Override
    public boolean isValid() {
        return real.isValid();
    }

    @Override
    public Holder<EntityStore> getHolder() {
        return real.getHolder();
    }

    @Override
    @SuppressWarnings({"removal", "deprecation"})
    public <T extends Component<EntityStore>> T getComponent(ComponentType<EntityStore, T> type) {
        return real.getComponent(type);
    }

    @Override
    @SuppressWarnings({"removal", "deprecation"})
    public <T extends Component<EntityStore>> T getComponentConcurrent(ComponentType<EntityStore, T> type) {
        return real.getComponentConcurrent(type);
    }

    @Override
    public UUID getUuid() {
        return real.getUuid();
    }

    @Override
    public String getUsername() {
        return real.getUsername();
    }

    @Override
    public String getLanguage() {
        return real.getLanguage();
    }

    @Override
    public PacketHandler getPacketHandler() {
        return real.getPacketHandler();
    }

    @Override
    public Transform getTransform() {
        return real.getTransform();
    }

    @Override
    public UUID getWorldUuid() {
        return real.getWorldUuid();
    }

    @Override
    public boolean hasPermission(String id) {
        return real.hasPermission(id);
    }

    @Override
    public boolean hasPermission(String id, boolean def) {
        return real.hasPermission(id, def);
    }
}
