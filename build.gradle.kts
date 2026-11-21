import groovy.json.JsonSlurper
import java.util.Properties

// Le mod « Peuples d'Orbis » : plugin Java + pack d'assets embarqué dans le jar (docs/conception/mods.md).
plugins {
    java
}

group = "dev.nakasar.hytale"
version = project.property("mod_version").toString()

description = "Peuples d'Orbis : des villages vivants des peuples d'Orbis qui grandissent avec l'aide du joueur"

val hytaleVersion: String = project.property("hytale_version").toString()
val hytaleServerRange: String = project.property("hytale_server_range").toString()
val modGroup: String = project.property("mod_group").toString()
val modAuthor: String = project.property("mod_author").toString()

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

dependencies {
    // Le serveur n'est jamais embarqué dans le jar : il est fourni à l'exécution.
    compileOnly("com.hypixel.hytale:Server:$hytaleVersion")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.add("-Xlint:deprecation")
}
tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("Nakasar-Peuples")
}

// Le manifest.json est versionné dans src/main/resources ; on y injecte la version et la plage serveur.
tasks.named<ProcessResources>("processResources") {
    filesMatching("manifest.json") {
        expand(
            "version" to project.version.toString(),
            "serverRange" to hytaleServerRange,
            "group" to modGroup,
            "author" to modAuthor,
        )
    }
}

val hytaleServer: Configuration by configurations.creating
dependencies {
    hytaleServer("com.hypixel.hytale:Server:$hytaleVersion")
}

val serverAssets = "src/main/resources/Server"

// Valide les rôles de PNJ avec le BuilderManager du serveur (briques et clés inconnues, valeurs invalides).
val validateRoles = tasks.register<Exec>("validateRoles") {
    group = "verification"
    description = "Valide les rôles de PNJ du mod avec le chargeur de rôles du serveur Hytale."
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    inputs.dir(serverAssets)
    inputs.file("tools/ValidateRoles.java")
    inputs.files(hytaleServer)
    val marker = layout.buildDirectory.file("validateRoles.ok")
    outputs.file(marker)
    doFirst {
        executable = launcher.get().executablePath.asFile.absolutePath
        args("-Dstdout.encoding=UTF-8", "--sun-misc-unsafe-memory-access=allow", "-cp", hytaleServer.asPath, file("tools/ValidateRoles.java").absolutePath,
            file(serverAssets).absolutePath)
    }
    doLast {
        marker.get().asFile.writeText("ok")
    }
}

// Décode tous les assets du pack avec les codecs du serveur (instances, portails, objets, effets, interactions,
// particules...) et vérifie les ids de blocs des prefabs.
val validateAssets = tasks.register<Exec>("validateAssets") {
    group = "verification"
    description = "Valide les assets du mod avec les codecs du serveur Hytale."
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    val blocks = file("tools/vanilla-block-ids-0.7.2.txt")
    val gradients = file("tools/vanilla-gradient-sets-0.7.2.txt")
    val refs = file("tools/vanilla-refs-0.7.2.txt")
    val stereo = file("tools/vanilla-stereo-sounds-0.7.2.txt")
    val items = file("tools/vanilla-item-ids-0.7.2.txt")
    val prefabs = file("tools/vanilla-prefabs-0.7.2.txt")
    val uiTextures = file("tools/vanilla-ui-textures-0.7.7.txt")
    inputs.dir(serverAssets)
    inputs.dir(file("src/main/resources/Common/UI"))
    inputs.file("tools/ValidateAssets.java")
    inputs.files(blocks, gradients, refs, stereo, items, prefabs, uiTextures)
    inputs.files(hytaleServer)
    // Les classes du plugin : types d'assets écrits en Java (dialogues).
    val pluginClasses = sourceSets.main.get().output.classesDirs
    inputs.files(pluginClasses)
    dependsOn(tasks.named("classes"))
    val marker = layout.buildDirectory.file("validateAssets.ok")
    outputs.file(marker)
    doFirst {
        executable = launcher.get().executablePath.asFile.absolutePath
        // -Dblocks : ids de blocs vanilla, pour repérer dans les prefabs les blocs inconnus (damier rose en jeu).
        // -Drefs : listes de butin et rôles vanilla cités par le mod (un id inconnu fait échouer le mod en jeu).
        // -Dstereo : sons vanilla stéréo, refusés en WorldSoundEventId (l'interaction entière est rejetée en jeu).
        // -Ditems : objets vanilla, pour les ItemId des dialogues.
        // -Dprefabs : prefabs vanilla (chemins et emprises relevés dans Assets.zip), pour les plans de village.
        // -Duitextures : textures vanilla des pages, pour les .ui du mod (tableau du village, dialogue).
        args("-Dstdout.encoding=UTF-8", "--sun-misc-unsafe-memory-access=allow", "-Dblocks=" + blocks.absolutePath, "-Dgradients=" + gradients.absolutePath,
            "-Drefs=" + refs.absolutePath, "-Dstereo=" + stereo.absolutePath, "-Ditems=" + items.absolutePath, "-Dprefabs=" + prefabs.absolutePath,
            "-Duitextures=" + uiTextures.absolutePath,
            "-cp", (hytaleServer + pluginClasses).asPath, file("tools/ValidateAssets.java").absolutePath, file(serverAssets).absolutePath)
    }
    doLast {
        marker.get().asFile.writeText("ok")
    }
}

