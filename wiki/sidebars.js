// Barre latérale du wiki. Les ids sont les chemins des pages sous docs/wiki, sans extension ; un README est
// l'index de son dossier (docs/wiki/README.md est l'accueil du site). Les grandes rubriques sont aussi dans la
// barre de sections (src/mods.js) : garder les deux d'accord.

/** @type {import('@docusaurus/plugin-content-docs').SidebarsConfig} */
const sidebars = {
  peuples: [
    "README",
    {
      type: "category",
      label: "Vivre avec les peuples",
      collapsed: false,
      items: [
        "reputation",
        "villages",
        "habitants",
        "monde-vivant",
        "lieux-dits",
        "conquete",
        "commerce",
        "climats",
      ],
    },
    {
      type: "category",
      label: "Les peuples",
      items: ["kweebecs", "trorks", "feran", "renonces", "slothians"],
    },
    {
      type: "category",
      label: "Bâtiments et métiers",
      items: [
        "batir",
        "batiments-de-metier",
        "emplacements",
        "avant-postes-de-metier",
        "infrastructures",
        {
          type: "category",
          label: "Bâtiments des villages",
          link: { type: "doc", id: "batiments/README" },
          items: [
            "batiments/kweebecs",
            "batiments/trorks",
            "batiments/feran",
            "batiments/renonces",
            "batiments/slothians",
          ],
        },
      ],
    },
    "avant-poste",
  ],
};

export default sidebars;
