// Wiki joueur du mod Peuples d'Orbis. Les pages sont le Markdown de ../docs/wiki (docs/wiki/README.md = accueil) ;
// le site est publié sur GitHub Pages (https://orbis.nakasar.xyz) par .github/workflows/wiki.yml. Aperçu local :
// npm ci puis npm start. Rubriques : barre latérale (sidebars.js) et barre de sections (src/mods.js).
import { themes as prismThemes } from "prism-react-renderer";

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: "Wiki Peuples d'Orbis",
  tagline: "Guide du joueur du mod Peuples d'Orbis pour Hytale",
  favicon: "img/logo.svg",
  url: "https://orbis.nakasar.xyz",
  baseUrl: "/",
  organizationName: "Nakasar",
  projectName: "peoples-of-orbis",
  trailingSlash: false,
  onBrokenLinks: "throw",
  onBrokenAnchors: "throw",
  markdown: { hooks: { onBrokenMarkdownLinks: "throw" } },
  i18n: { defaultLocale: "fr", locales: ["fr"] },
  stylesheets: [
    "https://fonts.googleapis.com/css2?family=Cinzel:wght@600;700&family=Inter:wght@400;500;600;700&display=swap",
  ],

  presets: [
    [
      "classic",
      /** @type {import('@docusaurus/preset-classic').Options} */
      ({
        docs: {
          path: "../docs/wiki",
          routeBasePath: "/",
          sidebarPath: "./sidebars.js",
          editUrl: "https://github.com/Nakasar/peoples-of-orbis/edit/main/docs/wiki/",
        },
        blog: false,
        theme: { customCss: "./src/css/custom.css" },
      }),
    ],
  ],

  plugins: [
    [
      "@docusaurus/plugin-client-redirects",
      {
        // Les pages étaient sous /peuples sur le wiki commun des mods Nakasar : une ancienne adresse
        // (/peuples/...) recopiée sur ce domaine mène à la page.
        createRedirects(path) {
          return path === "/" ? ["/peuples"] : [`/peuples${path}`];
        },
      },
    ],
  ],

  themes: [
    [
      "@easyops-cn/docusaurus-search-local",
      /** @type {import('@easyops-cn/docusaurus-search-local').PluginOptions} */
      ({ language: ["fr"], docsDir: "../docs/wiki", docsRouteBasePath: "/", indexBlog: false, hashed: true }),
    ],
  ],

  themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
    ({
      colorMode: { defaultMode: "dark", respectPrefersColorScheme: false },
      docs: { sidebar: { autoCollapseCategories: true } },
      navbar: {
        title: "Peuples d'Orbis",
        logo: { alt: "Accueil du wiki", src: "img/logo.svg" },
        items: [
          { type: "docSidebar", sidebarId: "peuples", label: "Guide du joueur", position: "left", className: "nk-mod-tab nk-mod-tab--peuples" },
          { href: "https://hunters.nakasar.xyz", label: "Autres mods Nakasar", position: "right" },
          { href: "https://github.com/Nakasar/peoples-of-orbis", label: "GitHub", position: "right" },
        ],
      },
      footer: {
        style: "dark",
        links: [
          { label: "Peuples d'Orbis", to: "/" },
          { label: "Autres mods Nakasar", href: "https://hunters.nakasar.xyz" },
          { label: "GitHub", href: "https://github.com/Nakasar/peoples-of-orbis" },
        ],
        copyright: "Mod Peuples d'Orbis pour Hytale, par Nakasar. Chiffres lus dans les fichiers du mod.",
      },
      prism: { theme: prismThemes.github, darkTheme: prismThemes.dracula },
    }),
};

export default config;
