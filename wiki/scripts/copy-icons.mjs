// Copie les icônes du pack (Common/Icons et Common/UI du mod) dans static/jeu/ avant chaque construction : les
// pages les citent en /jeu/..., et le site montre ainsi toujours les icônes du mod.
import { cpSync, existsSync, rmSync } from "node:fs";
import { fileURLToPath } from "node:url";

const target = fileURLToPath(new URL("../static/jeu/", import.meta.url));
const common = fileURLToPath(new URL("../../src/main/resources/Common/", import.meta.url));

rmSync(target, { recursive: true, force: true });
for (const folder of ["Icons", "UI"]) {
  if (existsSync(common + folder)) {
    cpSync(common + folder, target + folder, { recursive: true });
  }
}
console.log(`Icônes copiées dans ${target}`);