// Le serveur refuse deux systèmes de la même classe (« is already registered! ») et le plugin entier échoue :
// tools/CheckSystemRegistrations.java le repère dans les classes compilées.
val checkSystemRegistrations = tasks.register<Exec>("checkSystemRegistrations") {
    group = "verification"
    description = "Vérifie qu'aucune classe de système n'est enregistrée deux fois (registerSystem)."
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    val tool = file("tools/CheckSystemRegistrations.java")
    val classes = sourceSets.main.get().output.classesDirs
    inputs.file(tool)
    inputs.files(classes)
    dependsOn(tasks.named("classes"))
    val marker = layout.buildDirectory.file("checkSystemRegistrations.ok")
    outputs.file(marker)
    doFirst {
        executable = launcher.get().executablePath.asFile.absolutePath
        args(listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", tool.absolutePath) + classes.files.map { it.absolutePath })
    }
    doLast {
        marker.get().asFile.writeText("ok")
    }
}

// Vérifie qu'aucun id de terrain (E1, E2…) n'est écrit à la main dans le code : un bâtiment de projet se cherche
// sur tous les terrains du plan (village.Plots.projectBuildings), Kevin 2026-11-21.
val checkPlotLookups = tasks.register<Exec>("checkPlotLookups") {
    group = "verification"
    description = "Refuse les ids de terrain écrits à la main (recherche de bâtiment limitée à certains terrains)."
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    val tool = file("tools/CheckPlotLookups.java")
    val sources = file("src/main/java")
    inputs.file(tool)
    inputs.dir(sources)
    val marker = layout.buildDirectory.file("checkPlotLookups.ok")
    outputs.file(marker)
    doFirst {
        executable = launcher.get().executablePath.asFile.absolutePath
        args(listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", tool.absolutePath, sources.absolutePath))
    }
    doLast {
        marker.get().asFile.writeText("ok")
    }
}

// Vérifie que chaque fichier JSON d'assets est syntaxiquement valide.
// Les assets Hytale sont lus par un parseur strict : une virgule en trop fait échouer le chargement.
val validateJson = tasks.register("validateJson") {
    group = "verification"
    description = "Vérifie la syntaxe de tous les fichiers JSON du mod (assets et manifest)."
    val jsonFiles = fileTree("src") {
        include("**/*.json")
        include("**/*.blockymodel") // JSON aussi (modèles client)
    }
    inputs.files(jsonFiles)
    doLast {
        val slurper = JsonSlurper()
        var errors = 0
        jsonFiles.forEach { file ->
            try {
                slurper.parse(file)
            } catch (e: Exception) {
                errors++
                logger.error("JSON invalide : ${file.relativeTo(rootDir)} -> ${e.message}")
            }
        }
        if (errors > 0) throw GradleException("$errors fichier(s) JSON invalide(s).")
        logger.lifecycle("validateJson : ${jsonFiles.files.size} fichiers JSON valides.")
    }
}

tasks.named("check") {
    dependsOn(validateJson, checkSystemRegistrations, checkPlotLookups, validateRoles, validateAssets)
}

// Lit hytale.modsDir depuis -P, gradle.properties ou gradle-local.properties (non versionné).
fun resolveModsDir(): String? {
    val fromProject = (project.findProperty("hytale.modsDir") as String?)?.takeIf { it.isNotBlank() }
    if (fromProject != null) return fromProject
    val local = file("gradle-local.properties")
    if (!local.exists()) return null
    val props = Properties().apply { local.inputStream().use { load(it) } }
    return props.getProperty("hytale.modsDir")?.takeIf { it.isNotBlank() }
}

// Copie le jar construit dans le dossier mods/ d'un serveur local.
tasks.register<Copy>("deployToServer") {
    group = "hytale"
    description = "Copie le jar construit dans le dossier mods/ du serveur (propriété hytale.modsDir)."
    dependsOn(tasks.named("jar"))
    val modsDir = resolveModsDir()
    onlyIf {
        if (modsDir == null) logger.warn("deployToServer : définissez hytale.modsDir (gradle-local.properties ou -Phytale.modsDir=...).")
        modsDir != null
    }
    from(tasks.named("jar"))
    into(modsDir ?: layout.buildDirectory.dir("deploy-noop"))
}
