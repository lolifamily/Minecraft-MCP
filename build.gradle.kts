plugins {
    // Versions live in settings.gradle.kts pluginManagement; apply-false here so the subprojects that apply them
    // do so without repeating one — and so all of them share a SINGLE plugin load off this classpath.
    id("net.neoforged.moddev") apply false
    // Applied, not apply-false: the root is the aggregator. Version rides buildSrc's classpath like the others.
    id("org.jetbrains.dokka")
    // Isolated Projects forbids the root configuring its children, so what was allprojects{} is now a convention
    // every project applies itself. The root is one of them.
    id("mcp-base")
}

// The root declared no repositories until now; Dokka's own analysis stack (dokka-core, analysis-kotlin-symbols,
// templating-plugin) resolves HERE, in the aggregator. Same reason bridge carries one.
repositories {
    mavenCentral()
}

// One doc site for the whole mod. Each module still generates its own pages; the root stitches them together and
// writes the index. `./gradlew :dokkaGenerateHtml` -> build/dokka/html. The leading colon is not optional: an
// unqualified task name matches EVERY project, so it would run each module's own generator too and write one site
// per project instead of one aggregate. Aggregation itself is cheap — the root config carries zero source sets and just substitutes
// path templates in each module's pre-rendered HTML.
// `it.path` only: the root is configured before any subproject, so anything needing the target evaluated
// (`plugins.hasPlugin`) reads false here and would silently aggregate nothing. Path-only access is also all
// Isolated Projects permits on `subprojects`.
dependencies {
    subprojects.forEach { dokka(project(it.path)) }
}

// Dokka reads its header logo and its favicon from one bundled asset, images/logo-icon.svg, and a customAssets
// file of that name replaces it — the name is the whole contract. branding/ keeps neutral names, so rename on the
// way in, as multiloader-loader does for the mod-list logo. Sync, not Copy: the directory is handed over whole, so
// a stale file in it would ship.
val dokkaAssets = tasks.register<Sync>("dokkaAssets") {
    from(layout.settingsDirectory.file("branding/icon.svg")) { rename("icon.svg", "logo-icon.svg") }
    into(layout.buildDirectory.dir("dokkaAssets"))
}

// Here and nowhere else: a module renders with template substitution delayed and copies no assets, so the
// aggregate's images/ comes from the root's configuration alone. The per-module javadocJar sites keep Dokka's logo.
dokka {
    // The header shows the root's moduleName, which defaults to project.name: the repo slug, an identifier. This
    // site is the homepage (wrangler.jsonc serves it), so it carries the display name the mod lists show.
    moduleName.set(providers.gradleProperty("mod_name"))
    pluginsConfiguration.html {
        customAssets.from(files(dokkaAssets).asFileTree)
    }
}
