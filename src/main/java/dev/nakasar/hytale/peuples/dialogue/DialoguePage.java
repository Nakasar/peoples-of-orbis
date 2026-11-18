package dev.nakasar.hytale.peuples.dialogue;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.List;

/**
 * La fenêtre de dialogue, en bas de l'écran (maquette /mnt/project-files/peuples/maquettes/dialogue_bas_ecran.png) :
 * nom, métier et rang du joueur auprès du peuple dans l'en-tête, la réplique, quatre réponses numérotées au plus avec
 * leur effet (ou leur condition si elles sont grisées), le portrait du PNJ à droite et « Partir » dessous. La moitié haute
 * de l'écran reste à la scène, que la caméra de dialogue ({@link DialogueCamera}) cadre quand un PNJ parle.
 * Mise en page {@value #LAYOUT} livrée par le pack du mod (Common/UI/Custom/), bâtie sur les modèles vanilla de
 * Common.ui (@DecoratedContainer, @PanelSeparatorFancy, @SmallSecondaryTextButton), remplie et écoutée par le Java
 * comme les pages vanilla (BlockLorePage, MemoriesPage pour le portrait AssetImage). Secours : le dialogue par le tchat
 * ({@link Dialogues#openChat}).
 */
public final class DialoguePage extends InteractiveCustomUIPage<DialoguePage.Event> {

    public static final String LAYOUT = "Pages/Nakasar/Peuples/DialoguePage.ui";
    private static final String LEAVE = "Leave";
    /** Gestionnaire qui parle (steward/) : passer en dialogue libre, envoyer, confirmer, annuler, ouvrir le panneau. */
    private static final String TALK = "Talk";
    private static final String SAY = "Say";
    private static final String CONFIRM = "Confirm";
    private static final String CANCEL = "Cancel";
    private static final String BOARD = "Board";
    private static final String LOCKED_COLOR = "#c49a6c";
    private static final String LOCKED_TEXT_COLOR = "#7c838c";

    /** Donnée d'un clic : l'index de la réponse, ou {@value #LEAVE}. */
    public static final class Event {
        public static final BuilderCodec<Event> CODEC = BuilderCodec.builder(Event.class, Event::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (e, v) -> e.action = v, e -> e.action)
                .add()
                .append(new KeyedCodec<>("@Text", Codec.STRING), (e, v) -> e.text = v, e -> e.text)
                .add()
                .build();

        String action;
        /** Ce que le joueur a écrit au gestionnaire (champ #Input). */
        String text;
    }

    private final Dialogue dialogue;
    private final DialogueNode start;
    private final String portrait;
    /** Nœud affiché ; null quand la réponse choisie fermait le dialogue mais que le PNJ a parlé ({@link #said}). */
    private DialogueNode node;
    /** Ce que le PNJ vient de dire en réponse au dernier choix, affiché sous sa réplique. */
    private List<Message> said = List.of();
    private boolean camera;
    private Ref<EntityStore> npc;
    private Ref<EntityStore> talker;
    /** L'habitant que montre le PNJ (générations de villageois) : son nom remplace celui du dialogue. */
    private dev.nakasar.hytale.peuples.population.Population.Identity identity;
    /** Village (ou lieu-dit H:) dont le PNJ est le gestionnaire qui parle ; null pour les autres PNJ. */
    private String stewardVillage;
    /** Dialogue libre affiché à la place des réponses. */
    private boolean talking;
    /** Une question est partie : la fenêtre attend la réponse. */
    private boolean waiting;
    private Message stewardLine;
    private List<Message> stewardGame = List.of();
    private List<String> stewardPending = List.of();
    /**
     * PNJ de passage qui parle (Klops, voyageur, caravanier, aubergiste, campeur : world/Wayfarers) : dialogue libre
     * (steward/FreeTalk) et paroles d'information redites avec sa voix (steward/Retell). Null pour les autres PNJ.
     */
    private Ref<EntityStore> wayfarer;
    /** Numéro du dernier choix : une réplique redite qui arrive après un autre choix est jetée. */
    private int retellSeq;

    public DialoguePage(PlayerRef playerRef, Dialogue dialogue, DialogueNode start) {
        this(playerRef, dialogue, start, null);
    }

    /** `portrait` : chemin AssetImage du portrait du PNJ ({@link Portraits}), null pour aucun. */
    public DialoguePage(PlayerRef playerRef, Dialogue dialogue, DialogueNode start, String portrait) {
        super(playerRef, CustomPageLifetime.CanDismiss, Event.CODEC);
        this.dialogue = dialogue;
        this.start = start;
        this.portrait = portrait;
        this.node = start;
    }

    /** Le PNJ est le gestionnaire de `villageId` : le bouton « Parler librement » s'affiche si le serveur l'a activé. */
    void steward(String villageId) {
        this.stewardVillage = villageId;
    }

