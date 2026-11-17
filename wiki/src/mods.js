// Le mod du wiki : nom, pitch et rubriques. Utilisé par la barre de sections (src/components/ModBar) et la
// teinte du site (attribut data-mod sur <html>, couleurs dans src/css/custom.css). Une rubrique est active quand
// l'URL commence par l'un de ses préfixes `match` (par défaut son lien `to`).

export const MODS = [
  {
    id: "peuples",
    name: "Peuples d'Orbis",
    pack: "Nakasar : Peuples",
    to: "/",
    image: "peuples",
    pitch:
      "Des villages vivants qui grandissent avec votre aide, de la forêt kweebec au monastère slothian au bord du Vide, jusqu'à devenir le Champion d'un peuple.",
    cta: "Rejoindre les villages",
    stats: [
      ["5", "peuples"],
      ["4", "zones"],
      ["6", "rangs"],
    ],
    sections: [
      { label: "Présentation", to: "/", exact: true },
      { label: "Réputation", to: "/reputation" },
      { label: "Vivre au village", to: "/villages", match: ["/villages", "/monde-vivant", "/climats"] },
      {
        label: "Les peuples",
        to: "/kweebecs",
        match: ["/kweebecs", "/trorks", "/feran", "/renonces", "/slothians"],
      },
      {
        label: "Bâtiments",
        to: "/batiments",
        match: ["/batiments", "/batiments-de-metier", "/emplacements", "/avant-postes-de-metier"],
      },
      { label: "Avant-poste", to: "/avant-poste" },
    ],
  },
];

function under(path, prefix) {
  return path === prefix || path.startsWith(prefix + "/");
}

/** Le mod d'une URL du site (sans baseUrl) : le site n'en a qu'un. */
export function modOf(_path) {
  return MODS[0];
}

/** La rubrique active du mod pour cette URL. */
export function sectionOf(mod, path) {
  const clean = path.replace(/\/$/, "") || "/";
  return (
    mod.sections.find((s) => (s.exact ? clean === s.to : (s.match ?? [s.to]).some((p) => under(clean, p)))) ?? null
  );
}