    /** Le PNJ est de passage : « Parler librement » et répliques redites si le serveur les a activés. */
    void wayfarer(Ref<EntityStore> npc) {
        this.wayfarer = npc;
    }

    /** Le dialogue libre de cette fenêtre : celui du gestionnaire, ou celui d'un PNJ de passage. */
    private boolean canTalk() {
        return stewardVillage != null ? dev.nakasar.hytale.peuples.steward.Steward.enabled()
                : wayfarer != null && dev.nakasar.hytale.peuples.steward.FreeTalk.enabled();
    }

    void identity(dev.nakasar.hytale.peuples.population.Population.Identity identity) {
        this.identity = identity;
    }

    /** Ce que le PNJ dit déjà à l'ouverture, sous sa première réplique. */
    void said(List<Message> lines) {
        said = lines == null ? List.of() : List.copyOf(lines);
    }

    /** Le PNJ `npc` fait face à `talker` ({@link DialogueFacing}) : il sera libéré à la fermeture. */
    void facing(Ref<EntityStore> npc, Ref<EntityStore> talker) {
        this.npc = npc;
        this.talker = talker;
    }

    /** La caméra de dialogue a été posée pour cette fenêtre : elle sera rendue à sa fermeture. */
    void cameraFramed() {
        camera = true;
    }

    // Libellés en « .TextSpans » (message composé ou vide sur « .Text » : plantage du client), bouton Partir en « .Text »
    // avec une traduction sans paramètre Message (non remplacé par le client), comme les pages vanilla (voir BoardPage).
    // Les réponses sont des Button vanilla portant des Label : pas de texte de bouton imbriqué.
    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        cmd.append(LAYOUT);
        World world = store.getExternalData().getWorld();
        DialogueContext context = DialogueContext.of(world, store, ref, playerRef);
        cmd.set("#NpcName.TextSpans", identity != null ? identity.name()
                : dialogue.npcNameKey() == null ? Message.raw("?") : Message.translation(dialogue.npcNameKey()));
        cmd.set("#NpcRole.Visible", dialogue.roleKey() != null);
        cmd.set("#NpcRole.TextSpans", dialogue.roleKey() == null ? Message.raw(" ") : Message.translation(dialogue.roleKey()));
        Message standing = Dialogues.standing(dialogue, context);
        cmd.set("#Standing.Visible", standing != null);
        cmd.set("#Standing.TextSpans", standing == null ? Message.raw(" ") : standing);
        cmd.set("#PortraitFrame.Visible", portrait != null);
        if (portrait != null) {
            cmd.set("#Portrait.AssetPath", portrait);
        }
        boolean steward = canTalk();
        cmd.set("#Talk.Visible", steward);
        if (steward) {
            cmd.set("#Talk.Text", Message.translation(dev.nakasar.hytale.peuples.steward.Steward.KEYS + (talking ? "back" : "talk")));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#Talk", EventData.of("Action", TALK));
        }
        cmd.set("#StewardRow.Visible", steward && talking);
        cmd.set("#ConfirmRow.Visible", steward && talking && stewardVillage != null);
        cmd.set("#Leave.Text", Message.translation(Dialogues.KEYS + "leave"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#Leave", EventData.of("Action", LEAVE));
        if (steward && talking) {
            buildSteward(cmd, events);
            return;
        }
        // Paroles du dernier choix (où va le Klops, une rumeur, un refus...) sous la réplique ; seules si le PNJ n'a
        // plus de réplique (réponse qui fermait le dialogue) : le joueur les lit avant de partir.
        Message line = DialogueSpeech.withSaid(node == null ? null : Message.translation(node.textKey()), said);
        cmd.set("#Line.TextSpans", line == null ? Message.raw(" ") : line);
        // À l'ouverture, le PNJ parle des PNJ du village tués aujourd'hui (ils reviennent au matin).
        Message aside = node == start ? dev.nakasar.hytale.peuples.village.Absences.aside(dialogue, context) : null;
        if (aside == null && node == start && identity != null) {
            aside = identity.about();
        }
        cmd.set("#Aside.Visible", aside != null);
        cmd.set("#Aside.TextSpans", aside == null ? Message.raw(" ") : aside);
        DialogueResponse[] responses = node == null ? new DialogueResponse[0] : node.responses();
        for (int i = 0; i < Dialogue.MAX_RESPONSES; i++) {
            DialogueResponse r = i < responses.length ? responses[i] : null;
            String answer = "#Answer" + i;
            cmd.set(answer + ".Visible", r != null);
            if (r == null) {
                continue;
            }
            boolean available = r.available(context);
            cmd.set(answer + ".Disabled", !available);
            Message text = Message.translation(r.textKey());
            Message hint;
            if (available) {
                hint = r.hintKey() == null ? null : Message.translation(Dialogues.KEYS + "pageHint").param("hint", Message.translation(r.hintKey()));
            } else {
                text = text.color(LOCKED_TEXT_COLOR);
                hint = Message.translation(Dialogues.KEYS + "pageLockedShort").param("condition", Dialogues.failText(r)).color(LOCKED_COLOR);
            }
            cmd.set("#Text" + i + ".TextSpans", text);
            cmd.set("#Hint" + i + ".Visible", hint != null);
            cmd.set("#Hint" + i + ".TextSpans", hint == null ? Message.raw(" ") : hint);
            events.addEventBinding(CustomUIEventBindingType.Activating, answer, EventData.of("Action", Integer.toString(i)));
        }
    }

    /**
     * Dialogue libre avec le gestionnaire (steward/) : sa réponse en réplique, les messages du jeu (refus, résultats)
     * et les actions qui attendent Confirmer en aparté, le champ de saisie et ses boutons à la place des réponses.
     */
    private void buildSteward(UICommandBuilder cmd, UIEventBuilder events) {
        String keys = dev.nakasar.hytale.peuples.steward.Steward.KEYS;
        Message line = waiting ? Message.translation(keys + "thinking") : stewardLine != null ? stewardLine
                : Message.translation(stewardVillage != null ? keys + "intro" : dev.nakasar.hytale.peuples.steward.FreeTalk.KEYS + "intro");
        cmd.set("#Line.TextSpans", line);
        Message aside = null;
        for (Message m : stewardGame) {
            aside = aside == null ? Message.empty().insert(m) : aside.insert(Message.raw("\n")).insert(m);
        }
        if (!stewardPending.isEmpty()) {
            Message list = Message.translation(keys + "pendingList").param("actions", String.join(" ; ", stewardPending)).color(LOCKED_COLOR);
            aside = aside == null ? list : aside.insert(Message.raw("\n")).insert(list);
        }
        cmd.set("#Aside.Visible", aside != null);
        cmd.set("#Aside.TextSpans", aside == null ? Message.raw(" ") : aside);
        for (int i = 0; i < Dialogue.MAX_RESPONSES; i++) {
            cmd.set("#Answer" + i + ".Visible", false);
        }
        cmd.set("#Say.Text", Message.translation(keys + "say"));
        cmd.set("#Say.Disabled", waiting);
        EventData say = EventData.of("Action", SAY).append("@Text", "#Input.Value");
        events.addEventBinding(CustomUIEventBindingType.Activating, "#Say", say);
        events.addEventBinding(CustomUIEventBindingType.Validating, "#Input", say);
        if (stewardVillage == null) {
            return; // PNJ de passage : rien à confirmer, pas de panneau
        }
        boolean pending = !stewardPending.isEmpty() && !waiting;
        cmd.set("#Confirm.Visible", pending);
        cmd.set("#Cancel.Visible", pending);
        cmd.set("#Confirm.Text", Message.translation(keys + "confirm"));
        cmd.set("#Cancel.Text", Message.translation(keys + "cancel"));
        cmd.set("#OpenBoard.Text", Message.translation(keys + "board"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#Confirm", EventData.of("Action", CONFIRM));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#Cancel", EventData.of("Action", CANCEL));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#OpenBoard", EventData.of("Action", BOARD));
    }

    /** Événements du dialogue libre ; vrai si l'action en était un. */
    private boolean handleSteward(Ref<EntityStore> ref, Store<EntityStore> store, Event data) {
        World world = store.getExternalData().getWorld();
        switch (data.action) {
            case TALK -> {
                talking = !talking;
                if (!talking) {
                    node = start;
                    said = List.of();
                }
                rebuild();
            }
            case SAY -> {
                if (waiting || data.text == null || data.text.isBlank()) {
                    return true;
                }
                waiting = true;
                stewardGame = List.of();
                stewardPending = List.of();
                rebuild();
                String language = dev.nakasar.hytale.peuples.steward.StewardConfig.get().language;
                Message name = identity != null ? identity.name()
                        : dialogue.npcNameKey() == null ? null : Message.translation(dialogue.npcNameKey());
                String npcName = name == null ? null : dev.nakasar.hytale.peuples.steward.TextRender.text(name, language);
                String npcRole = dialogue.roleKey() == null ? null
                        : dev.nakasar.hytale.peuples.steward.TextRender.text(Message.translation(dialogue.roleKey()), language);
                java.util.function.Consumer<dev.nakasar.hytale.peuples.steward.Steward.Answer> done = answer -> {
                    waiting = false;
                    show(answer);
                    refreshIfOpen();
                };
                if (stewardVillage != null) {
                    dev.nakasar.hytale.peuples.steward.Steward.ask(world, store, ref, playerRef, stewardVillage, npcName, npcRole, data.text, done);
                } else {
                    dev.nakasar.hytale.peuples.steward.FreeTalk.ask(world, store, ref, playerRef, wayfarer, npcName, data.text, done);
                }
            }
            case CONFIRM -> {
                if (!waiting && stewardVillage != null) {
                    show(dev.nakasar.hytale.peuples.steward.Steward.confirm(world, store, ref, playerRef));
                    rebuild();
                }
            }
            case CANCEL -> {
                if (stewardVillage == null) {
                    return true;
                }
                dev.nakasar.hytale.peuples.steward.Steward.cancel(playerRef);
                stewardPending = List.of();
                stewardLine = Message.translation(dev.nakasar.hytale.peuples.steward.Steward.KEYS + "cancelled");
                rebuild();
            }
            case BOARD -> {
                if (stewardVillage == null) {
                    return true;
                }
                releaseCamera();
                if (!dev.nakasar.hytale.peuples.steward.Steward.openBoard(world, store, ref, playerRef, stewardVillage)) {
                    rebuild();
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void show(dev.nakasar.hytale.peuples.steward.Steward.Answer answer) {
        stewardLine = answer.line();
        stewardGame = answer.game();
        stewardPending = answer.pending();
    }

    /** La réponse arrive plus tard : ne redessiner que si cette fenêtre est encore celle du joueur. */
    private void refreshIfOpen() {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Player player = ref.getStore().getComponent(ref, Player.getComponentType());
        if (player != null && player.getPageManager().getCustomPage() == this) {
            rebuild();
        }
    }

    /** Fermeture par Échap, par « Partir », par un effet ou par une autre fenêtre ouverte à sa place : caméra rendue, PNJ libéré. */
    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        super.onDismiss(ref, store);
        releaseCamera();
    }

    /** Caméra rendue au joueur, PNJ libéré (il reprend sa vie). */
    private void releaseCamera() {
        if (npc != null) {
            DialogueFacing.stop(npc, talker);
            npc = null;
        }
        if (camera) {
            camera = false;
            DialogueCamera.reset(playerRef);
        }
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Event data) {
        if (data == null || data.action == null || LEAVE.equals(data.action)) {
            releaseCamera();
            close();
            return;
        }
        retellSeq++;
        if ((stewardVillage != null || wayfarer != null) && handleSteward(ref, store, data)) {
            return;
        }
        if (node == null) {
            return;
        }
        int index;
        try {
            index = Integer.parseInt(data.action);
        } catch (NumberFormatException e) {
            return;
        }
        World world = store.getExternalData().getWorld();
        Dialogues.Outcome outcome = Dialogues.choose(dialogue, node, index, DialogueContext.of(world, store, ref, playerRef));
        if (!outcome.chosen()) {
            said = outcome.said();
            retell(world, ref, store);
            rebuild();
            return;
        }
        if (outcome.closed() && !outcome.closedByEffect() && !outcome.said().isEmpty()) {
            // La réponse terminait le dialogue, mais le PNJ a répondu : la fenêtre reste ouverte sur ses paroles.
            node = null;
            said = outcome.said();
            retell(world, ref, store);
            rebuild();
            return;
        }
        if (outcome.closed()) {
            // Une autre page (boutique, tableau) remplace la fenêtre : ce que le PNJ a dit part au tchat.
            DialogueSpeech.flush(playerRef, outcome.said());
            releaseCamera();
            // Un effet a pu ouvrir une autre page (tableau) : ne fermer que si la fenêtre affichée est encore celle-ci.
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null || player.getPageManager().getCustomPage() == this) {
                close();
            }
            return;
        }
        node = outcome.next();
        said = outcome.said();
        retell(world, ref, store);
        rebuild();
    }

    /**
     * PNJ de passage : ses paroles d'information (rumeur, tournée, colis, lettre...) redites avec sa voix
     * (steward/Retell). En attendant, « … » à leur place ; le texte du jeu revient tel quel en cas d'échec ou de délai.
     */
    private void retell(World world, Ref<EntityStore> ref, Store<EntityStore> store) {
        if (wayfarer == null || said.isEmpty() || !dev.nakasar.hytale.peuples.steward.Retell.enabled()) {
            return;
        }
        int seq = retellSeq;
        List<Message> original = said;
        said = List.of(Message.translation(dev.nakasar.hytale.peuples.steward.Steward.KEYS + "thinking"));
        boolean pending = dev.nakasar.hytale.peuples.steward.Retell.retell(world, store, ref, playerRef, wayfarer, original, lines -> {
            if (seq == retellSeq) {
                said = lines;
                refreshIfOpen();
            }
        });
        if (!pending) {
            said = original;
        }
    }
}
